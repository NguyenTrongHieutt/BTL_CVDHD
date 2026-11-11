package vn.nhom17.banhang.platform;

/**
 * Danh sách thao tác được ghi Nhật ký. Mã có dạng {@code <module>.<entity>.<verb>}.
 * Chủ module thêm giá trị mới cho module của mình qua PR (Hiếu duyệt).
 */
public enum AuditAction {
    PLATFORM_USER_CREATE("platform.user.create", "app_user"),
    PLATFORM_USER_LOCK("platform.user.lock", "app_user"),
    PLATFORM_USER_UNLOCK("platform.user.unlock", "app_user"),
    PLATFORM_USER_RESET_PASSWORD("platform.user.reset_password", "app_user"),
    PLATFORM_STORE_UPDATE("platform.store.update", "store"),
    CATALOG_VARIANT_PRICE_CHANGE("catalog.variant.price_change", "product_variant"),
    CATALOG_PRODUCT_DEACTIVATE("catalog.product.deactivate", "product"),
    SALES_ORDER_CANCEL("sales.order.cancel", "sales_order"),
    SALES_RETURN_CREATE("sales.return.create", "sales_return"),
    INVENTORY_STOCKTAKE_COMPLETE("inventory.stocktake.complete", "stocktake");

    private final String code;
    private final String entity;

    AuditAction(String code, String entity) {
        this.code = code;
        this.entity = entity;
    }

    public String code() {
        return code;
    }

    public String entity() {
        return entity;
    }
}
