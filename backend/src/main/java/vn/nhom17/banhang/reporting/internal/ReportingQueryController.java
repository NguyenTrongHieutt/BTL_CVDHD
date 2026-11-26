package vn.nhom17.banhang.reporting.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TimeZones;

@RestController
@RequestMapping("/api/v1/reporting/q")
class ReportingQueryController {
    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_RANKING_DAYS = 30;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    ReportingQueryController(NamedParameterJdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @GetMapping("/{queryId}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    QueryResponse query(
            @PathVariable String queryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String rankBy,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) UUID categoryId) {
        LocalDate today = LocalDate.now(clock.withZone(TimeZones.VIETNAM));
        return switch (queryId) {
            case "Q1" -> revenue(requireRange(fromDate, toDate));
            case "Q2" -> orders(requireRange(fromDate, toDate));
            case "Q3" -> grossProfit(requireRange(fromDate, toDate));
            case "Q4" -> ranking(rankingRange(fromDate, toDate, today), parseDirection(direction),
                    parseRankBy(rankBy), validateLimit(limit), categoryId);
            case "Q5" -> inventory(today, validateInventoryFilters(productName, categoryName));
            default -> throw invalid("queryId", "Chỉ hỗ trợ Q1, Q2, Q3, Q4 và Q5 trong giai đoạn này.");
        };
    }

    private QueryResponse revenue(DateRange range) {
        Map<String, Object> parameters = dateParameters(range);
        parameters.put("from", startOfDay(range.fromDate()));
        parameters.put("until", startOfDay(range.toDate().plusDays(1)));
        parameters.put("storeId", context.storeId());
        List<RevenueDay> rows = jdbc.query("""
                WITH calendar AS (
                    SELECT generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day')::date AS date
                ), events AS (
                    SELECT (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS date,
                           o.total_amount AS gross_sales, 0::bigint AS refund_total,
                           (o.line_discount_total + o.order_discount + o.points_discount)::bigint AS discount_total
                    FROM app.sales_order o
                    WHERE o.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND o.created_at >= :from AND o.created_at < :until
                    UNION ALL
                    SELECT (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           0::bigint, r.refund_amount, 0::bigint
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                )
                SELECT c.date,
                       coalesce(sum(e.gross_sales), 0)::bigint AS gross_sales,
                       coalesce(sum(e.refund_total), 0)::bigint AS refund_total,
                       (coalesce(sum(e.gross_sales), 0) - coalesce(sum(e.refund_total), 0))::bigint AS revenue,
                       coalesce(sum(e.discount_total), 0)::bigint AS discount_total
                FROM calendar c LEFT JOIN events e ON e.date = c.date
                GROUP BY c.date ORDER BY c.date
                """, parameters, (rs, row) -> new RevenueDay(rs.getObject("date", LocalDate.class),
                rs.getLong("gross_sales"), rs.getLong("refund_total"), rs.getLong("revenue"),
                rs.getLong("discount_total")));

        long grossSales = rows.stream().mapToLong(RevenueDay::grossSales).sum();
        long refundTotal = rows.stream().mapToLong(RevenueDay::refundTotal).sum();
        long revenue = rows.stream().mapToLong(RevenueDay::revenue).sum();
        long discountTotal = rows.stream().mapToLong(RevenueDay::discountTotal).sum();
        return new QueryResponse("Q1", range, dateParameters(range),
                List.of(column("date", "Ngày", "date"), column("grossSales", "Doanh thu trước hoàn", "money"),
                        column("refundTotal", "Tiền hoàn", "money"), column("revenue", "Doanh thu thuần", "money"),
                        column("discountTotal", "Giảm giá", "money")),
                rows, Map.of("grossSales", grossSales, "refundTotal", refundTotal,
                        "revenue", revenue, "discountTotal", discountTotal));
    }

    private QueryResponse orders(DateRange range) {
        Map<String, Object> parameters = dateParameters(range);
        parameters.put("from", startOfDay(range.fromDate()));
        parameters.put("until", startOfDay(range.toDate().plusDays(1)));
        parameters.put("storeId", context.storeId());
        List<OrderDay> rows = jdbc.query("""
                WITH calendar AS (
                    SELECT generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day')::date AS date
                ), order_counts AS (
                    SELECT (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS date,
                           count(*) FILTER (WHERE o.status <> 'CANCELLED') AS order_count,
                           count(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled_count,
                           count(*) FILTER (WHERE o.status = 'RETURNED') AS fully_returned_count
                    FROM app.sales_order o
                    WHERE o.store_id = :storeId AND o.created_at >= :from AND o.created_at < :until
                    GROUP BY (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                ), return_counts AS (
                    SELECT (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS date, count(*) AS return_count
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                    GROUP BY (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                )
                SELECT c.date, coalesce(o.order_count, 0) AS order_count,
                       coalesce(o.cancelled_count, 0) AS cancelled_count,
                       coalesce(o.fully_returned_count, 0) AS fully_returned_count,
                       coalesce(r.return_count, 0) AS return_count
                FROM calendar c
                LEFT JOIN order_counts o ON o.date = c.date
                LEFT JOIN return_counts r ON r.date = c.date
                ORDER BY c.date
                """, parameters, (rs, row) -> new OrderDay(rs.getObject("date", LocalDate.class),
                rs.getLong("order_count"), rs.getLong("cancelled_count"), rs.getLong("fully_returned_count"),
                rs.getLong("return_count")));

        long orderCount = rows.stream().mapToLong(OrderDay::orderCount).sum();
        long cancelledCount = rows.stream().mapToLong(OrderDay::cancelledCount).sum();
        long fullyReturnedCount = rows.stream().mapToLong(OrderDay::fullyReturnedCount).sum();
        long returnCount = rows.stream().mapToLong(OrderDay::returnCount).sum();
        return new QueryResponse("Q2", range, dateParameters(range),
                List.of(column("date", "Ngày", "date"), column("orderCount", "Số đơn hàng", "int"),
                        column("cancelledCount", "Đơn đã hủy", "int"),
                        column("fullyReturnedCount", "Đơn đã trả hết", "int"),
                        column("returnCount", "Số phiếu trả", "int")),
                rows, Map.of("orderCount", orderCount, "cancelledCount", cancelledCount,
                        "fullyReturnedCount", fullyReturnedCount, "returnCount", returnCount));
    }

    private QueryResponse grossProfit(DateRange range) {
        Map<String, Object> parameters = dateParameters(range);
        parameters.put("from", startOfDay(range.fromDate()));
        parameters.put("until", startOfDay(range.toDate().plusDays(1)));
        parameters.put("storeId", context.storeId());
        List<GrossProfitDay> rows = jdbc.query("""
                WITH calendar AS (
                    SELECT generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day')::date AS date
                ), sales AS (
                    SELECT o.id, o.store_id, o.created_at, o.total_amount
                    FROM app.sales_order o
                    WHERE o.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND o.created_at >= :from AND o.created_at < :until
                ), returns AS (
                    SELECT r.id, r.store_id, r.order_id, r.created_at, r.refund_amount
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                ), events AS (
                    SELECT (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS date,
                           sum(o.total_amount) AS revenue, 0::bigint AS cost_of_goods_sold
                    FROM sales o
                    GROUP BY (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                    UNION ALL
                    SELECT (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           sum(-r.refund_amount), 0::bigint
                    FROM returns r
                    GROUP BY (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                    UNION ALL
                    SELECT (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           0::bigint, sum(l.quantity::bigint * l.unit_cost)
                    FROM sales o
                    JOIN app.sales_order_line l ON l.order_id = o.id AND l.store_id = o.store_id
                    GROUP BY (o.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                    UNION ALL
                    SELECT (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                           0::bigint, sum(-(rl.quantity::bigint * ol.unit_cost))
                    FROM returns r
                    JOIN app.sales_return_line rl ON rl.return_id = r.id AND rl.store_id = r.store_id
                    JOIN app.sales_order_line ol ON ol.id = rl.order_line_id AND ol.store_id = rl.store_id
                                               AND ol.order_id = r.order_id AND ol.variant_id = rl.variant_id
                    GROUP BY (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
                )
                SELECT c.date,
                       coalesce(sum(e.revenue), 0)::bigint AS revenue,
                       coalesce(sum(e.cost_of_goods_sold), 0)::bigint AS cost_of_goods_sold,
                       (coalesce(sum(e.revenue), 0) - coalesce(sum(e.cost_of_goods_sold), 0))::bigint AS gross_profit
                FROM calendar c LEFT JOIN events e ON e.date = c.date
                GROUP BY c.date ORDER BY c.date
                """, parameters, (rs, row) -> new GrossProfitDay(rs.getObject("date", LocalDate.class),
                rs.getLong("revenue"), rs.getLong("cost_of_goods_sold"), rs.getLong("gross_profit")));

        long revenue = rows.stream().mapToLong(GrossProfitDay::revenue).sum();
        long costOfGoodsSold = rows.stream().mapToLong(GrossProfitDay::costOfGoodsSold).sum();
        long grossProfit = rows.stream().mapToLong(GrossProfitDay::grossProfit).sum();
        return new QueryResponse("Q3", range, dateParameters(range),
                List.of(column("date", "Ngày", "date"), column("revenue", "Doanh thu thuần", "money"),
                        column("costOfGoodsSold", "Giá vốn hàng bán", "money"),
                        column("grossProfit", "Lợi nhuận gộp", "money")),
                rows, Map.of("revenue", revenue, "costOfGoodsSold", costOfGoodsSold, "grossProfit", grossProfit));
    }

    private QueryResponse ranking(DateRange range, Direction direction, RankBy rankBy, int limit, UUID categoryId) {
        Map<String, Object> parameters = dateParameters(range);
        parameters.put("from", startOfDay(range.fromDate()));
        parameters.put("until", startOfDay(range.toDate().plusDays(1)));
        parameters.put("storeId", context.storeId());
        parameters.put("limit", limit);

        boolean bestSelling = direction == Direction.BEST_SELLING;
        String filter = bestSelling
                ? (rankBy == RankBy.QUANTITY ? "coalesce(t.quantity, 0) > 0" : "coalesce(t.revenue, 0) > 0")
                : "p.status = 'ACTIVE' AND p.deleted_at IS NULL AND EXISTS ("
                        + "SELECT 1 FROM app.product_variant active_v WHERE active_v.store_id = :storeId "
                        + "AND active_v.product_id = p.id AND active_v.status = 'ACTIVE' AND active_v.deleted_at IS NULL)";
        if (categoryId != null) {
            parameters.put("categoryId", categoryId);
            filter += " AND c.id = :categoryId";
        }
        String orderBy;
        if (rankBy == RankBy.QUANTITY) {
            orderBy = bestSelling ? "quantity DESC, revenue DESC" : "quantity ASC, revenue ASC";
        } else {
            orderBy = bestSelling ? "revenue DESC, quantity DESC" : "revenue ASC, quantity ASC";
        }

        List<RankedProduct> products = jdbc.query("""
                WITH movements AS (
                    SELECT l.variant_id, l.quantity AS sold_quantity, l.net_line_amount AS sales_amount,
                           0::bigint AS returned_quantity, 0::bigint AS refund_amount
                    FROM app.sales_order o
                    JOIN app.sales_order_line l ON l.order_id = o.id AND l.store_id = o.store_id
                    WHERE o.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND o.created_at >= :from AND o.created_at < :until
                    UNION ALL
                    SELECT l.variant_id, 0::bigint, 0::bigint, l.quantity, l.refund_amount
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.id = r.order_id AND o.store_id = r.store_id
                    JOIN app.sales_return_line l ON l.return_id = r.id AND l.store_id = r.store_id
                    WHERE r.store_id = :storeId AND o.status <> 'CANCELLED'
                      AND r.created_at >= :from AND r.created_at < :until
                ), variant_totals AS (
                    SELECT variant_id,
                           sum(sold_quantity) AS sold_quantity,
                           sum(sales_amount) AS sales_amount,
                           sum(returned_quantity) AS returned_quantity,
                           sum(refund_amount) AS refund_amount
                    FROM movements
                    GROUP BY variant_id
                ), totals AS (
                    SELECT v.product_id,
                           (sum(m.sold_quantity) - sum(m.returned_quantity))::bigint AS quantity,
                           (sum(m.sales_amount) - sum(m.refund_amount))::bigint AS revenue,
                           sum(m.sold_quantity)::bigint AS sold_quantity,
                           sum(m.returned_quantity)::bigint AS returned_quantity
                    FROM variant_totals m
                    JOIN app.product_variant v ON v.id = m.variant_id AND v.store_id = :storeId
                    GROUP BY v.product_id
                )
                SELECT p.id AS product_id, p.name AS product_name, c.name AS category_name,
                       coalesce(t.quantity, 0)::bigint AS quantity, coalesce(t.revenue, 0)::bigint AS revenue,
                       coalesce(t.sold_quantity, 0)::bigint AS sold_quantity,
                       coalesce(t.returned_quantity, 0)::bigint AS returned_quantity
                FROM app.product p
                LEFT JOIN app.category c ON c.id = p.category_id AND c.store_id = p.store_id
                LEFT JOIN totals t ON t.product_id = p.id
                WHERE p.store_id = :storeId AND %s
                ORDER BY %s, p.name, p.id
                LIMIT :limit
                """.formatted(filter, orderBy), parameters,
                (rs, row) -> new RankedProduct(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                        rs.getString("category_name"), rs.getLong("quantity"), rs.getLong("revenue"),
                        rs.getLong("sold_quantity"), rs.getLong("returned_quantity")));
        List<RankedProduct> rows = new ArrayList<>(products.size());
        for (int index = 0; index < products.size(); index++) {
            RankedProduct product = products.get(index);
            rows.add(product.withRank(index + 1));
        }
        Map<String, Object> parametersOut = new LinkedHashMap<>(dateParameters(range));
        parametersOut.put("direction", direction.name());
        parametersOut.put("rankBy", rankBy.name());
        parametersOut.put("limit", limit);
        if (categoryId != null) parametersOut.put("categoryId", categoryId);
        return new QueryResponse("Q4", range, parametersOut,
                List.of(column("rank", "Hạng", "int"), column("productName", "Sản phẩm", "text"),
                        column("categoryName", "Danh mục", "text"), column("quantity", "Số lượng bán ròng", "int"),
                        column("revenue", "Doanh thu thuần", "money"),
                        column("soldQuantity", "Số lượng bán", "int"),
                        column("returnedQuantity", "Số lượng trả", "int")),
                rows, Map.of("productCount", rows.size()));
    }

    private QueryResponse inventory(LocalDate today, InventoryFilters filters) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("storeId", context.storeId());
        parameters.put("productName", filters.productName());
        parameters.put("categoryName", filters.categoryName());
        List<StockRow> rows = jdbc.query("""
                SELECT p.id AS product_id, p.name AS product_name, c.name AS category_name,
                       v.id AS variant_id, v.name AS variant_name, v.sku,
                       coalesce(s.quantity, 0) AS stock_quantity,
                       coalesce(s.avg_unit_cost, 0) AS avg_unit_cost,
                       (coalesce(s.quantity, 0)::bigint * coalesce(s.avg_unit_cost, 0))::bigint AS stock_value
                FROM app.product p
                JOIN app.product_variant v ON v.product_id = p.id AND v.store_id = p.store_id
                LEFT JOIN app.category c ON c.id = p.category_id AND c.store_id = p.store_id
                LEFT JOIN app.stock s ON s.variant_id = v.id AND s.store_id = v.store_id
                WHERE p.store_id = :storeId AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
                  AND v.status = 'ACTIVE' AND v.deleted_at IS NULL AND coalesce(s.is_active, true)
                  AND (CAST(:productName AS text) IS NULL
                       OR p.name ILIKE ('%' || CAST(:productName AS text) || '%'))
                  AND (CAST(:categoryName AS text) IS NULL
                       OR c.name ILIKE ('%' || CAST(:categoryName AS text) || '%'))
                ORDER BY p.name, v.sku, v.id
                """, parameters, (rs, row) -> new StockRow(rs.getObject("product_id", UUID.class),
                rs.getString("product_name"), rs.getString("category_name"),
                rs.getObject("variant_id", UUID.class), rs.getString("variant_name"), rs.getString("sku"),
                rs.getInt("stock_quantity"), rs.getLong("avg_unit_cost"), rs.getLong("stock_value")));

        long stockQuantity = rows.stream().mapToLong(StockRow::stockQuantity).sum();
        long stockValue = rows.stream().mapToLong(StockRow::stockValue).sum();
        Map<String, CategoryStock> categories = new TreeMap<>();
        Map<UUID, ProductStockBuilder> products = new LinkedHashMap<>();
        for (StockRow row : rows) {
            String category = row.categoryName() == null ? "Không phân loại" : row.categoryName();
            CategoryStock categoryTotal = categories.computeIfAbsent(category, name -> new CategoryStock(name, 0, 0));
            categories.put(category, categoryTotal.add(row.stockQuantity(), row.stockValue()));

            ProductStockBuilder product = products.computeIfAbsent(row.productId(),
                    id -> new ProductStockBuilder(id, row.productName(), category));
            product.add(row);
        }
        List<ProductStock> byProduct = products.values().stream().map(ProductStockBuilder::build).toList();
        StockSummary summary = new StockSummary(stockQuantity, stockValue, rows.size(),
                List.copyOf(categories.values()), byProduct);
        return new QueryResponse("Q5", new DateRange(today, today), inventoryParameters(filters),
                List.of(column("productName", "Sản phẩm", "text"), column("categoryName", "Danh mục", "text"),
                        column("sku", "SKU", "text"), column("variantName", "Phân loại", "text"),
                        column("stockQuantity", "Số lượng tồn", "int"),
                        column("avgUnitCost", "Giá vốn bình quân", "money"),
                        column("stockValue", "Giá trị tồn", "money")),
                rows, summary);
    }

    private static Map<String, Object> dateParameters(DateRange range) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fromDate", range.fromDate().toString());
        parameters.put("toDate", range.toDate().toString());
        return parameters;
    }

    private static Map<String, Object> inventoryParameters(InventoryFilters filters) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (filters.productName() != null) parameters.put("productName", filters.productName());
        if (filters.categoryName() != null) parameters.put("categoryName", filters.categoryName());
        return parameters;
    }

    private static DateRange requireRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null) {
            throw invalid("fromDate", "Cần truyền cả fromDate và toDate.");
        }
        return validateRange(fromDate, toDate);
    }

    private static DateRange rankingRange(LocalDate fromDate, LocalDate toDate, LocalDate today) {
        if (fromDate == null && toDate == null) {
            return new DateRange(today.minusDays(DEFAULT_RANKING_DAYS - 1), today);
        }
        return requireRange(fromDate, toDate);
    }

    private static DateRange validateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw invalid("fromDate", "fromDate phải trước hoặc bằng toDate.");
        }
        long days = toDate.toEpochDay() - fromDate.toEpochDay() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw invalid("toDate", "Khoảng ngày tối đa là 366 ngày.");
        }
        return new DateRange(fromDate, toDate);
    }

    private static Direction parseDirection(String value) {
        try {
            return Direction.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalid("direction", "direction phải là BEST_SELLING hoặc SLOW_SELLING.");
        }
    }

    private static RankBy parseRankBy(String value) {
        if (value == null) return RankBy.QUANTITY;
        try {
            return RankBy.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw invalid("rankBy", "rankBy phải là QUANTITY hoặc REVENUE.");
        }
    }

    private static int validateLimit(Integer value) {
        int limit = value == null ? DEFAULT_LIMIT : value;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit", "limit phải nằm trong khoảng 1 đến 50.");
        }
        return limit;
    }

    private static InventoryFilters validateInventoryFilters(String productName, String categoryName) {
        if (productName != null && categoryName != null) {
            throw invalid("productName", "Chỉ được lọc theo productName hoặc categoryName.");
        }
        String product = cleanFilter(productName, "productName");
        String category = cleanFilter(categoryName, "categoryName");
        return new InventoryFilters(product, category);
    }

    private static String cleanFilter(String value, String field) {
        if (value == null) return null;
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.length() > 100) {
            throw invalid(field, "Bộ lọc phải có từ 1 đến 100 ký tự.");
        }
        return cleaned;
    }

    private static ProblemException invalid(String field, String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                message, Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TimeZones.VIETNAM).toOffsetDateTime();
    }

    private static Column column(String key, String label, String type) {
        return new Column(key, label, type);
    }

    record QueryResponse(String queryId, DateRange range, Map<String, Object> params,
                         List<Column> columns, List<?> rows, Object summary) {}
    record DateRange(LocalDate fromDate, LocalDate toDate) {}
    record Column(String key, String label, String type) {}
    record RevenueDay(LocalDate date, long grossSales, long refundTotal, long revenue, long discountTotal) {}
    record OrderDay(LocalDate date, long orderCount, long cancelledCount, long fullyReturnedCount, long returnCount) {}
    record GrossProfitDay(LocalDate date, long revenue, long costOfGoodsSold, long grossProfit) {}
    record RankedProduct(int rank, UUID productId, String productName, String categoryName, long quantity,
                         long revenue, long soldQuantity, long returnedQuantity) {
        RankedProduct(UUID productId, String productName, String categoryName, long quantity, long revenue,
                      long soldQuantity, long returnedQuantity) {
            this(0, productId, productName, categoryName, quantity, revenue, soldQuantity, returnedQuantity);
        }
        RankedProduct withRank(int value) {
            return new RankedProduct(value, productId, productName, categoryName, quantity,
                    revenue, soldQuantity, returnedQuantity);
        }
    }
    record StockRow(UUID productId, String productName, String categoryName, UUID variantId, String variantName,
                    String sku, int stockQuantity, long avgUnitCost, long stockValue) {}
    record InventoryFilters(String productName, String categoryName) {}
    record StockSummary(long stockQuantity, long stockValue, int variantCount,
                        List<CategoryStock> byCategory, List<ProductStock> byProduct) {}
    record CategoryStock(String categoryName, long stockQuantity, long stockValue) {
        CategoryStock add(long quantity, long value) {
            return new CategoryStock(categoryName, stockQuantity + quantity, stockValue + value);
        }
    }
    record ProductStock(UUID productId, String productName, String categoryName, long stockQuantity, long stockValue,
                        List<VariantStock> variants) {}
    record VariantStock(UUID variantId, String sku, String variantName, int stockQuantity,
                        long avgUnitCost, long stockValue) {}

    private static final class ProductStockBuilder {
        private final UUID productId;
        private final String productName;
        private final String categoryName;
        private final List<VariantStock> variants = new ArrayList<>();
        private long stockQuantity;
        private long stockValue;

        private ProductStockBuilder(UUID productId, String productName, String categoryName) {
            this.productId = productId;
            this.productName = productName;
            this.categoryName = categoryName;
        }

        private void add(StockRow row) {
            variants.add(new VariantStock(row.variantId(), row.sku(), row.variantName(),
                    row.stockQuantity(), row.avgUnitCost(), row.stockValue()));
            stockQuantity += row.stockQuantity();
            stockValue += row.stockValue();
        }

        private ProductStock build() {
            variants.sort(Comparator.comparing(VariantStock::sku));
            return new ProductStock(productId, productName, categoryName, stockQuantity, stockValue,
                    List.copyOf(variants));
        }
    }

    private enum Direction { BEST_SELLING, SLOW_SELLING }
    private enum RankBy { QUANTITY, REVENUE }
}
