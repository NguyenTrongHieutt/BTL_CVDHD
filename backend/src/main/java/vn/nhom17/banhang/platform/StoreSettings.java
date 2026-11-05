package vn.nhom17.banhang.platform;

import java.util.UUID;

/** Cài đặt của Cửa hàng hiện tại (AD-3; FR-48). Đọc trong transaction. */
public interface StoreSettings {

    View current();

    record View(
            UUID storeId,
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

        /** AD-22: chưa cài tài khoản thì màn thanh toán ẩn chuyển khoản. */
        public boolean hasBankAccount() {
            return bankBin != null && !bankBin.isBlank() && bankAccountNo != null && !bankAccountNo.isBlank();
        }
    }
}
