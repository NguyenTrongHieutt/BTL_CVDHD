package vn.nhom17.banhang.catalog;

import java.util.UUID;

/**
 * Sự kiện đồng bộ (AD-14): phát cho từng Biến thể khi Biến thể (FR-7, SP-07) hoặc cả Sản phẩm (FR-3) ngừng kinh doanh.
 * {@code inventory} đặt {@code stock.is_active = false} trong cùng transaction. Thao tác một chiều.
 */
public record VariantDeactivated(UUID storeId, UUID variantId) {
}
