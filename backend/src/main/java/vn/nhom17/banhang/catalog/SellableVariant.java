package vn.nhom17.banhang.catalog;

import java.util.UUID;

/** Biến thể bán được: tên chụp lại vào dòng Đơn hàng, giá bán là số nguyên đồng (AD-8). */
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
