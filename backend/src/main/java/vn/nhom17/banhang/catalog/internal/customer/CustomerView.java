package vn.nhom17.banhang.catalog.internal.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * Khách hàng trả cho frontend: {@code GET} danh sách, {@code GET /{id}}, {@code POST}, {@code PUT}. {@code name},
 * {@code note} là {@code null} khi bỏ trống; {@code phone} dạng {@code 0…}. Không có điểm tích lũy (KH-04, GĐ3).
 */
record CustomerView(UUID customerId, String name, String phone, String note, Instant createdAt) {

    static CustomerView of(CustomerEntity customer) {
        return new CustomerView(customer.id(), customer.name(), customer.phone(), customer.note(),
                customer.createdAt());
    }
}
