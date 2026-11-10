package vn.nhom17.banhang.catalog.internal.supplier;

import java.util.UUID;

/**
 * Nhà cung cấp trả cho frontend: {@code GET} danh sách, {@code GET /{id}}, {@code POST}, {@code PUT}. {@code code} chữ
 * HOA; {@code code}, {@code phone}, {@code address}, {@code note} là {@code null} khi bỏ trống.
 */
record SupplierView(UUID supplierId, String code, String name, String phone, String address, String note) {

    static SupplierView of(SupplierEntity supplier) {
        return new SupplierView(supplier.id(), supplier.code(), supplier.name(), supplier.phone(), supplier.address(),
                supplier.note());
    }
}
