package vn.nhom17.banhang.catalog;

import java.util.UUID;

/**
 * Biến thể bán được: tên chụp lại vào dòng Đơn hàng, giá bán là số nguyên đồng (AD-8). {@code variantName} là
 * {@code null} với Biến thể của Sản phẩm một Biến thể; Sản phẩm nhiều Biến thể (FR-7, SP-07) thì là nhãn của Biến thể
 * (ví dụ "Đỏ / L"), kể cả Biến thể mặc định, mỗi Biến thể một phần tử.
 */
public record SellableVariant(
        UUID variantId,
        UUID productId,
        String productName,
        String variantName,
        String sku,
        String barcode,
        String unit,
        long salePrice) {
}
