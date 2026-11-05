package vn.nhom17.banhang.catalog;

import java.util.Optional;
import java.util.UUID;

/** Tra cứu Nhà cung cấp cho inventory (KHO-03). Hiện thực ở GĐ1 (Hiếu, KHO-02). */
public interface SupplierLookup {

    Optional<SupplierSummary> findById(UUID supplierId);

    record SupplierSummary(UUID supplierId, String code, String name) {
    }
}
