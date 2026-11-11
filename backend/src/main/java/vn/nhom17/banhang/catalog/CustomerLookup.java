package vn.nhom17.banhang.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra cứu Khách hàng cho sales (DH-06 gắn Khách hàng vào Đơn hàng, KH-04 tích điểm). Hiện thực ở catalog (Hiếu,
 * KH-01/KH-02). Chỉ trả Khách hàng chưa xóa mềm của Cửa hàng hiện tại; id hay số điện thoại của Cửa hàng khác là
 * "không có". Gọi trong transaction của bên gọi hoặc tự mở transaction chỉ đọc.
 *
 * <p>Cửa hàng hiện tại lấy từ {@code CurrentContext}: JWT của request, hoặc {@code TenantScope.runAs} (seed, test).
 * Không có ngữ cảnh nào: mọi phương thức ném {@link IllegalStateException} ("Chưa có ngữ cảnh Cửa hàng"), là lỗi lập
 * trình, kể cả khi đối số là {@code null}.
 */
public interface CustomerLookup {

    /** Khách hàng theo id; id {@code null}, id lạ, của Cửa hàng khác thì rỗng. */
    Optional<CustomerSummary> findById(UUID customerId);

    /**
     * Khách hàng theo số điện thoại, chuẩn hóa như lúc lưu: {@code 0} + 9 chữ số hoặc {@code +84} + 9 chữ số, được có
     * dấu cách, dấu chấm, gạch ngang ({@code "+84 912 345 678"} tìm ra Khách hàng {@code 0912345678}). Số không đúng
     * dạng hay {@code null} thì rỗng, không ném lỗi.
     */
    Optional<CustomerSummary> findByPhone(String phone);

    /**
     * Khách hàng rút gọn. {@code name} là {@code null} khi tạo nhanh chỉ bằng số điện thoại (FR-14); {@code phone}
     * dạng {@code 0…} (10 chữ số).
     */
    record CustomerSummary(UUID customerId, String name, String phone) {
    }
}
