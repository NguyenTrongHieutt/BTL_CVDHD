package vn.nhom17.banhang.sales.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.UserNameLookup;

/** Chứng từ nguồn chỉ đọc khi Chủ cửa hàng tra cứu Sổ kho (KHO-07). */
@RestController
@RequestMapping("/api/v1/sales/ledger-documents")
public class SalesLedgerDocumentController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final CustomerLookup customers;
    private final UserNameLookup users;

    public SalesLedgerDocumentController(JdbcTemplate jdbc, CurrentContext context, CustomerLookup customers,
                                         UserNameLookup users) {
        this.jdbc = jdbc;
        this.context = context;
        this.customers = customers;
        this.users = users;
    }

    @GetMapping("/{type}/{id}")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Document document(@PathVariable DocumentType type, @PathVariable UUID id) {
        UUID storeId = context.storeId();
        return switch (type) {
            case SALE, SALE_CANCEL -> orderDocument(storeId, type, id);
            case SALE_RETURN -> returnDocument(storeId, id);
        };
    }

    private Document orderDocument(UUID storeId, DocumentType type, UUID id) {
        boolean cancellation = type == DocumentType.SALE_CANCEL;
        OrderRow order;
        try {
            order = jdbc.queryForObject("""
                    SELECT order_code, created_at, created_by, status, customer_id, subtotal,
                           line_discount_total + order_discount + points_discount AS discount, total_amount,
                           cancelled_at, cancelled_by, cancel_reason
                    FROM app.sales_order
                    WHERE store_id = ? AND id = ?
                    """ + (cancellation
                    ? " AND status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL" : ""),
                    (rs, row) -> new OrderRow(rs.getString("order_code"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                            rs.getString("status"), rs.getObject("customer_id", UUID.class),
                            rs.getLong("subtotal"), rs.getLong("discount"), rs.getLong("total_amount"),
                            rs.getObject("cancelled_at", OffsetDateTime.class),
                            rs.getObject("cancelled_by", UUID.class), rs.getString("cancel_reason")), storeId, id);
        } catch (EmptyResultDataAccessException ex) {
            throw notFound();
        }

        // Như hóa đơn in lại: giữ tên, số lượng và giá đã chụp lúc bán; không tính lại từ Danh mục.
        var lines = jdbc.query("""
                SELECT line_no, product_name, variant_name, quantity, unit_price, net_line_amount
                FROM app.sales_order_line
                WHERE store_id = ? AND order_id = ?
                ORDER BY line_no
                """, (rs, row) -> new Line(rs.getInt("line_no"), rs.getString("product_name"),
                        rs.getString("variant_name"), rs.getInt("quantity"),
                        rs.getLong("unit_price"), rs.getLong("net_line_amount")), storeId, id);
        var methods = jdbc.query("""
                SELECT method FROM app.payment
                WHERE store_id = ? AND order_id = ?
                ORDER BY created_at, id LIMIT 1
                """, (rs, row) -> rs.getString("method"), storeId, id);
        UUID actorId = cancellation ? order.cancelledBy() : order.createdBy();
        return new Document(type, id, order.code(), cancellation ? order.cancelledAt() : order.createdAt(),
                actorName(actorId), order.status(), customerName(order.customerId()),
                cancellation ? order.cancelReason() : null, null, id,
                methods.isEmpty() ? null : methods.getFirst(), order.subtotal(), order.discount(), order.total(), lines);
    }

    private Document returnDocument(UUID storeId, UUID id) {
        ReturnRow receipt;
        try {
            receipt = jdbc.queryForObject("""
                    SELECT r.order_id, o.order_code, o.customer_id, r.created_at, r.created_by,
                           r.refund_amount, r.refund_method, r.reason
                    FROM app.sales_return r
                    JOIN app.sales_order o ON o.store_id = r.store_id AND o.id = r.order_id
                    WHERE r.store_id = ? AND r.id = ?
                    """, (rs, row) -> new ReturnRow(rs.getObject("order_id", UUID.class),
                            rs.getString("order_code"), rs.getObject("customer_id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("created_by", UUID.class),
                            rs.getLong("refund_amount"), rs.getString("refund_method"), rs.getString("reason")),
                    storeId, id);
        } catch (EmptyResultDataAccessException ex) {
            throw notFound();
        }

        // Chỉ các dòng trong phiếu trả này; tiền hoàn lấy nguyên từ chứng từ đã ghi, kể cả phần làm tròn.
        var lines = jdbc.query("""
                SELECT rl.line_no, ol.product_name, ol.variant_name, rl.quantity, rl.refund_amount
                FROM app.sales_return_line rl
                JOIN app.sales_order_line ol ON ol.store_id = rl.store_id AND ol.id = rl.order_line_id
                                            AND ol.order_id = ?
                WHERE rl.store_id = ? AND rl.return_id = ?
                ORDER BY rl.line_no
                """, (rs, row) -> new Line(rs.getInt("line_no"), rs.getString("product_name"),
                        rs.getString("variant_name"), rs.getInt("quantity"), null, rs.getLong("refund_amount")),
                receipt.orderId(), storeId, id);
        return new Document(DocumentType.SALE_RETURN, id, null, receipt.createdAt(), actorName(receipt.createdBy()),
                null, customerName(receipt.customerId()), receipt.reason(), receipt.orderCode(), receipt.orderId(),
                receipt.refundMethod(), null, null, receipt.refundAmount(), lines);
    }

    private String actorName(UUID actorId) {
        return users.findNames(List.of(actorId)).get(actorId);
    }

    private String customerName(UUID customerId) {
        return customers.findById(customerId)
                .map(customer -> customer.name() == null || customer.name().isBlank() ? customer.phone() : customer.name())
                .orElse(null);
    }

    private static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy chứng từ",
                "Chứng từ không tồn tại trong cửa hàng hiện tại.");
    }

    public enum DocumentType { SALE, SALE_CANCEL, SALE_RETURN }

    public record Document(DocumentType documentType, UUID documentId, String code, OffsetDateTime recordedAt,
                           String actorName, String status, String customerName, String note,
                           String relatedOrderCode, UUID orderId, String paymentMethod,
                           Long subtotal, Long discount, Long totalAmount, List<Line> lines) {}

    public record Line(int lineNo, String productName, String variantName, int quantity, Long unitAmount,
                       Long amount) {}

    private record OrderRow(String code, OffsetDateTime createdAt, UUID createdBy, String status, UUID customerId,
                            long subtotal, long discount, long total, OffsetDateTime cancelledAt, UUID cancelledBy,
                            String cancelReason) {}

    private record ReturnRow(UUID orderId, String orderCode, UUID customerId, OffsetDateTime createdAt, UUID createdBy,
                             long refundAmount, String refundMethod, String reason) {}
}
