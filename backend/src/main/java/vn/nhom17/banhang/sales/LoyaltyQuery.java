package vn.nhom17.banhang.sales;

import java.util.UUID;

/** Số dư Điểm tích lũy của Khách hàng tại Cửa hàng hiện tại ({@code customer} không có cột điểm, AD-17). */
public interface LoyaltyQuery {

    int balanceOf(UUID customerId);
}
