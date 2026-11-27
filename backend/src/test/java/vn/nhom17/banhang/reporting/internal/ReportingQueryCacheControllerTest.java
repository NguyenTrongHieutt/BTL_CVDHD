package vn.nhom17.banhang.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.GrossProfitDay;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.QueryResponse;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.RankedProduct;

class ReportingQueryCacheControllerTest {
    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate UNTIL = LocalDate.of(2025, 12, 31);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T03:00:00Z"), ZoneOffset.UTC);
    private final AtomicLong nanos = new AtomicLong();
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final CurrentContext context = mock(CurrentContext.class);
    private final ReportingQueryCache cache = new ReportingQueryCache(true, Duration.ofSeconds(10),
            128, 4 * 1024 * 1024, nanos::get);

    @Test
    void heavyProfitHitsAvoidTransactionsAndRefreshCancelledSalesAfterExpiry() {
        org.mockito.Mockito.when(context.storeId()).thenReturn(UUID.randomUUID());
        ReportingQueryController controller = controller();
        jdbc.rows = () -> List.of(new GrossProfitDay(FROM, 100, 40, 60));
        assertThat(query(controller, "Q3", FROM, UNTIL, null, null, null, null).summary())
                .isEqualTo(Map.of("revenue", 100L, "costOfGoodsSold", 40L, "grossProfit", 60L));

        // A cancellation changes the DB result. The cached snapshot lasts at most the short TTL.
        jdbc.rows = () -> List.of(new GrossProfitDay(FROM, 0, 0, 0));
        nanos.set(Duration.ofSeconds(9).toNanos());
        assertThat(query(controller, "Q3", FROM, UNTIL, null, null, null, null).summary())
                .isEqualTo(Map.of("revenue", 100L, "costOfGoodsSold", 40L, "grossProfit", 60L));
        assertThat(transactions.begins).hasValue(1);
        assertThat(jdbc.calls).hasValue(1);

        nanos.set(Duration.ofSeconds(10).toNanos());
        assertThat(query(controller, "Q3", FROM, UNTIL, null, null, null, null).summary())
                .isEqualTo(Map.of("revenue", 0L, "costOfGoodsSold", 0L, "grossProfit", 0L));
        assertThat(transactions.begins).hasValue(2);
    }

    @Test
    void equivalentRankingDefaultsShareAKeyAndForeignStoresDoNot() {
        UUID firstStore = UUID.randomUUID();
        UUID secondStore = UUID.randomUUID();
        org.mockito.Mockito.when(context.storeId()).thenReturn(firstStore);
        UUID product = UUID.randomUUID();
        jdbc.rows = () -> List.of(new RankedProduct(product, "Sản phẩm A", "Danh mục A", 10, 100, 10, 0));
        ReportingQueryController controller = controller();
        QueryResponse first = query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", null, null, null);
        jdbc.rows = List::of;
        assertThat(query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", "QUANTITY", 10, null).rows())
                .isEqualTo(first.rows());
        assertThat(jdbc.calls).hasValue(1);

        org.mockito.Mockito.when(context.storeId()).thenReturn(secondStore);
        assertThat(query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", "QUANTITY", 10, null).rows()).isEmpty();
        assertThat(jdbc.calls).hasValue(2);
        assertThat(jdbc.lastParameters.get("storeId")).isEqualTo(secondStore);
    }

    @Test
    void dailyProfitAndCurrentStockReadTheDatabaseOnEveryRequest() {
        org.mockito.Mockito.when(context.storeId()).thenReturn(UUID.randomUUID());
        ReportingQueryController controller = controller();
        jdbc.rows = () -> List.of(new GrossProfitDay(FROM, 100, 40, 60));
        query(controller, "Q3", FROM, FROM, null, null, null, null);
        jdbc.rows = () -> List.of(new GrossProfitDay(FROM, 0, 0, 0));
        assertThat(query(controller, "Q3", FROM, FROM, null, null, null, null).summary())
                .isEqualTo(Map.of("revenue", 0L, "costOfGoodsSold", 0L, "grossProfit", 0L));
        jdbc.rows = List::of;
        query(controller, "Q5", null, null, null, null, null, null);
        query(controller, "Q5", null, null, null, null, null, null);
        assertThat(jdbc.calls).hasValue(4);
        assertThat(transactions.begins).hasValue(4);
    }

    @Test
    void aWarmCacheDoesNotBypassRankingValidation() {
        org.mockito.Mockito.when(context.storeId()).thenReturn(UUID.randomUUID());
        jdbc.rows = List::of;
        ReportingQueryController controller = controller();
        query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", null, null, null);
        assertThatThrownBy(() -> query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", "bad", null, null))
                .isInstanceOf(ProblemException.class);
        assertThatThrownBy(() -> query(controller, "Q4", FROM, UNTIL, "BEST_SELLING", null, 51, null))
                .isInstanceOf(ProblemException.class);
        assertThat(transactions.begins).hasValue(1);
    }

    @Test
    void concurrentHeavyReportsWaitBeforeStartingAnyDatabaseTransaction() throws Exception {
        org.mockito.Mockito.when(context.storeId()).thenReturn(UUID.randomUUID());
        ReportingQueryController controller = controller();
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch requested = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        jdbc.rows = () -> {
            loading.countDown();
            await(release);
            return List.of(new GrossProfitDay(FROM, 100, 40, 60));
        };
        try (var executor = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<java.util.concurrent.Future<QueryResponse>>();
            for (int index = 0; index < 6; index++) {
                results.add(executor.submit(() -> {
                    requested.countDown();
                    return query(controller, "Q3", FROM, UNTIL, null, null, null, null);
                }));
            }
            try {
                assertThat(requested.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(transactions.begins).hasValue(1);
            } finally {
                release.countDown();
            }
            for (var result : results) {
                assertThat(result.get(3, TimeUnit.SECONDS).summary())
                        .isEqualTo(Map.of("revenue", 100L, "costOfGoodsSold", 40L, "grossProfit", 60L));
            }
        }
        assertThat(transactions.begins).hasValue(1);
        assertThat(jdbc.calls).hasValue(1);
    }

    private ReportingQueryController controller() {
        return new ReportingQueryController(jdbc, context, CLOCK, transactions, cache, mock(ReportingWarehouse.class));
    }

    private static QueryResponse query(ReportingQueryController controller, String queryId,
                                       LocalDate from, LocalDate to, String direction, String rankBy,
                                       Integer limit, UUID categoryId) {
        return controller.query(queryId, from, to, direction, rankBy, limit, null, null, categoryId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("database load was not released");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static final class RecordingJdbc extends NamedParameterJdbcTemplate {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Supplier<List<?>> rows = List::of;
        private volatile Map<String, ?> lastParameters;

        RecordingJdbc() {
            super(mock(DataSource.class));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, Map<String, ?> parameters, RowMapper<T> mapper) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            calls.incrementAndGet();
            lastParameters = new java.util.HashMap<>(parameters);
            return (List<T>) rows.get();
        }
    }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private final AtomicInteger begins = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
