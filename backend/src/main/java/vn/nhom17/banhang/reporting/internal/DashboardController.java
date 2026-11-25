package vn.nhom17.banhang.reporting.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.TimeZones;

@RestController
@RequestMapping("/api/v1/reporting")
class DashboardController {
    private static final int RANKING_DAYS = 7;
    private static final int TOP_PRODUCTS = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    DashboardController(NamedParameterJdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @GetMapping("/dashboard")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Dashboard dashboard() {
        UUID storeId = context.storeId();
        LocalDate date = LocalDate.now(clock.withZone(TimeZones.VIETNAM));
        OffsetDateTime end = startOfDay(date.plusDays(1));
        SalesTotals today = totals(storeId, startOfDay(date), end);
        List<RankedProduct> top = topProducts(storeId, startOfDay(date.minusDays(RANKING_DAYS - 1)), end);
        return new Dashboard(date, today, top);
    }

    private SalesTotals totals(UUID storeId, OffsetDateTime from, OffsetDateTime until) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(revenue), 0) AS revenue, coalesce(sum(orders), 0) AS order_count
                FROM (
                    SELECT total_amount AS revenue, 1 AS orders
                    FROM app.sales_order
                    WHERE store_id = :storeId AND status <> 'CANCELLED'
                      AND created_at >= :from AND created_at < :until
                    UNION ALL
                    SELECT -r.refund_amount, 0
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                ) events
                """, Map.of("storeId", storeId, "from", from, "until", until),
                (rs, row) -> new SalesTotals(rs.getLong("revenue"), rs.getLong("order_count")));
    }

    private List<RankedProduct> topProducts(UUID storeId, OffsetDateTime from, OffsetDateTime until) {
        return jdbc.query("""
                WITH movements AS (
                    SELECT l.variant_id, l.quantity, l.net_line_amount AS revenue
                    FROM app.sales_order o
                    JOIN app.sales_order_line l ON l.order_id = o.id AND l.store_id = o.store_id
                    WHERE o.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND o.created_at >= :from AND o.created_at < :until
                    UNION ALL
                    SELECT l.variant_id, -l.quantity, -l.refund_amount
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    JOIN app.sales_return_line l ON l.return_id = r.id AND l.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                )
                SELECT p.id, p.name, sum(m.quantity) AS quantity, sum(m.revenue) AS revenue
                FROM movements m
                JOIN app.product_variant v ON v.id = m.variant_id AND v.store_id = :storeId
                JOIN app.product p ON p.id = v.product_id AND p.store_id = v.store_id
                GROUP BY p.id, p.name
                HAVING sum(m.quantity) > 0
                ORDER BY quantity DESC, revenue DESC, p.name, p.id
                LIMIT :limit
                """, Map.of("storeId", storeId, "from", from, "until", until, "limit", TOP_PRODUCTS),
                (rs, row) -> new RankedProduct(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("quantity"), rs.getLong("revenue")));
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TimeZones.VIETNAM).toOffsetDateTime();
    }

    record Dashboard(LocalDate date, SalesTotals today, List<RankedProduct> top5Last7Days) {}
    record SalesTotals(long revenue, long orderCount) {}
    record RankedProduct(UUID productId, String name, long quantity, long revenue) {}
}
