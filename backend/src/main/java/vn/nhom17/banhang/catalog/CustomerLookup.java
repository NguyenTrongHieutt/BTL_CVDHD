package vn.nhom17.banhang.catalog;

import java.util.Optional;
import java.util.UUID;

/** Tra cứu Khách hàng cho sales (DH-06, KH-04). Hiện thực ở GĐ1 (Hiếu, KH-01/KH-02). */
public interface CustomerLookup {

    Optional<CustomerSummary> findById(UUID customerId);

    Optional<CustomerSummary> findByPhone(String phone);

    record CustomerSummary(UUID customerId, String name, String phone) {
    }
}
