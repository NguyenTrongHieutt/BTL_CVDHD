package vn.nhom17.banhang.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
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

import org.junit.jupiter.api.Test;

import vn.nhom17.banhang.reporting.internal.ReportingQueryCache.Key;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.DateRange;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.GrossProfitDay;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.QueryResponse;

class ReportingQueryCacheTest {
    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private final AtomicLong nanos = new AtomicLong();

    @Test
    void reusesAnIdenticalReportUntilTheFixedExpiryEvenWhenItIsReadRepeatedly() {
        ReportingQueryCache cache = cache(10, 1_000_000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<QueryResponse> load = () -> response(loads.incrementAndGet(), 30);
        Key key = key(STORE, "Q3", 30, null, null, 0, null);

        assertThat(cache.get(key, load).summary()).isEqualTo(Map.of("grossProfit", 1L));
        nanos.set(Duration.ofSeconds(9).toNanos());
        assertThat(cache.get(key, load).summary()).isEqualTo(Map.of("grossProfit", 1L));
        nanos.set(Duration.ofSeconds(10).toNanos());
        assertThat(cache.get(key, load).summary()).isEqualTo(Map.of("grossProfit", 2L));
        assertThat(loads).hasValue(2);
    }

    @Test
    void isolatesStoresAndEveryRankingFilter() {
        ReportingQueryCache cache = cache(20, 1_000_000);
        UUID category = UUID.randomUUID();
        UUID otherStore = UUID.randomUUID();
        List<Key> keys = List.of(
                key(STORE, "Q4", 30, "BEST_SELLING", "QUANTITY", 10, category),
                key(otherStore, "Q4", 30, "BEST_SELLING", "QUANTITY", 10, category),
                key(STORE, "Q4", 31, "BEST_SELLING", "QUANTITY", 10, category),
                new Key(STORE, "Q4", new DateRange(FROM.plusDays(1), FROM.plusDays(30)),
                        "BEST_SELLING", "QUANTITY", 10, category),
                key(STORE, "Q4", 30, "SLOW_SELLING", "QUANTITY", 10, category),
                key(STORE, "Q4", 30, "BEST_SELLING", "REVENUE", 10, category),
                key(STORE, "Q4", 30, "BEST_SELLING", "QUANTITY", 20, category),
                key(STORE, "Q4", 30, "BEST_SELLING", "QUANTITY", 10, null),
                key(STORE, "Q3", 30, null, null, 0, null));
        AtomicInteger loads = new AtomicInteger();
        for (Key key : keys) {
            cache.get(key, () -> response(loads.incrementAndGet(), 30));
        }
        for (int index = 0; index < keys.size(); index++) {
            assertThat(cache.get(keys.get(index), () -> response(loads.incrementAndGet(), 30)).summary())
                    .isEqualTo(Map.of("grossProfit", (long) index + 1));
        }
        assertThat(loads).hasValue(keys.size());
    }

    @Test
    void leavesDailyReportsAndInventoryUncached() {
        ReportingQueryCache cache = cache(10, 1_000_000);
        for (Key key : List.of(key(STORE, "Q3", 29, null, null, 0, null),
                key(STORE, "Q4", 1, "BEST_SELLING", "QUANTITY", 10, null),
                key(STORE, "Q1", 365, null, null, 0, null),
                key(STORE, "Q5", 365, null, null, 0, null))) {
            AtomicInteger loads = new AtomicInteger();
            cache.get(key, () -> response(loads.incrementAndGet(), 30));
            assertThat(cache.get(key, () -> response(loads.incrementAndGet(), 30)).summary())
                    .isEqualTo(Map.of("grossProfit", 2L));
        }
    }

    @Test
    void disabledOrZeroBudgetCachesStillRunTheReport() {
        for (ReportingQueryCache cache : List.of(
                new ReportingQueryCache(false, Duration.ofSeconds(10), 10, 1_000_000, nanos::get),
                new ReportingQueryCache(true, Duration.ZERO, 10, 1_000_000, nanos::get),
                cache(0, 1_000_000), cache(10, 0))) {
            AtomicInteger loads = new AtomicInteger();
            Key key = key(STORE, "Q3", 30, null, null, 0, null);
            cache.get(key, () -> response(loads.incrementAndGet(), 30));
            assertThat(cache.get(key, () -> response(loads.incrementAndGet(), 30)).summary())
                    .isEqualTo(Map.of("grossProfit", 2L));
        }
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryAtTheEntryLimit() {
        ReportingQueryCache cache = cache(2, 1_000_000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<QueryResponse> load = () -> response(loads.incrementAndGet(), 30);
        Key first = key(STORE, "Q3", 30, null, null, 0, null);
        Key second = key(STORE, "Q3", 31, null, null, 0, null);
        Key third = key(STORE, "Q3", 32, null, null, 0, null);
        cache.get(first, load);
        cache.get(second, load);
        cache.get(first, load);
        cache.get(third, load);

        assertThat(cache.get(first, load).summary()).isEqualTo(Map.of("grossProfit", 1L));
        assertThat(cache.get(second, load).summary()).isEqualTo(Map.of("grossProfit", 4L));
        assertThat(loads).hasValue(4);
    }

    @Test
    void evictsBeforeTheRetainedByteBudgetIsExceededAndSkipsOversizedEntries() {
        ReportingQueryCache cache = cache(10, 8_000);
        AtomicInteger loads = new AtomicInteger();
        Supplier<QueryResponse> load = () -> response(loads.incrementAndGet(), 30);
        Key first = key(STORE, "Q3", 30, null, null, 0, null);
        Key second = key(STORE, "Q3", 31, null, null, 0, null);
        cache.get(first, load);
        cache.get(second, load);
        assertThat(cache.get(second, load).summary()).isEqualTo(Map.of("grossProfit", 2L));
        assertThat(cache.get(first, load).summary()).isEqualTo(Map.of("grossProfit", 3L));

        ReportingQueryCache tiny = cache(10, 1_000);
        tiny.get(first, load);
        assertThat(tiny.get(first, load).summary()).isEqualTo(Map.of("grossProfit", 5L));
    }

    @Test
    void concurrentIdenticalMissesShareOneLoad() throws Exception {
        ReportingQueryCache cache = cache(10, 1_000_000);
        Key key = key(STORE, "Q3", 365, null, null, 0, null);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch requested = new CountDownLatch(8);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<QueryResponse>>();
            for (int index = 0; index < 8; index++) {
                results.add(executor.submit(() -> {
                    requested.countDown();
                    return cache.get(key, () -> {
                        loads.incrementAndGet();
                        loading.countDown();
                        await(release);
                        return response(7, 365);
                    });
                }));
            }
            try {
                assertThat(requested.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(loading.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            for (var result : results) {
                assertThat(result.get(3, TimeUnit.SECONDS).summary()).isEqualTo(Map.of("grossProfit", 7L));
            }
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void aSlowFillCannotExtendTheExpiryBeyondItsStartTime() {
        ReportingQueryCache cache = cache(10, 1_000_000);
        Key key = key(STORE, "Q3", 30, null, null, 0, null);
        cache.get(key, () -> {
            nanos.set(Duration.ofSeconds(11).toNanos());
            return response(1, 30);
        });
        assertThat(cache.get(key, () -> response(2, 30)).summary()).isEqualTo(Map.of("grossProfit", 2L));
    }

    @Test
    void failedOrInterruptedLoadsAreNeverRetained() {
        ReportingQueryCache cache = cache(10, 1_000_000);
        Key key = key(STORE, "Q3", 30, null, null, 0, null);
        RuntimeException databaseFailure = new IllegalStateException("database unavailable");
        assertThatThrownBy(() -> cache.get(key, () -> { throw databaseFailure; })).isSameAs(databaseFailure);
        try {
            cache.get(key, () -> {
                Thread.currentThread().interrupt();
                return response(1, 30);
            });
        } finally {
            Thread.interrupted();
        }
        assertThat(cache.get(key, () -> response(2, 30)).summary()).isEqualTo(Map.of("grossProfit", 2L));
    }

    @Test
    void cacheBookkeepingFailureFallsBackToTheUncachedReport() {
        ReportingQueryCache cache = new ReportingQueryCache(true, Duration.ofSeconds(10), 10, 1_000_000,
                () -> { throw new IllegalStateException("timer unavailable"); });
        assertThat(cache.get(key(STORE, "Q3", 30, null, null, 0, null), () -> response(42, 30)).summary())
                .isEqualTo(Map.of("grossProfit", 42L));
    }

    private ReportingQueryCache cache(int maxEntries, long maxBytes) {
        return new ReportingQueryCache(true, Duration.ofSeconds(10), maxEntries, maxBytes, nanos::get);
    }

    private static Key key(UUID store, String query, int days, String direction, String rankBy,
                           int limit, UUID categoryId) {
        return new Key(store, query, new DateRange(FROM, FROM.plusDays(days - 1)), direction, rankBy, limit, categoryId);
    }

    private static QueryResponse response(long value, int days) {
        List<GrossProfitDay> rows = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            rows.add(new GrossProfitDay(FROM.plusDays(index), value, 0, value));
        }
        return new QueryResponse("Q3", new DateRange(FROM, FROM.plusDays(days - 1)),
                Map.of("fromDate", FROM.toString(), "toDate", FROM.plusDays(days - 1).toString()),
                List.of(), rows, Map.of("grossProfit", value));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("load was not released");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
