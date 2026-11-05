package vn.nhom17.banhang.catalog;

import java.util.UUID;

/**
 * Sự kiện đồng bộ (AD-14): phát trong transaction tạo Biến thể. {@code inventory} nhận bằng
 * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}, tạo dòng {@code stock}; có tồn đầu kỳ thì gọi
 * {@code StockService.apply} với chứng từ {@code OPENING}.
 */
public record VariantCreated(UUID storeId, UUID variantId, int openingQuantity, long openingUnitCost) {
}
