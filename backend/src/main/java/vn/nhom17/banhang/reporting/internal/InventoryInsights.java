package vn.nhom17.banhang.reporting.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import vn.nhom17.banhang.platform.TimeZones;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.Column;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.DateRange;
import vn.nhom17.banhang.reporting.internal.ReportingQueryController.QueryResponse;

enum InventoryInsights {
    Q7(30, "stock_quantity > 0 AND (daily_sales = 0 OR stock_quantity > daily_sales * 30)",
            "stock_value DESC, daily_sales ASC"),
    Q8(14, "below_minimum OR days_of_stock < 7", "days_of_stock ASC NULLS LAST, suggested_quantity DESC"),
    Q9(30, "stock_quantity > 0 AND sale_price > avg_unit_cost "
            + "AND (daily_sales = 0 OR stock_quantity > daily_sales * 30)", "stock_value DESC");

    private static final int MAX_ROWS = 50;
    private static final String SQL = """
            WITH movements AS (
                SELECT l.variant_id, sum(l.quantity) AS quantity
                FROM app.sales_order o
                JOIN app.sales_order_line l ON l.order_id = o.id AND l.store_id = o.store_id
                WHERE o.store_id = :storeId AND o.status <> 'CANCELLED'
                  AND o.created_at >= :from AND o.created_at < :until
                GROUP BY l.variant_id
                UNION ALL
                SELECT l.variant_id, -sum(l.quantity)
                FROM app.sales_return r
                JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                JOIN app.sales_return_line l ON l.return_id = r.id AND l.store_id = r.store_id
                WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                  AND r.created_at >= :from AND r.created_at < :until
                GROUP BY l.variant_id
            ), sales AS (
                SELECT variant_id, greatest(0, sum(quantity))::bigint AS sold_quantity
                FROM movements GROUP BY variant_id
            ), inputs AS (
                SELECT p.id AS product_id, p.name AS product_name, v.id AS variant_id,
                       v.name AS variant_name, v.sku, v.sale_price,
                       coalesce(s.quantity, 0) AS stock_quantity, coalesce(s.avg_unit_cost, 0) AS avg_unit_cost,
                       coalesce(s.quantity, 0)::bigint * coalesce(s.avg_unit_cost, 0) AS stock_value,
                       s.min_quantity, coalesce(s.quantity < s.min_quantity, false) AS below_minimum,
                       coalesce(m.sold_quantity, 0) AS sold_quantity,
                       coalesce(m.sold_quantity, 0)::numeric / :days AS daily_sales
                FROM app.product p
                JOIN app.product_variant v ON v.product_id = p.id AND v.store_id = p.store_id
                LEFT JOIN app.stock s ON s.variant_id = v.id AND s.store_id = v.store_id
                LEFT JOIN sales m ON m.variant_id = v.id
                WHERE p.store_id = :storeId AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
                  AND v.status = 'ACTIVE' AND v.deleted_at IS NULL AND coalesce(s.is_active, true)
            ), recommendations AS (
                SELECT *, stock_quantity / nullif(daily_sales, 0) AS days_of_stock,
                       greatest(0, ceil(sold_quantity::numeric * 7 / :days) - stock_quantity,
                                coalesce(min_quantity, 0) - stock_quantity)::bigint AS suggested_quantity
                FROM inputs
            )
            SELECT *, count(*) OVER () AS match_count
            FROM recommendations WHERE %s
            ORDER BY %s, product_name, sku, variant_id LIMIT :limit
            """;

    private final int days;
    private final String sql;

    InventoryInsights(int days, String filter, String order) {
        this.days = days;
        this.sql = SQL.formatted(filter, order);
    }

    QueryResponse query(NamedParameterJdbcTemplate jdbc, UUID storeId, LocalDate today) {
        LocalDate from = today.minusDays(days - 1L);
        var parameters = Map.of("storeId", storeId, "days", days, "limit", MAX_ROWS,
                "from", from.atStartOfDay(TimeZones.VIETNAM).toOffsetDateTime(),
                "until", today.plusDays(1).atStartOfDay(TimeZones.VIETNAM).toOffsetDateTime());
        var matches = jdbc.query(sql, parameters, (rs, index) -> {
            BigDecimal cover = rs.getBigDecimal("days_of_stock");
            long sold = rs.getLong("sold_quantity");
            String reason = switch (this) {
                case Q7 -> sold == 0 ? "Chưa bán ròng trong 30 ngày" : "Tồn đủ bán hơn 30 ngày";
                case Q8 -> rs.getBoolean("below_minimum") ? "Tồn dưới ngưỡng tối thiểu" : "Tồn đủ bán dưới 7 ngày";
                case Q9 -> sold == 0 ? "Chưa bán ròng và giá bán cao hơn giá vốn"
                        : "Bán chậm và giá bán cao hơn giá vốn";
            };
            var row = new InsightRow(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                    rs.getObject("variant_id", UUID.class), rs.getString("variant_name"), rs.getString("sku"),
                    rs.getInt("stock_quantity"), rs.getLong("avg_unit_cost"), rs.getLong("sale_price"),
                    rs.getLong("stock_value"), sold, rounded(rs.getBigDecimal("daily_sales")), rounded(cover),
                    rs.getObject("min_quantity", Integer.class), this == Q8 ? rs.getLong("suggested_quantity") : null,
                    reason);
            return new Match(row, rs.getLong("match_count"));
        });
        var rows = matches.stream().map(Match::row).toList();
        return new QueryResponse(name(), new DateRange(from, today), Map.of("lookbackDays", days), columns(), rows,
                Map.of("matchCount", matches.isEmpty() ? 0L : matches.getFirst().count(), "shownCount", rows.size()));
    }

    private List<Column> columns() {
        var columns = new ArrayList<>(List.of(new Column("productName", "Sản phẩm", "text"),
                new Column("sku", "SKU", "text"), new Column("variantName", "Phân loại", "text"),
                new Column("stockQuantity", "Tồn kho", "int"), new Column("soldQuantity", "Bán ròng", "int"),
                new Column("dailySales", "Bán trung bình/ngày", "number"),
                new Column("daysOfStock", "Số ngày đủ bán", "number"),
                new Column("avgUnitCost", "Giá vốn bình quân", "money")));
        if (this == Q8) {
            columns.add(new Column("minQuantity", "Ngưỡng tối thiểu", "int"));
            columns.add(new Column("suggestedQuantity", "Số lượng nên nhập", "int"));
        } else {
            if (this == Q9) columns.add(new Column("salePrice", "Giá bán", "money"));
            columns.add(new Column("stockValue", "Giá trị tồn", "money"));
        }
        columns.add(new Column("reason", "Lý do", "text"));
        return columns;
    }

    private static BigDecimal rounded(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private record Match(InsightRow row, long count) {}
    record InsightRow(UUID productId, String productName, UUID variantId, String variantName, String sku,
                      int stockQuantity, long avgUnitCost, long salePrice, long stockValue, long soldQuantity,
                      BigDecimal dailySales, BigDecimal daysOfStock, Integer minQuantity, Long suggestedQuantity,
                      String reason) {}
}
