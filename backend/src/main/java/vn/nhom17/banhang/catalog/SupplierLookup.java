package vn.nhom17.banhang.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra cứu Nhà cung cấp cho inventory (KHO-03 Phiếu nhập). Hiện thực ở catalog (Hiếu, KHO-02). Chỉ trả Nhà cung cấp
 * chưa xóa mềm của Cửa hàng hiện tại; id của Cửa hàng khác là "không có". Gọi trong transaction của bên gọi hoặc tự
 * mở transaction chỉ đọc.
 *
 * <p>Cửa hàng hiện tại lấy từ {@code CurrentContext}: JWT của request, hoặc {@code TenantScope.runAs} (seed, test).
 * Không có ngữ cảnh nào: ném {@link IllegalStateException} ("Chưa có ngữ cảnh Cửa hàng"), là lỗi lập trình, kể cả khi
 * đối số là {@code null}.
 */
public interface SupplierLookup {

    /** Nhà cung cấp theo id; id {@code null}, id lạ, của Cửa hàng khác, đã xóa mềm thì rỗng. */
    Optional<SupplierSummary> findById(UUID supplierId);

    /** Nhà cung cấp rút gọn. {@code code} chữ HOA, {@code null} khi Nhà cung cấp không có mã. */
    record SupplierSummary(UUID supplierId, String code, String name) {
    }
}
