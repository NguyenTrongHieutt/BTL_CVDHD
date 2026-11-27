package vn.nhom17.banhang.platform.internal.tenant;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.scheduling.annotation.Scheduled;

/** Chọn một nguồn đọc cho cả request. Hook RLS của TenantTransactionManager tiếp tục chạy trên nguồn đó. */
final class ReplicaDataSource extends AbstractDataSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ReplicaDataSource.class);
    private static final long MAX_CHECK_AGE = Duration.ofSeconds(6).toNanos();

    private final HikariDataSource primary;
    private final List<Replica> replicas;
    private final AtomicInteger next = new AtomicInteger();
    private final ThreadLocal<ReadRoute> route = new ThreadLocal<>();

    ReplicaDataSource(HikariDataSource primary, List<HikariDataSource> replicas) {
        this.primary = primary;
        this.replicas = replicas.stream().map(Replica::new).toList();
    }

    void beginRead() {
        int start = Math.floorMod(next.getAndIncrement(), replicas.size());
        for (int i = 0; i < replicas.size(); i++) {
            Replica replica = replicas.get((start + i) % replicas.size());
            Long lastCheck = replica.caughtUpAt;
            if (lastCheck != null && System.nanoTime() - lastCheck < MAX_CHECK_AGE) {
                route.set(new ReadRoute(replica));
                return;
            }
        }
    }

    void endRead() {
        route.remove();
    }

    @Override
    public Connection getConnection() throws SQLException {
        ReadRoute selection = route.get();
        if (selection != null && !selection.onPrimary) {
            try {
                return selection.replica.pool.getConnection();
            } catch (SQLException failure) {
                selection.onPrimary = true;
                selection.replica.caughtUpAt = null;
                LOG.debug("Chuyển lượt đọc sang primary vì chưa lấy được kết nối replica", failure);
            }
        }
        return primary.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return primary.getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : primary.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || primary.isWrapperFor(type);
    }

    @Scheduled(fixedDelayString = "${app.db.replica-check-interval-ms:1000}")
    void checkReplication() {
        String primaryLsn;
        try (var connection = primary.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            try (var rows = statement.executeQuery("select pg_current_wal_lsn()::text")) {
                primaryLsn = rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException failure) {
            replicas.forEach(replica -> replica.caughtUpAt = null);
            LOG.debug("Chưa đọc được mốc WAL của primary", failure);
            return;
        }
        for (Replica replica : replicas) checkReplica(replica, primaryLsn);
    }

    private void checkReplica(Replica replica, String primaryLsn) {
        try (var connection = replica.pool.getConnection(); var statement = connection.prepareStatement("""
                select pg_is_in_recovery() and pg_last_wal_replay_lsn() >= cast(? as pg_lsn)
                """)) {
            statement.setString(1, primaryLsn);
            statement.setQueryTimeout(1);
            try (var rows = statement.executeQuery()) {
                replica.caughtUpAt = rows.next() && rows.getBoolean(1) ? System.nanoTime() : null;
            }
        } catch (SQLException failure) {
            replica.caughtUpAt = null;
            LOG.debug("Replica chưa sẵn sàng đọc", failure);
        }
    }

    @Override
    public void close() {
        replicas.forEach(replica -> replica.pool.close());
        primary.close();
    }

    private static final class Replica {
        private final HikariDataSource pool;
        private volatile Long caughtUpAt;

        private Replica(HikariDataSource pool) {
            this.pool = pool;
        }
    }

    private static final class ReadRoute {
        private final Replica replica;
        private boolean onPrimary;

        private ReadRoute(Replica replica) {
            this.replica = replica;
        }
    }
}
