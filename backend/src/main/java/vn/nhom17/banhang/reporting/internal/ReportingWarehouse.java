package vn.nhom17.banhang.reporting.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** A separately owned pool, never registered as the application's business datasource. */
@Component
class ReportingWarehouse implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReportingWarehouse.class);
    private final boolean enabled;
    private final Duration maxAge;
    private final Clock clock;
    private final HikariDataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    ReportingWarehouse(
            @Value("${app.reporting.warehouse.enabled:${APP_REPORTING_WAREHOUSE_ENABLED:false}}") boolean enabled,
            @Value("${app.reporting.warehouse.url:${APP_REPORTING_WAREHOUSE_URL:}}") String url,
            @Value("${app.reporting.warehouse.username:${APP_REPORTING_WAREHOUSE_USERNAME:warehouse_reader}}") String username,
            @Value("${app.reporting.warehouse.password:${APP_REPORTING_WAREHOUSE_PASSWORD:}}") String password,
            @Value("${app.reporting.warehouse.max-age:${APP_REPORTING_WAREHOUSE_MAX_AGE:30s}}") Duration maxAge,
            Clock clock) {
        this.enabled = enabled;
        this.maxAge = maxAge;
        this.clock = clock;
        if (!enabled) {
            dataSource = null;
            jdbc = null;
            readOnly = null;
            return;
        }
        if (url == null || url.isBlank() || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("Enabled reporting warehouse needs a URL and a positive max-age");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("reporting-warehouse");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "15");
        dataSource = new HikariDataSource(config);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readOnly.setTimeout(15);
    }

    <T> Optional<T> read(UUID storeId, Function<NamedParameterJdbcTemplate, T> query) {
        if (!enabled) return Optional.empty();
        try {
            return Optional.ofNullable(readOnly.execute(transaction -> {
                // Both this setting and the ready checkpoint belong to the same repeatable-read snapshot.
                jdbc.queryForObject("SELECT set_config('app.store_id', :storeId, true)",
                        Map.of("storeId", storeId.toString()), String.class);
                if (!readStatus().available()) return null;
                return query.apply(jdbc);
            }));
        } catch (DataAccessException | TransactionException failure) {
            LOG.debug("Reporting warehouse unavailable; using business database", failure);
            return Optional.empty();
        }
    }

    Status status() {
        if (!enabled) return unavailable(false);
        try {
            return readOnly.execute(transaction -> readStatus());
        } catch (DataAccessException | TransactionException failure) {
            return unavailable(true);
        }
    }

    private Status readStatus() {
        return jdbc.queryForObject("""
                SELECT snapshot_completed, caught_up, next_offset, last_heartbeat_at,
                       last_commit_at, last_poll_at, last_error IS NULL AS healthy
                FROM warehouse.cdc_state WHERE singleton
                """, Map.of(), (rs, row) -> {
            Instant heartbeat = instant(rs.getObject("last_heartbeat_at", OffsetDateTime.class));
            Instant poll = instant(rs.getObject("last_poll_at", OffsetDateTime.class));
            Instant committed = instant(rs.getObject("last_commit_at", OffsetDateTime.class));
            Long lag = heartbeat == null ? null : Math.max(0, Duration.between(heartbeat, clock.instant()).toMillis());
            boolean available = rs.getBoolean("snapshot_completed") && rs.getBoolean("caught_up")
                    && rs.getBoolean("healthy") && recent(heartbeat) && recent(poll);
            return new Status(true, available, rs.getBoolean("snapshot_completed"), rs.getBoolean("caught_up"),
                    rs.getLong("next_offset"), heartbeat, committed, poll, lag);
        });
    }

    private boolean recent(Instant timestamp) {
        return timestamp != null && !timestamp.plus(maxAge).isBefore(clock.instant());
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Status unavailable(boolean enabled) {
        return new Status(enabled, false, false, false, null, null, null, null, null);
    }

    @PreDestroy
    @Override
    public void close() {
        if (dataSource != null) dataSource.close();
    }

    record Status(boolean enabled, boolean available, boolean snapshotCompleted, boolean caughtUp,
                  Long nextOffset, Instant lastHeartbeatAt, Instant lastCommitAt, Instant lastPollAt,
                  Long heartbeatAgeMillis) {}
}
