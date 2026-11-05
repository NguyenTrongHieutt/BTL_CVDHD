package vn.nhom17.banhang.sales;

import java.util.UUID;

/** Số dư Điểm tích lũy của Khách hàng ({@code customer} không có cột điểm, AD-17). Hiện thực ở GĐ3 (Anh, KH-04). */
public interface LoyaltyQuery {

    int balanceOf(UUID customerId);
}
