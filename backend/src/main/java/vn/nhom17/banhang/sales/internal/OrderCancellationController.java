package vn.nhom17.banhang.sales.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
@RequestMapping("/api/v1/sales/orders")
public class OrderCancellationController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final StockService stock;
    private final AuditLog audit;
    private final Clock clock;

    public OrderCancellationController(JdbcTemplate jdbc, CurrentContext context, IdempotencyService idempotency,
                                       StockService stock, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.idempotency = idempotency;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Cancellation> cancel(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestBody CancellationRequest request) {
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 200
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Lý do hủy không hợp lệ",
                    "Nhập lý do hủy trên một dòng, tối đa 200 ký tự.");
        }

        String hash = idempotency.hash(Map.of("orderId", id, "reason", reason, "actorId", context.userId()));
        var result = idempotency.execute(StockDocumentType.SALE_CANCEL.name(), key, hash,
                () -> cancelOrder(id, reason), this::load);
        var response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.value());
    }

    private UUID cancelOrder(UUID id, String reason) {
        UUID storeId = context.storeId();
        OrderForCancellation order;
        try {
            // Hủy và trả hàng phải khóa cùng dòng đơn trước khi kiểm tra trạng thái.
            order = jdbc.queryForObject("""
                    SELECT status, created_by, created_at
                    FROM app.sales_order
                    WHERE store_id = ? AND id = ?
                    FOR UPDATE
                    """, (rs, row) -> new OrderForCancellation(rs.getString("status"),
                    rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class)),
                    storeId, id);
        } catch (EmptyResultDataAccessException ex) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy đơn hàng",
                    "Đơn hàng không tồn tại trong cửa hàng hiện tại.");
        }

        var now = clock.instant();
        UUID actorId = context.userId();
        if (!OrderCancellationPolicy.permits(context.isOwner(), actorId, order.sellerId(), order.createdAt(), now)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "/errors/common/forbidden", "Không có quyền hủy đơn",
                    "Nhân viên chỉ được hủy đơn của mình trong cùng ngày theo giờ Việt Nam.");
        }
        if (!"COMPLETED".equals(order.status())) {
            throw ProblemException.conflict("/errors/sales/invalid-state", "Không thể hủy đơn",
                    "Chỉ đơn ở trạng thái Hoàn thành được phép hủy.");
        }
        boolean hasReturns = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.sales_return WHERE store_id = ? AND order_id = ?)
                """, Boolean.class, storeId, id));
        if (hasReturns) {
            throw ProblemException.conflict("/errors/sales/invalid-state", "Không thể hủy đơn",
                    "Đơn đã có phiếu trả hàng.");
        }

        var lines = jdbc.query("""
                SELECT line_no, variant_id, quantity
                FROM app.sales_order_line
                WHERE store_id = ? AND order_id = ?
                """, (rs, row) -> new StockService.StockLine(rs.getInt("line_no"),
                rs.getObject("variant_id", UUID.class), rs.getInt("quantity"), null), storeId, id);
        // Dùng cùng thứ tự khóa biến thể như khi tạo đơn.
        lines.sort(Comparator.comparing(StockService.StockLine::variantId));
        stock.apply(StockDocumentType.SALE_CANCEL, id, lines);
        jdbc.update("""
                UPDATE app.sales_order SET status = 'CANCELLED', cancelled_at = ?, cancelled_by = ?, cancel_reason = ?
                WHERE store_id = ? AND id = ?
                """, now.atOffset(ZoneOffset.UTC), actorId, reason, storeId, id);
        audit.record(AuditAction.SALES_ORDER_CANCEL, id.toString(), Map.of("status", "COMPLETED"),
                Map.of("status", "CANCELLED", "reason", reason));
        return id;
    }

    private Cancellation load(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, status, cancelled_at, cancel_reason FROM app.sales_order
                WHERE store_id = ? AND id = ?
                """, (rs, row) -> new Cancellation(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("cancelled_at", OffsetDateTime.class), rs.getString("cancel_reason")),
                context.storeId(), id);
    }

    public record CancellationRequest(String reason) {}
    public record Cancellation(UUID orderId, String status, OffsetDateTime cancelledAt, String reason) {}
    private record OrderForCancellation(String status, UUID sellerId, OffsetDateTime createdAt) {}
}
