package vn.nhom17.banhang.platform.internal.store;

import java.util.LinkedHashMap;
import java.util.Map;
import vn.nhom17.banhang.platform.StoreSettings;

/**
 * Thông tin Cửa hàng (FR-48, TK-05): phản hồi của {@code GET} và {@code PUT /api/v1/platform/store}, cũng là dạng
 * đã chuẩn hóa của body {@code PUT}. Trường văn bản không bắt buộc là {@code null} khi bỏ trống; ba trường ngân hàng
 * cùng {@code null} khi chưa cài tài khoản nhận chuyển khoản.
 *
 * @param loyaltyEarnAmount số tiền (đồng) để được 1 điểm
 * @param loyaltyPointValue giá trị (đồng) của 1 điểm khi đổi
 * @param expiryWarningDays cảnh báo lô hàng sắp hết hạn trước bấy nhiêu ngày
 */
record StoreView(
        String name,
        String phone,
        String address,
        String invoiceFooter,
        String bankBin,
        String bankAccountNo,
        String bankAccountName,
        long loyaltyEarnAmount,
        long loyaltyPointValue,
        int expiryWarningDays) {

    static StoreView of(StoreSettings.View settings) {
        return new StoreView(settings.name(), settings.phone(), settings.address(), settings.invoiceFooter(),
                settings.bankBin(), settings.bankAccountNo(), settings.bankAccountName(),
                settings.loyaltyEarnAmount(), settings.loyaltyPointValue(), settings.expiryWarningDays());
    }

    /** Mọi trường theo tên trong JSON, để so trước, sau khi ghi Nhật ký (giá trị có thể là {@code null}). */
    Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("phone", phone);
        fields.put("address", address);
        fields.put("invoiceFooter", invoiceFooter);
        fields.put("bankBin", bankBin);
        fields.put("bankAccountNo", bankAccountNo);
        fields.put("bankAccountName", bankAccountName);
        fields.put("loyaltyEarnAmount", loyaltyEarnAmount);
        fields.put("loyaltyPointValue", loyaltyPointValue);
        fields.put("expiryWarningDays", expiryWarningDays);
        return fields;
    }
}
