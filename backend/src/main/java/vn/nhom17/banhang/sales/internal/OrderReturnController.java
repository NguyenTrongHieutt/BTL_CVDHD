package vn.nhom17.banhang.sales.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/sales/orders/{id}/returns")
public class OrderReturnController {
    private static final String COMPLETED = "COMPLETED";
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final StockService stock;
    private final AuditLog audit;
    private final Clock clock;

    public OrderReturnController(JdbcTemplate jdbc, CurrentContext context, IdempotencyService idempotency,
                                 StockService stock, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.idempotency = idempotency;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReturnDetails details(@PathVariable UUID id) {
        UUID storeId = context.storeId();
        var order = findOrder(storeId, id, false);
        var lines = findLines(storeId, id).stream().map(line -> new ReturnableLine(
                line.lineNo(), line.productName(), line.variantName(), line.quantity(), line.returnedQuantity(),
                COMPLETED.equals(order.status()) ? line.remaining() : 0)).toList();
        var receipts = jdbc.query("""
                SELECT id, refund_amount, refund_method, reason, created_at
                FROM app.sales_return WHERE store_id = ? AND order_id = ?
                ORDER BY created_at DESC, id DESC
                """, (rs, row) -> new Receipt(rs.getObject("id", UUID.class), rs.getLong("refund_amount"),
                rs.getString("refund_method"), rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class)), storeId, id);
        return new ReturnDetails(order.code(), order.status(), lines, receipts);
    }

    @PostMapping("/quote")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RefundQuote quote(@PathVariable UUID id, @RequestBody QuoteRequest request) {
        UUID storeId = context.storeId();
        var selected = quantities(request.lines());
        requireCompleted(findOrder(storeId, id, false));
        var plan = plan(findLines(storeId, id), selected);
        return new RefundQuote(plan.total(), plan.lines().stream()
                .map(line -> new RefundLine(line.orderLine().lineNo(), line.quantity(), line.refund())).toList());
    }

    @PostMapping
    public ResponseEntity<Receipt> create(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestBody CreateRequest request) {
        var selected = quantities(request.lines());
        String method = request.refundMethod();
        if (!"CASH".equals(method) && !"BANK_TRANSFER".equals(method)) {
            throw invalid("Chọn phương thức hoàn tiền mặt hoặc chuyển khoản.");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.codePointCount(0, reason.length()) > 200 || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Lý do trả hàng phải trên một dòng và tối đa 200 ký tự.");
        }
        if (request.quotedRefundTotal() == null || request.quotedRefundTotal().signum() < 0) {
            throw invalid("Cần xác nhận số tiền hoàn đã tính.");
        }

        var refundTotal = request.quotedRefundTotal().stripTrailingZeros();
        String hash = idempotency.hash(Map.of("orderId", id, "actorId", context.userId(), "lines", selected,
                "method", method, "reason", reason, "total", refundTotal));
        var result = idempotency.execute(StockDocumentType.SALE_RETURN.name(), key, hash,
                () -> save(id, selected, method, reason, refundTotal), this::loadReceipt);
        var response = ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.value());
    }

    private UUID save(UUID orderId, Map<Integer, Integer> selected, String method, String reason,
                      BigDecimal quotedRefundTotal) {
        UUID storeId = context.storeId();
        // Cùng khóa với hủy đơn; số đã trả chỉ đọc sau khi giữ khóa đơn.
        requireCompleted(findOrder(storeId, orderId, true));
        var plan = plan(findLines(storeId, orderId), selected);
        if (quotedRefundTotal.compareTo(BigDecimal.valueOf(plan.total())) != 0) {
            throw stale("Tiền hoàn đã thay đổi. Kiểm tra lại số tiền trước khi xác nhận.");
        }

        UUID returnId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_return
                    (id, store_id, order_id, refund_amount, refund_method, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, returnId, storeId, orderId, plan.total(), method, reason.isEmpty() ? null : reason,
                context.userId(), clock.instant().atOffset(ZoneOffset.UTC));
        for (var line : plan.lines()) {
            var original = line.orderLine();
            jdbc.update("""
                    INSERT INTO app.sales_return_line
                        (store_id, return_id, line_no, order_line_id, variant_id, quantity, refund_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, storeId, returnId, original.lineNo(), original.id(), original.variantId(),
                    line.quantity(), line.refund());
        }
        var changes = plan.lines().stream().map(line -> new StockService.StockLine(
                line.orderLine().lineNo(), line.orderLine().variantId(), line.quantity(), null))
                .sorted(Comparator.comparing(StockService.StockLine::variantId)).toList();
        stock.apply(StockDocumentType.SALE_RETURN, returnId, changes);
        if (plan.fullyReturned()) {
            jdbc.update("UPDATE app.sales_order SET status = 'RETURNED' WHERE store_id = ? AND id = ?",
                    storeId, orderId);
        }
        audit.record(AuditAction.SALES_RETURN_CREATE, returnId.toString(), null,
                Map.of("orderId", orderId, "refundAmount", plan.total(), "refundMethod", method,
                        "status", plan.fullyReturned() ? "RETURNED" : COMPLETED, "reason", reason));
        return returnId;
    }

    private Order findOrder(UUID storeId, UUID id, boolean lock) {
        try {
            return jdbc.queryForObject("""
                    SELECT order_code, status FROM app.sales_order WHERE store_id = ? AND id = ?
                    """ + (lock ? " FOR UPDATE" : ""),
                    (rs, row) -> new Order(rs.getString("order_code"), rs.getString("status")), storeId, id);
        } catch (EmptyResultDataAccessException ex) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy đơn hàng",
                    "Đơn hàng không tồn tại trong cửa hàng hiện tại.");
        }
    }

    private List<OrderLine> findLines(UUID storeId, UUID id) {
        return jdbc.query("""
                SELECT ol.id, ol.variant_id, ol.line_no, ol.product_name, ol.variant_name, ol.quantity, ol.net_line_amount,
                       coalesce(sum(rl.quantity), 0) AS returned_quantity,
                       coalesce(sum(rl.refund_amount), 0) AS refunded
                FROM app.sales_order_line ol
                LEFT JOIN app.sales_return_line rl ON rl.store_id = ol.store_id AND rl.order_line_id = ol.id
                WHERE ol.store_id = ? AND ol.order_id = ?
                GROUP BY ol.id
                ORDER BY ol.line_no
                """, (rs, row) -> new OrderLine(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                rs.getInt("line_no"), rs.getString("product_name"), rs.getString("variant_name"),
                rs.getInt("quantity"), rs.getLong("net_line_amount"), rs.getInt("returned_quantity"),
                rs.getLong("refunded")), storeId, id);
    }

    private Receipt loadReceipt(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, refund_amount, refund_method, reason, created_at
                FROM app.sales_return WHERE store_id = ? AND id = ?
                """, (rs, row) -> new Receipt(rs.getObject("id", UUID.class), rs.getLong("refund_amount"),
                rs.getString("refund_method"), rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class)), context.storeId(), id);
    }

    private static ReturnPlan plan(List<OrderLine> orderLines, Map<Integer, Integer> selected) {
        var lines = new ArrayList<PlannedLine>();
        boolean fullyReturned = true;
        long total = 0;
        for (var line : orderLines) {
            int quantity = selected.getOrDefault(line.lineNo(), 0);
            if (quantity > line.remaining()) {
                throw stale("Dòng " + line.lineNo() + " chỉ còn " + line.remaining() + " sản phẩm có thể trả.");
            }
            fullyReturned &= quantity == line.remaining();
            if (quantity > 0) {
                long refund = refundAmount(line.net(), line.quantity(), line.returnedQuantity(), line.refunded(), quantity);
                lines.add(new PlannedLine(line, quantity, refund));
                total = Math.addExact(total, refund);
            }
        }
        if (lines.size() != selected.size()) {
            throw invalid("Có dòng hàng không thuộc đơn đã chọn.");
        }
        return new ReturnPlan(lines, total, fullyReturned);
    }

    static long refundAmount(long net, int sold, int alreadyReturned, long alreadyRefunded, int quantity) {
        if (quantity == sold - alreadyReturned) {
            return net - alreadyRefunded;
        }
        return BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(sold), 0, RoundingMode.DOWN).longValueExact();
    }

    private static Map<Integer, Integer> quantities(List<Selection> lines) {
        if (lines == null || lines.isEmpty()) {
            throw invalid("Chọn ít nhất một dòng hàng để trả.");
        }
        Map<Integer, Integer> selected = new TreeMap<>();
        for (var line : lines) {
            if (line == null) throw invalid("Dòng trả hàng không hợp lệ.");
            int number = positiveInteger(line.lineNo());
            int quantity = positiveInteger(line.quantity());
            if (selected.putIfAbsent(number, quantity) != null) {
                throw invalid("Mỗi dòng đơn chỉ được chọn một lần trong một phiếu trả.");
            }
        }
        return selected;
    }

    private static int positiveInteger(BigDecimal value) {
        try {
            if (value != null && value.signum() > 0) return value.intValueExact();
        } catch (ArithmeticException ignored) {
            // Giá trị có phần lẻ hoặc vượt giới hạn int.
        }
        throw invalid("Số thứ tự dòng và số lượng trả phải là số nguyên dương.");
    }

    private static void requireCompleted(Order order) {
        if (!COMPLETED.equals(order.status())) {
            throw ProblemException.conflict("/errors/sales/invalid-state", "Không thể trả hàng",
                    "Chỉ đơn Hoàn thành còn hàng chưa trả mới lập được phiếu trả.");
        }
    }

    private static ProblemException invalid(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Phiếu trả hàng không hợp lệ", detail);
    }

    private static ProblemException stale(String detail) {
        return ProblemException.conflict("/errors/sales/return-stale", "Đơn hàng đã thay đổi", detail);
    }

    public record Selection(BigDecimal lineNo, BigDecimal quantity) {}
    public record QuoteRequest(List<Selection> lines) {}
    public record CreateRequest(List<Selection> lines, String refundMethod, String reason, BigDecimal quotedRefundTotal) {}
    public record RefundLine(int lineNo, int quantity, long refundAmount) {}
    public record RefundQuote(long refundAmount, List<RefundLine> lines) {}
    public record ReturnableLine(int lineNo, String productName, String variantName, int quantity,
                                 int returnedQuantity, int remainingQuantity) {}
    public record Receipt(UUID returnId, long refundAmount, String refundMethod, String reason, OffsetDateTime createdAt) {}
    public record ReturnDetails(String orderCode, String status, List<ReturnableLine> lines, List<Receipt> receipts) {}

    private record Order(String code, String status) {}
    private record OrderLine(UUID id, UUID variantId, int lineNo, String productName, String variantName, int quantity,
                             long net, int returnedQuantity, long refunded) {
        int remaining() { return quantity - returnedQuantity; }
    }
    private record PlannedLine(OrderLine orderLine, int quantity, long refund) {}
    private record ReturnPlan(List<PlannedLine> lines, long total, boolean fullyReturned) {}
}
