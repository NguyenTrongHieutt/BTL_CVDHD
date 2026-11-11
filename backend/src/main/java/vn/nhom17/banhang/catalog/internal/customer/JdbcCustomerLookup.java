package vn.nhom17.banhang.catalog.internal.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.platform.CurrentContext;

/**
 * Hiện thực {@link CustomerLookup} cho {@code sales} (DH-06 gắn Khách hàng vào Đơn hàng, KH-04 tích điểm). Chỉ Khách
 * hàng chưa xóa mềm của Cửa hàng hiện tại ({@code store_id} và RLS). Mỗi phương thức chạy trong transaction chỉ đọc,
 * hoặc tham gia transaction của bên gọi, nên dùng được cả dưới {@code TenantScope.runAs}.
 */
@Component
class JdbcCustomerLookup implements CustomerLookup {

    private static final String SELECT = """
            select id, name, phone from app.customer
            where store_id = ? and deleted_at is null
            """;

    private static final RowMapper<CustomerSummary> ROW = (rs, i) -> new CustomerSummary(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone"));

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcCustomerLookup(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findById(UUID customerId) {
        // Không có ngữ cảnh Cửa hàng thì lỗi ngay, kể cả khi id là null
        UUID storeId = context.storeId();
        if (customerId == null) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " and id = ?", ROW, storeId, customerId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> findByPhone(String phone) {
        UUID storeId = context.storeId();
        String normalized = CustomerPhones.normalize(phone);
        if (normalized == null) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + " and phone = ?", ROW, storeId, normalized).stream().findFirst();
    }
}
