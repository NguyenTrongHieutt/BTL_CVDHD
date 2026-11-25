package vn.nhom17.banhang.inventory.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.inventory.StockService.StockLine;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/inventory/stocktakes")
class StocktakeController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final StockService stock;
    private final AuditLog audit;
    private final Clock clock;

    StocktakeController(JdbcTemplate jdbc, CurrentContext context, IdempotencyService idempotency,
                        StockService stock, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.idempotency = idempotency;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<Stocktake> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                     @RequestBody CreateRequest request) {
        var input = validate(request);
        String hash = idempotency.hash(Map.of("actorId", context.userId(), "request", input));
        var result = idempotency.execute(StockDocumentType.STOCKTAKE.name(), key, hash,
                () -> save(input), this::load);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }

    private UUID save(ValidatedRequest request) {
        UUID storeId = context.storeId();
        Map<UUID, Integer> quantities = new HashMap<>();
        // Dùng cùng thứ tự khóa biến thể với phiếu nhập và bán hàng.
        for (var line : request.lines().stream().sorted(Comparator.comparing(Count::variantId)).toList()) {
            quantities.put(line.variantId(), lockQuantity(storeId, line.variantId()));
        }

        Map<UUID, Integer> changedQuantities = new HashMap<>();
        for (var line : request.lines()) {
            int current = quantities.get(line.variantId());
            if (line.systemQuantity() != null && line.systemQuantity() != current) {
                changedQuantities.put(line.variantId(), current);
            }
        }
        if (!changedQuantities.isEmpty()) {
            throw new ProblemException(HttpStatus.CONFLICT, "/errors/inventory/stocktake-stale",
                    "Tồn hệ thống đã thay đổi", "Đối chiếu lại số đếm và lý do trước khi lưu.",
                    Map.of("currentQuantities", changedQuantities));
        }

        var lines = new ArrayList<StocktakeLine>();
        for (var line : request.lines()) {
            int current = quantities.get(line.variantId());
            int delta = line.countedQuantity() - current;
            if (delta != 0 && line.reason() == null) {
                throw invalid("Nhập lý do chênh lệch ở dòng " + line.lineNo() + ".");
            }
            lines.add(new StocktakeLine(line.lineNo(), line.variantId(), current, line.countedQuantity(),
                    delta, delta == 0 ? null : line.reason()));
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.stocktake (id, store_id, note, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, storeId, request.note(), context.userId(), clock.instant().atOffset(ZoneOffset.UTC));

        var changes = lines.stream().map(line -> new StockLine(line.lineNo(), line.variantId(), line.delta(), null))
                .sorted(Comparator.comparing(StockLine::variantId)).toList();
        stock.apply(StockDocumentType.STOCKTAKE, id, changes);
        jdbc.batchUpdate("""
                INSERT INTO app.stocktake_line
                    (store_id, stocktake_id, line_no, variant_id, system_quantity, counted_quantity, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, lines, lines.size(), (statement, line) -> {
            statement.setObject(1, storeId);
            statement.setObject(2, id);
            statement.setInt(3, line.lineNo());
            statement.setObject(4, line.variantId());
            statement.setInt(5, line.systemQuantity());
            statement.setInt(6, line.countedQuantity());
            statement.setString(7, line.reason());
        });

        long adjusted = lines.stream().filter(line -> line.delta() != 0).count();
        audit.record(AuditAction.INVENTORY_STOCKTAKE_COMPLETE, id.toString(), null,
                Map.of("lineCount", lines.size(), "adjustedCount", adjusted));
        return id;
    }

    private int lockQuantity(UUID storeId, UUID variantId) {
        try {
            return jdbc.queryForObject("""
                    SELECT quantity FROM app.stock WHERE store_id = ? AND variant_id = ? FOR UPDATE
                    """, Integer.class, storeId, variantId);
        } catch (EmptyResultDataAccessException ex) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy tồn kho",
                    "Có biến thể không thuộc tồn kho của cửa hàng hiện tại.");
        }
    }

    private Stocktake load(UUID id) {
        UUID storeId = context.storeId();
        var header = jdbc.queryForObject("""
                SELECT note, created_at FROM app.stocktake WHERE store_id = ? AND id = ?
                """, (rs, row) -> new Header(rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class)),
                storeId, id);
        var lines = jdbc.query("""
                SELECT line_no, variant_id, system_quantity, counted_quantity, counted_quantity - system_quantity AS delta, reason
                FROM app.stocktake_line WHERE store_id = ? AND stocktake_id = ? ORDER BY line_no
                """, (rs, row) -> new StocktakeLine(rs.getInt("line_no"), rs.getObject("variant_id", UUID.class),
                rs.getInt("system_quantity"), rs.getInt("counted_quantity"), rs.getInt("delta"), rs.getString("reason")),
                storeId, id);
        return new Stocktake(id, header.note(), header.createdAt(), lines);
    }

    private static ValidatedRequest validate(CreateRequest request) {
        if (request.lines() == null || request.lines().isEmpty()) {
            throw invalid("Thêm ít nhất một biến thể vào phiếu.");
        }
        var seen = new HashSet<UUID>();
        var counts = new ArrayList<Count>();
        for (var line : request.lines()) {
            int lineNo = counts.size() + 1;
            if (line == null || line.variantId() == null) {
                throw invalid("Chọn biến thể cho dòng " + lineNo + ".");
            }
            if (!seen.add(line.variantId())) {
                throw invalid("Mỗi biến thể chỉ được xuất hiện một lần trong phiếu.");
            }
            int counted = quantity(line.countedQuantity(), "Số đếm thực tế", lineNo);
            Integer system = line.systemQuantity() == null ? null : quantity(line.systemQuantity(), "Tồn hệ thống", lineNo);
            counts.add(new Count(lineNo, line.variantId(), counted, system, text(line.reason(), "Lý do dòng " + lineNo)));
        }
        return new ValidatedRequest(text(request.note(), "Ghi chú"), List.copyOf(counts));
    }

    private static int quantity(BigDecimal value, String label, int lineNo) {
        try {
            if (value != null && value.signum() >= 0) return value.intValueExact();
        } catch (ArithmeticException ignored) {
            // Có phần lẻ hoặc vượt giới hạn int.
        }
        throw invalid(label + " ở dòng " + lineNo + " phải là số nguyên không âm trong phạm vi hỗ trợ.");
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > 200 || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(label + " phải nằm trên một dòng và tối đa 200 ký tự.");
        }
        return trimmed;
    }

    private static ProblemException invalid(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Phiếu kiểm kho không hợp lệ", detail);
    }

    record CreateRequest(String note, List<LineRequest> lines) {}
    record LineRequest(UUID variantId, BigDecimal countedQuantity, BigDecimal systemQuantity, String reason) {}
    record Stocktake(UUID stocktakeId, String note, OffsetDateTime createdAt, List<StocktakeLine> lines) {}
    record StocktakeLine(int lineNo, UUID variantId, int systemQuantity, int countedQuantity, int delta, String reason) {}
    private record Header(String note, OffsetDateTime createdAt) {}
    private record Count(int lineNo, UUID variantId, int countedQuantity, Integer systemQuantity, String reason) {}
    private record ValidatedRequest(String note, List<Count> lines) {}
}
