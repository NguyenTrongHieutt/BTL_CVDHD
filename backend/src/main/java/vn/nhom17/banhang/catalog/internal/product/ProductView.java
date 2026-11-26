package vn.nhom17.banhang.catalog.internal.product;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chi tiết Sản phẩm trả cho frontend: {@code POST} (201, hoặc 200 khi phát lại), {@code GET /{id}}, {@code PUT /{id}},
 * ngừng kinh doanh, tải ảnh lên ({@code PUT /{id}/image}), các thao tác Biến thể (SP-07) và từng dòng của danh sách.
 * Cả hai vai trò đọc được, nên KHÔNG có Giá vốn hay tồn đầu kỳ (AD-15, AD-16). {@code variants} gồm Biến thể chưa xóa
 * mềm, Biến thể mặc định đứng đầu, rồi theo thứ tự tạo. {@code imageUrl} là đường dẫn
 * {@code GET /api/v1/catalog/products/{id}/image?v=<phiên bản>} (SP-01b), hoặc {@code null} khi chưa có ảnh.
 * {@code priceRange} là giá bán thấp nhất, cao nhất của các Biến thể đang bán (SP-07), {@code null} khi không còn Biến
 * thể nào đang bán (Sản phẩm đã ngừng kinh doanh).
 */
record ProductView(
        UUID id,
        String name,
        UUID categoryId,
        String categoryName,
        String unit,
        String description,
        String imageUrl,
        boolean hasVariants,
        String status,
        Instant createdAt,
        Instant updatedAt,
        PriceRange priceRange,
        List<Variant> variants) {

    /**
     * Biến thể; giá bán là số nguyên đồng (AD-8). {@code name} (nhãn) là {@code null} với Biến thể mặc định của Sản
     * phẩm một Biến thể; {@code attributes} là các cặp tên → giá trị thuộc tính (rỗng khi không có).
     */
    record Variant(UUID id, String sku, String barcode, String name, Map<String, String> attributes, long salePrice,
                   boolean isDefault, String status) {
    }

    /** Khoảng giá bán của các Biến thể đang bán, gồm cả hai đầu. */
    record PriceRange(long min, long max) {
    }
}
