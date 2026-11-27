package vn.nhom17.banhang.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import vn.nhom17.banhang.platform.CurrentContext;

@Testcontainers
class ReportingWarehouseIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private static final UUID STORE_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate FROM = LocalDate.parse("2026-11-26");
    private static final LocalDate TO = LocalDate.parse("2026-11-27");
    private static TimeZone originalTimeZone;
    @Container
    static final PostgreSQLContainer WAREHOUSE = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("banhang_warehouse")
            .withEnv("DB_WAREHOUSE_WRITER_PASSWORD", "writer-test")
            .withEnv("DB_WAREHOUSE_READER_PASSWORD", "reader-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/cdc/00-init.sh"), 0755),
                    "/docker-entrypoint-initdb.d/00-init.sh")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/cdc/schema.sql")),
                    "/docker-entrypoint-initdb.d/01-schema.sql");
    private JdbcTemplate admin;

    @BeforeAll
    static void usePortableJdbcTimeZone() {
        // Windows can advertise the legacy Asia/Saigon alias, absent from stock PG18's tzdata.
        // The report's date grouping remains explicitly Asia/Ho_Chi_Minh and is asserted below.
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restoreJdbcTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @BeforeEach
    void prepare() {
        admin = new JdbcTemplate(new DriverManagerDataSource(WAREHOUSE.getJdbcUrl(),
                WAREHOUSE.getUsername(), WAREHOUSE.getPassword()));
        admin.execute("TRUNCATE app.sales_order, app.sales_order_line, app.sales_return, app.sales_return_line");
        admin.update("""
                UPDATE warehouse.cdc_state SET snapshot_completed = true, caught_up = true,
                    next_offset = 15, last_heartbeat_at = '2026-11-27T03:00:00Z',
                    last_poll_at = '2026-11-27T03:00:00Z', last_error = NULL
                """);
    }

    @Test
    void q1AndQ3UseWarehouseWithVietnameseDatesSnapshotCostReturnsCancellationsAndTenantIsolation() {
        UUID order = order(STORE_A, "COMPLETED", 72_000, 8_000, "2026-11-25T17:00:00Z");
        UUID variant = UUID.randomUUID();
        UUID line = line(STORE_A, order, variant, 3, 13_000);
        returned(STORE_A, order, line, variant, 1, 24_000, "2026-11-26T17:00:00Z");
        UUID cancelled = order(STORE_A, "CANCELLED", 900_000, 0, "2026-11-25T17:00:00Z");
        line(STORE_A, cancelled, UUID.randomUUID(), 5, 70_000);
        UUID foreign = order(STORE_B, "COMPLETED", 500_000, 0, "2026-11-25T17:00:00Z");
        line(STORE_B, foreign, UUID.randomUUID(), 1, 100_000);

        try (var warehouse = warehouse()) {
            var controller = controller(warehouse, STORE_A);
            assertThat(query(controller, "Q1").summary()).isEqualTo(Map.of("grossSales", 72_000L,
                    "refundTotal", 24_000L, "revenue", 48_000L, "discountTotal", 8_000L));
            assertThat(query(controller, "Q3").rows()).isEqualTo(List.of(
                    new ReportingQueryController.GrossProfitDay(FROM, 72_000, 39_000, 33_000),
                    new ReportingQueryController.GrossProfitDay(TO, -24_000, -13_000, -11_000)));
            assertThat(query(controller(warehouse, STORE_B), "Q3").summary()).isEqualTo(
                    Map.of("revenue", 500_000L, "costOfGoodsSold", 100_000L, "grossProfit", 400_000L));
        }
    }

    @Test
    void unfinishedSnapshotBacklogAndStaleSourceNeverServeWarehouseRows() {
        try (var warehouse = warehouse()) {
            assertThat(warehouse.status().available()).isTrue();
            for (String update : new String[]{"snapshot_completed = false", "caught_up = false",
                    "last_heartbeat_at = '2026-11-27T02:59:00Z'", "last_poll_at = '2026-11-27T02:59:00Z'"}) {
                prepare();
                admin.update("UPDATE warehouse.cdc_state SET " + update);
                assertThat(warehouse.read(STORE_A, jdbc -> { throw new AssertionError("unsafe warehouse read"); }))
                        .isEmpty();
                assertThat(warehouse.status().available()).isFalse();
            }
        }
        try (var unavailable = new ReportingWarehouse(true, "jdbc:postgresql://127.0.0.1:1/absent",
                "warehouse_reader", "reader-test", Duration.ofSeconds(30), CLOCK)) {
            assertThat(unavailable.read(STORE_A, jdbc -> 1)).isEmpty();
        }
    }

    @Test
    void warehouseQueriesHaveTransactionLocalTenantReadOnlyAndRepeatableRead() {
        order(STORE_A, "COMPLETED", 10_000, 0, "2026-11-25T17:00:00Z");
        order(STORE_B, "COMPLETED", 20_000, 0, "2026-11-25T17:00:00Z");
        try (var warehouse = warehouse()) {
            assertThat(warehouse.read(STORE_A, jdbc -> jdbc.queryForMap("""
                    SELECT current_setting('transaction_read_only') AS read_only,
                           current_setting('transaction_isolation') AS isolation,
                           count(*) AS rows, sum(total_amount) AS amount FROM app.sales_order
                    """, Map.of()))).hasValueSatisfying(row -> {
                        assertThat(row.get("read_only")).isEqualTo("on");
                        assertThat(row.get("isolation")).isEqualTo("repeatable read");
                        assertThat(row.get("rows")).isEqualTo(1L);
                        assertThat(row.get("amount").toString()).isEqualTo("10000");
                    });
            assertThat(warehouse.read(STORE_B, jdbc -> jdbc.queryForObject(
                    "SELECT sum(total_amount)::bigint FROM app.sales_order", Map.of(), Long.class))).contains(20_000L);
            assertThat(warehouse.read(STORE_A, jdbc -> jdbc.update("DELETE FROM app.sales_order", Map.of()))).isEmpty();
            assertThat(admin.queryForObject("SELECT count(*) FROM app.sales_order", Long.class)).isEqualTo(2L);
        }
    }

    private ReportingWarehouse warehouse() {
        return new ReportingWarehouse(true, WAREHOUSE.getJdbcUrl(), "warehouse_reader", "reader-test",
                Duration.ofSeconds(30), CLOCK);
    }

    private ReportingQueryController controller(ReportingWarehouse warehouse, UUID storeId) {
        var context = mock(CurrentContext.class);
        when(context.storeId()).thenReturn(storeId);
        // Any accidental primary read fails; these fixtures exist exclusively in the reporting warehouse.
        var primary = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/absent", "nobody", "none");
        return new ReportingQueryController(new NamedParameterJdbcTemplate(primary), context, CLOCK,
                new DataSourceTransactionManager(primary), new ReportingQueryCache(false, Duration.ZERO, 0, 0), warehouse);
    }

    private ReportingQueryController.QueryResponse query(ReportingQueryController controller, String id) {
        return controller.query(id, FROM, TO, null, null, null, null, null, null);
    }

    private UUID order(UUID store, String status, long amount, long discount, String at) {
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO app.sales_order (id, store_id, status, total_amount, line_discount_total,
                    order_discount, points_discount, created_at) VALUES (?, ?, ?, ?, 0, ?, 0, ?::timestamptz)
                """, id, store, status, amount, discount, at);
        return id;
    }

    private UUID line(UUID store, UUID order, UUID variant, int quantity, long cost) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO app.sales_order_line (id, store_id, order_id, variant_id, quantity, unit_cost) VALUES (?, ?, ?, ?, ?, ?)",
                id, store, order, variant, quantity, cost);
        return id;
    }

    private void returned(UUID store, UUID order, UUID line, UUID variant, int quantity, long refund, String at) {
        UUID id = UUID.randomUUID();
        admin.update("INSERT INTO app.sales_return (id, store_id, order_id, refund_amount, created_at) VALUES (?, ?, ?, ?, ?::timestamptz)",
                id, store, order, refund, at);
        admin.update("INSERT INTO app.sales_return_line (id, store_id, return_id, order_line_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), store, id, line, variant, quantity);
    }
}
