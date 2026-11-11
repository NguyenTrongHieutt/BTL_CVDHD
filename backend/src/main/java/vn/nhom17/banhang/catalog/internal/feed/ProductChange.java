package vn.nhom17.banhang.catalog.internal.feed;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng change-feed (AD-12), lấy nguyên từ view {@code app.catalog_product_changes}.
 *
 * @param storeId    Cửa hàng của Sản phẩm
 * @param productId  Sản phẩm
 * @param searchText chữ để đánh chỉ mục: tên, tên Danh mục, nhãn và thuộc tính các Biến thể chưa xóa mềm
 * @param status     {@code ACTIVE}, hoặc {@code INACTIVE} khi đã ngừng kinh doanh hay đã xóa mềm (dịch vụ AI xóa
 *                   embedding)
 * @param changedAt  {@code product.changed_at}, ISO-8601 UTC
 */
record ProductChange(UUID storeId, UUID productId, String searchText, String status, Instant changedAt) {
}
