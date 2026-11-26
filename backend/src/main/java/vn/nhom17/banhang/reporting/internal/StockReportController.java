package vn.nhom17.banhang.reporting.internal;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TimeZones;

/** Báo cáo tồn kho có phân trang; hợp đồng Q5 dành cho AI tiếp tục được giữ riêng. */
@RestController
@RequestMapping("/api/v1/reporting/stock")
class StockReportController {
    private static final String SELECT_ITEMS = """
            SELECT p.id AS product_id, p.name AS product_name, c.name AS category_name,
                   v.id AS variant_id, v.name AS variant_name, v.sku,
                   coalesce(s.quantity, 0) AS stock_quantity, s.min_quantity,
                   coalesce(s.avg_unit_cost, 0) AS avg_unit_cost,
                   (coalesce(s.quantity, 0)::bigint * coalesce(s.avg_unit_cost, 0))::bigint AS stock_value,
                   (p.status = 'ACTIVE' AND v.status = 'ACTIVE' AND coalesce(s.is_active, true)) AS is_active
            """;
    private static final String ORDER_BY = " ORDER BY lower(app.f_unaccent(p.name)), p.name, v.sku, v.id";
    private static final RowMapper<StockItem> ITEM_MAPPER = (rs, row) -> new StockItem(
            rs.getObject("product_id", UUID.class), rs.getString("product_name"), rs.getString("category_name"),
            rs.getObject("variant_id", UUID.class), rs.getString("variant_name"), rs.getString("sku"),
            rs.getInt("stock_quantity"), rs.getObject("min_quantity", Integer.class), rs.getLong("avg_unit_cost"),
            rs.getLong("stock_value"), rs.getBoolean("is_active"));

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    StockReportController(NamedParameterJdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    StockPage report(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        OffsetDateTime asOf = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        StockSql sql = filtered(q, categoryId, stockStatus, includeInactive);
        StockSummary summary = summary(sql);
        int pageSize = PageResponse.clampSize(size);
        long lastPage = summary.variantCount() == 0 ? 0 : (summary.variantCount() - 1) / pageSize;
        int effectivePage = (int) Math.min(Math.max(0, page), lastPage);
        sql.parameters().put("limit", pageSize);
        sql.parameters().put("offset", (long) effectivePage * pageSize);
        List<StockItem> items = jdbc.query(SELECT_ITEMS + sql.from() + ORDER_BY + " LIMIT :limit OFFSET :offset",
                sql.parameters(), ITEM_MAPPER);
        return new StockPage(items, summary.variantCount(), effectivePage, pageSize, asOf, summary);
    }

    @GetMapping("/export")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        StockSql sql = filtered(q, categoryId, stockStatus, includeInactive);
        StockSummary summary = summary(sql);
        StringBuilder csv = new StringBuilder("\ufeff");
        appendCsvRow(csv, "Sản phẩm", "Danh mục", "Phân loại", "SKU", "Số lượng tồn", "Giá vốn bình quân", "Giá trị tồn");
        // Không áp LIMIT: file gồm mọi dòng khớp bộ lọc, độc lập trang đang xem.
        jdbc.query(SELECT_ITEMS + sql.from() + ORDER_BY, sql.parameters(), rs -> {
            appendCsvRow(csv, rs.getString("product_name"), rs.getString("category_name"),
                    rs.getString("variant_name") == null ? "Mặc định" : rs.getString("variant_name"),
                    rs.getString("sku"), rs.getInt("stock_quantity"), rs.getLong("avg_unit_cost"),
                    rs.getLong("stock_value"));
        });
        appendCsvRow(csv, "Tổng", null, null, null, summary.stockQuantity(), null, summary.stockValue());
        String filename = "bao-cao-ton-kho-" + LocalDate.now(clock.withZone(TimeZones.VIETNAM)) + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private StockSummary summary(StockSql sql) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(coalesce(s.quantity, 0)), 0)::bigint AS stock_quantity,
                       coalesce(sum(coalesce(s.quantity, 0)::bigint * coalesce(s.avg_unit_cost, 0)), 0)::bigint AS stock_value,
                       count(*) AS variant_count
                """ + sql.from(), sql.parameters(), (rs, row) -> new StockSummary(
                rs.getLong("stock_quantity"), rs.getLong("stock_value"), rs.getLong("variant_count")));
    }

    private StockSql filtered(String q, UUID categoryId, String rawStatus, boolean includeInactive) {
        StockStatus stockStatus = stockStatus(rawStatus);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("storeId", context.storeId());
        StringBuilder from = new StringBuilder("""
                FROM app.product p
                JOIN app.product_variant v ON v.product_id = p.id AND v.store_id = p.store_id
                LEFT JOIN app.category c ON c.id = p.category_id AND c.store_id = p.store_id
                LEFT JOIN app.stock s ON s.variant_id = v.id AND s.store_id = v.store_id
                WHERE p.store_id = :storeId AND p.deleted_at IS NULL AND v.deleted_at IS NULL
                """);
        if (!includeInactive) {
            from.append(" AND p.status = 'ACTIVE' AND v.status = 'ACTIVE' AND coalesce(s.is_active, true)");
        }
        if (categoryId != null) {
            from.append(" AND c.id = :categoryId");
            parameters.put("categoryId", categoryId);
        }
        String query = normalizeQuery(q);
        if (query != null) {
            parameters.put("q", query);
            // strpos giữ nguyên ý nghĩa ký tự %, _ và \\ do người dùng gõ.
            from.append(" AND (").append(contains("p.name")).append(" OR ").append(contains("v.name"))
                    .append(" OR ").append(contains("v.sku")).append(" OR ").append(contains("v.barcode")).append(")");
        }
        if (stockStatus != null) {
            from.append(switch (stockStatus) {
                case OUT_OF_STOCK -> " AND coalesce(s.quantity, 0) = 0";
                case BELOW_MIN -> " AND s.quantity > 0 AND s.quantity < s.min_quantity";
                case IN_STOCK -> " AND s.quantity > 0 AND s.quantity >= s.min_quantity";
            });
        }
        return new StockSql(from.toString(), parameters);
    }

    private static String contains(String column) {
        return "strpos(" + folded(column) + ", " + folded("CAST(:q AS text)") + ") > 0";
    }

    private static String folded(String expression) {
        return "regexp_replace(lower(app.f_unaccent(" + expression + ")), '\\s+', ' ', 'g')";
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) return null;
        StringBuilder text = new StringBuilder();
        Normalizer.normalize(raw, Normalizer.Form.NFC).codePoints()
                .filter(c -> Character.getType(c) != Character.FORMAT)
                .map(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) ? ' ' : c)
                .filter(c -> !Character.isISOControl(c))
                .forEach(text::appendCodePoint);
        String query = text.toString().replaceAll(" +", " ").strip();
        return query.isEmpty() ? null : query;
    }

    private static StockStatus stockStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return StockStatus.valueOf(raw.strip());
        } catch (IllegalArgumentException invalid) {
            String message = "Trạng thái tồn kho chỉ nhận OUT_OF_STOCK, BELOW_MIN hoặc IN_STOCK.";
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                    message, Map.of("errors", List.of(Map.of("field", "stockStatus", "message", message))));
        }
    }

    private static void appendCsvRow(StringBuilder csv, Object... cells) {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) csv.append(',');
            Object value = cells[index];
            if (value instanceof Number) {
                csv.append(value);
            } else if (value != null) {
                String text = value.toString();
                if (formulaPrefix(text)) text = "'" + text;
                csv.append('"').append(text.replace("\"", "\"\"")).append('"');
            }
        }
        csv.append("\r\n");
    }

    private static boolean formulaPrefix(String text) {
        if (text.isEmpty()) return false;
        char first = text.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') return true;
        for (int index = 0; index < text.length();) {
            int codepoint = text.codePointAt(index);
            if (!Character.isWhitespace(codepoint) && !Character.isSpaceChar(codepoint) && codepoint != '\ufeff') {
                return codepoint == '=' || codepoint == '+' || codepoint == '-' || codepoint == '@';
            }
            index += Character.charCount(codepoint);
        }
        return false;
    }

    private enum StockStatus { OUT_OF_STOCK, BELOW_MIN, IN_STOCK }
    private record StockSql(String from, Map<String, Object> parameters) {}
    record StockPage(List<StockItem> items, long total, int page, int size, OffsetDateTime asOf, StockSummary summary) {}
    record StockSummary(long stockQuantity, long stockValue, long variantCount) {}
    record StockItem(UUID productId, String productName, String categoryName, UUID variantId, String variantName,
                     String sku, int stockQuantity, Integer minQuantity, long avgUnitCost, long stockValue, boolean isActive) {}
}
