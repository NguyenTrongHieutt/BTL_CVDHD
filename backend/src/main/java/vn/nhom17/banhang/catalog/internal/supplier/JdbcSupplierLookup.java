package vn.nhom17.banhang.catalog.internal.supplier;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.SupplierLookup;
import vn.nhom17.banhang.platform.CurrentContext;

/**
 * Hiện thực {@link SupplierLookup} cho {@code inventory} (KHO-03 Phiếu nhập). Chỉ Nhà cung cấp chưa xóa mềm của Cửa
 * hàng hiện tại ({@code store_id} và RLS). Chạy trong transaction chỉ đọc, hoặc tham gia transaction của bên gọi, nên
 * dùng được cả dưới {@code TenantScope.runAs}.
 */
@Component
class JdbcSupplierLookup implements SupplierLookup {

    private static final RowMapper<SupplierSummary> ROW = (rs, i) -> new SupplierSummary(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"));

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcSupplierLookup(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierSummary> findById(UUID supplierId) {
        // Không có ngữ cảnh Cửa hàng thì lỗi ngay, kể cả khi id là null
        UUID storeId = context.storeId();
        if (supplierId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id, code, name from app.supplier
                where store_id = ? and id = ? and deleted_at is null
                """, ROW, storeId, supplierId).stream().findFirst();
    }
}
