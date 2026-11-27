package vn.nhom17.banhang.sales.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/** Kết quả cuối của lệnh bị từ chối, không phải outbox và không chứa nội dung giỏ hàng. */
@Service
class OrderRequestFailures {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    OrderRequestFailures(JdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean contains(UUID operationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.sales_order_request_failure
                    WHERE store_id = ? AND actor_id = ? AND operation_id = ?)
                """, Boolean.class, context.storeId(), context.userId(), operationId));
    }

    @Transactional
    public void record(UUID operationId, ProblemException rejection) {
        jdbc.update("""
                INSERT INTO app.sales_order_request_failure (store_id, actor_id, operation_id, reason, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (store_id, actor_id, operation_id) DO NOTHING
                """, context.storeId(), context.userId(), operationId, safeReason(rejection),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public Page list(int requestedSize) {
        int size = Math.clamp(requestedSize, 1, 50);
        String scope = "store_id = ?" + (context.isOwner() ? "" : " AND actor_id = ?");
        Object[] parameters = context.isOwner() ? new Object[]{context.storeId()}
                : new Object[]{context.storeId(), context.userId()};
        Long total = jdbc.queryForObject("SELECT count(*) FROM app.sales_order_request_failure WHERE " + scope,
                Long.class, parameters);
        var items = jdbc.query("""
                SELECT operation_id, reason, created_at FROM app.sales_order_request_failure WHERE
                """ + scope + " ORDER BY created_at DESC, operation_id DESC LIMIT " + size,
                (rs, row) -> new Failure(rs.getObject("operation_id", UUID.class), rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)), parameters);
        return new Page(items, total == null ? 0 : total);
    }

    private static String safeReason(ProblemException rejection) {
        // Chỉ thông điệp cố định: không đưa SQL, stack trace, tên khách hoặc chi tiết request vào hộp xử lý.
        return switch (rejection.type()) {
            case "/errors/sales/cart-stale" -> "Giá hoặc trạng thái sản phẩm đã thay đổi. Kiểm tra lại giỏ hàng.";
            case "/errors/inventory/insufficient-stock" -> "Không đủ tồn kho để ghi nhận đơn. Kiểm tra lại số lượng hàng.";
            case "/errors/sales/loyalty-insufficient", "/errors/sales/loyalty-invalid",
                 "/errors/sales/loyalty-limit" -> "Không thể áp dụng điểm tích lũy. Kiểm tra lại khách hàng và số điểm.";
            case "/errors/common/idempotency-key-reuse" -> "Mã yêu cầu đã dùng cho nội dung khác. Kiểm tra lịch sử bán hàng.";
            case "/errors/common/validation" -> "Dữ liệu đơn hàng không hợp lệ. Kiểm tra lại trước khi bán lại.";
            default -> "Chưa ghi nhận được đơn hàng. Kiểm tra lại giỏ hàng hoặc liên hệ chủ cửa hàng.";
        };
    }

    record Failure(UUID operationId, String reason, OffsetDateTime createdAt) {}
    record Page(List<Failure> items, long total) {}
}
