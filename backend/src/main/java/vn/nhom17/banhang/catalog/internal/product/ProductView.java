package vn.nhom17.banhang.catalog.internal.product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chi tiết Sản phẩm trả cho frontend: {@code POST} (201, hoặc 200 khi phát lại), {@code GET /{id}}, {@code PUT /{id}},
 * ngừng kinh doanh, tải ảnh lên ({@code PUT /{id}/image}) và từng dòng của danh sách. Cả hai vai trò đọc được, nên
 * KHÔNG có Giá vốn hay tồn đầu kỳ (AD-15, AD-16). {@code variants} gồm Biến thể chưa xóa mềm, Biến thể mặc định đứng
 * đầu. {@code imageUrl} là đường dẫn {@code GET /api/v1/catalog/products/{id}/image?v=<phiên bản>} (SP-01b), hoặc
 * {@code null} khi chưa có ảnh.
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
        List<Variant> variants) {

    /** Biến thể; giá bán là số nguyên đồng (AD-8). {@code name} là {@code null} với Biến thể mặc định. */
    record Variant(UUID id, String sku, String barcode, String name, long salePrice, boolean isDefault, String status) {
    }
}
