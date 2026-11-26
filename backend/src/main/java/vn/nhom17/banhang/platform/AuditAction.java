package vn.nhom17.banhang.platform;

/**
 * Danh sách thao tác được ghi Nhật ký. Mã có dạng {@code <module>.<entity>.<verb>}.
 * Chủ module thêm giá trị mới cho module của mình qua PR (Hiếu duyệt).
 */
public enum AuditAction {
    PLATFORM_USER_CREATE("platform.user.create", "app_user", "Tạo tài khoản Nhân viên"),
    PLATFORM_USER_LOCK("platform.user.lock", "app_user", "Khóa tài khoản Nhân viên"),
    PLATFORM_USER_UNLOCK("platform.user.unlock", "app_user", "Mở khóa tài khoản Nhân viên"),
    PLATFORM_USER_RESET_PASSWORD("platform.user.reset_password", "app_user", "Đặt lại mật khẩu Nhân viên"),
    PLATFORM_STORE_UPDATE("platform.store.update", "store", "Cập nhật thông tin Cửa hàng"),
    CATALOG_VARIANT_PRICE_CHANGE("catalog.variant.price_change", "product_variant", "Sửa giá bán"),
    CATALOG_PRODUCT_DEACTIVATE("catalog.product.deactivate", "product", "Ngừng kinh doanh Sản phẩm"),
    /** Ngừng kinh doanh một Biến thể (FR-7, SP-07); ngừng cả Sản phẩm là {@link #CATALOG_PRODUCT_DEACTIVATE}. */
    CATALOG_VARIANT_DEACTIVATE("catalog.variant.deactivate", "product_variant", "Ngừng kinh doanh Biến thể"),
    SALES_ORDER_CANCEL("sales.order.cancel", "sales_order", "Hủy Đơn hàng"),
    SALES_RETURN_CREATE("sales.return.create", "sales_return", "Trả hàng"),
    INVENTORY_STOCKTAKE_COMPLETE("inventory.stocktake.complete", "stocktake", "Hoàn tất kiểm kê");

    private final String code;
    private final String entity;

    private final String label;

    AuditAction(String code, String entity, String label) {
        this.code = code;
        this.entity = entity;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String code() {
        return code;
    }

    public String entity() {
        return entity;
    }
}
