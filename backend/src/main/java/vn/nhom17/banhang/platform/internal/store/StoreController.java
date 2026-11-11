package vn.nhom17.banhang.platform.internal.store;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thông tin Cửa hàng (FR-48, TK-05): tên, số điện thoại, địa chỉ, chân hóa đơn in trên hóa đơn; tài khoản ngân hàng
 * nhận VietQR (AD-22); tỷ lệ tích điểm; số ngày cảnh báo hết hạn. Chỉ Chủ cửa hàng (nhánh {@code /api/v1/platform/**}
 * của {@code SecurityConfig}, AD-16). Cửa hàng lấy từ phiên đăng nhập, không từ dữ liệu trong yêu cầu. Không có
 * {@code Idempotency-Key}: {@code PUT} ghi đè, gửi lại cùng nội dung không đổi gì.
 */
@RestController
@RequestMapping("/api/v1/platform/store")
class StoreController {

    private final StoreService stores;

    StoreController(StoreService stores) {
        this.stores = stores;
    }

    /**
     * Body {@code PUT}: thay TOÀN BỘ, trường bỏ trống hoặc thiếu thì xóa giá trị cũ (số thì báo thiếu). Kiểm ở
     * {@link StoreService#update}, trả lỗi theo trường. Số nhận {@link BigDecimal} để số lẻ bị báo lỗi thay vì bị cắt.
     *
     * @param name              bắt buộc, tối đa 200 ký tự
     * @param phone             tối đa 20 ký tự: chữ số, khoảng trắng, {@code +}, {@code .}, {@code -}
     * @param address           tối đa 300 ký tự
     * @param invoiceFooter     tối đa 300 ký tự
     * @param bankBin           mã BIN NAPAS, đúng 6 chữ số
     * @param bankAccountNo     6–19 chữ số
     * @param bankAccountName   chữ in hoa không dấu và khoảng trắng, tối đa 50 ký tự. Ba trường ngân hàng cùng trống
     *                          (tắt chuyển khoản) hoặc cùng có
     * @param loyaltyEarnAmount bắt buộc, &gt; 0
     * @param loyaltyPointValue bắt buộc, ≥ 0
     * @param expiryWarningDays bắt buộc, 0–365
     */
    record StoreRequest(String name, String phone, String address, String invoiceFooter, String bankBin,
                        String bankAccountNo, String bankAccountName, BigDecimal loyaltyEarnAmount,
                        BigDecimal loyaltyPointValue, BigDecimal expiryWarningDays) {
    }

    @GetMapping
    StoreView get() {
        return stores.get();
    }

    @PutMapping
    StoreView update(@RequestBody StoreRequest request) {
        return stores.update(request);
    }
}
