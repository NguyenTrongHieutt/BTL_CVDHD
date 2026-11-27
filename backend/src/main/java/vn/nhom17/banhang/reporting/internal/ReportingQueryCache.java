package vn.nhom17.banhang.reporting.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import vn.nhom17.banhang.reporting.internal.ReportingQueryController.DateRange;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.GrossProfitDay;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.QueryResponse;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.RankedProduct;

/**
 * Short, process-local snapshots of expensive Q3/Q4 reads, only for ranges of at least 30 days.
 * The default stale window is 10 seconds from query start (never extended by hits); a cancellation,
 * return or catalog change can remain visible in an old snapshot only within that window.
 * In-flight database reads retain their normal repeatable-read snapshot semantics.
 *
 * <p>Settings: {@code app.reporting.query-cache.enabled}, {@code ttl}, {@code max-entries},
 * {@code max-bytes}. Defaults are true, 10s, 128 and 4 MiB. Limits are capped at 30s, 1024 entries
 * and 16 MiB. Nonpositive limits disable caching. The byte limit is a conservative retained-object
 * weight for the fixed report record shapes, including keys, maps, rows and UTF-16 string storage.
 * Inventory and pricing never enter this cache. No data or locks survive a process restart.
 */
@Component
class ReportingQueryCache {
    private static final Logger LOG = LoggerFactory.getLogger(ReportingQueryCache.class);
    private static final int MIN_RANGE_DAYS = 30;
    private static final int LOCK_STRIPES = 64;
    private static final Duration MAX_TTL = Duration.ofSeconds(30);

    private final boolean enabled;
    private final long ttlNanos;
    private final int maxEntries;
    private final long maxBytes;
    private final LongSupplier ticker;
    private final ReentrantLock[] fills = new ReentrantLock[LOCK_STRIPES];
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    @Autowired
    ReportingQueryCache(@Value("${app.reporting.query-cache.enabled:true}") boolean enabled,
                        @Value("${app.reporting.query-cache.ttl:10s}") Duration ttl,
                        @Value("${app.reporting.query-cache.max-entries:128}") int maxEntries,
                        @Value("${app.reporting.query-cache.max-bytes:4194304}") long maxBytes) {
        this(enabled, ttl, maxEntries, maxBytes, System::nanoTime);
    }

    ReportingQueryCache(boolean enabled, Duration ttl, int maxEntries, long maxBytes, LongSupplier ticker) {
        this.ttlNanos = ttl.isNegative() ? 0 : (ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl).toNanos();
        this.maxEntries = Math.max(0, Math.min(maxEntries, 1024));
        this.maxBytes = Math.max(0, Math.min(maxBytes, 16L * 1024 * 1024));
        this.enabled = enabled && ttlNanos > 0 && this.maxEntries > 0 && this.maxBytes > 0;
        this.ticker = ticker;
        for (int index = 0; index < fills.length; index++) fills[index] = new ReentrantLock();
    }

    QueryResponse get(Key key, Supplier<QueryResponse> load) {
        if (!enabled || !eligible(key)) return load.get();
        try {
            QueryResponse cached = find(key);
            if (cached != null) return cached;
        } catch (RuntimeException ex) {
            LOG.debug("Reporting cache lookup failed; reading the report directly", ex);
            return load.get();
        }

        // Fixed stripes bound lock bookkeeping. Acquire before load starts its tenant transaction,
        // so requests waiting for the same report never occupy a database connection.
        ReentrantLock fill = fills[(key.hashCode() & Integer.MAX_VALUE) % fills.length];
        try {
            fill.lockInterruptibly();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("Report request interrupted while waiting");
            cancelled.initCause(ex);
            throw cancelled;
        }
        try {
            long startedAt;
            try {
                QueryResponse cached = find(key);
                if (cached != null) return cached;
                startedAt = ticker.getAsLong();
            } catch (RuntimeException ex) {
                LOG.debug("Reporting cache lookup failed; reading the report directly", ex);
                return load.get();
            }

            // SQL/transaction errors propagate unchanged, are not retried, and are never cached.
            QueryResponse result = load.get();
            if (!Thread.currentThread().isInterrupted()) {
                try {
                    retain(key, result, startedAt);
                } catch (RuntimeException ex) {
                    LOG.debug("Reporting cache update failed; returning the completed report", ex);
                }
            }
            return result;
        } finally {
            fill.unlock();
        }
    }

    private static boolean eligible(Key key) {
        return ("Q3".equals(key.queryId()) || "Q4".equals(key.queryId()))
                && key.storeId() != null
                && key.range().toDate().toEpochDay() - key.range().fromDate().toEpochDay() + 1 >= MIN_RANGE_DAYS;
    }

    private synchronized QueryResponse find(Key key) {
        long now = ticker.getAsLong();
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (now - entry.startedAt() >= ttlNanos) {
            entries.remove(key);
            retainedBytes -= entry.bytes();
            return null;
        }
        return entry.response();
    }

    private void retain(Key key, QueryResponse result, long startedAt) {
        long bytes = weight(result);
        if (bytes > maxBytes) return;
        synchronized (this) {
            long now = ticker.getAsLong();
            if (now - startedAt >= ttlNanos) return;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next().getValue();
                if (now - entry.startedAt() >= ttlNanos) {
                    retainedBytes -= entry.bytes();
                    iterator.remove();
                }
            }
            iterator = entries.entrySet().iterator();
            while (entries.size() >= maxEntries || retainedBytes + bytes > maxBytes) {
                retainedBytes -= iterator.next().getValue().bytes();
                iterator.remove();
            }
            entries.put(key, new Entry(result, startedAt, bytes));
            retainedBytes += bytes;
        }
    }

    private long weight(QueryResponse response) {
        long bytes = 2048; // Key, response, date/summary maps, columns and list/entry overhead.
        for (Object row : response.rows()) {
            if (row instanceof GrossProfitDay) {
                bytes += 128;
            } else if (row instanceof RankedProduct product) {
                bytes += 256 + stringWeight(product.productName()) + stringWeight(product.categoryName());
            } else {
                return maxBytes + 1; // Unknown shapes are never retained with an inaccurate weight.
            }
            if (bytes > maxBytes) return bytes;
        }
        return bytes;
    }

    private static long stringWeight(String value) {
        return value == null ? 0 : 64 + 2L * value.length();
    }

    record Key(UUID storeId, String queryId, DateRange range, String direction, String rankBy,
               int limit, UUID categoryId) {}

    private record Entry(QueryResponse response, long startedAt, long bytes) {}
}
