package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Biến thể của Sản phẩm (AD-6: Tồn kho khóa theo {@code variant_id}). {@code store_id} do Hibernate tự điền và tự lọc
 * qua {@code @TenantId}. SKU và mã vạch lưu chữ HOA, duy nhất trong Cửa hàng khi chưa xóa mềm (unique
 * {@code product_variant_sku_uq}, {@code product_variant_barcode_uq}); hai Cửa hàng được trùng nhau.
 * Cột {@code attributes} để mặc định {@code '{}'} của database.
 *
 * <p>Id gán sẵn ở Java vì SKU tự sinh lấy từ id ({@code SP-} + 8 ký tự đầu, viết hoa).
 */
@Entity
@Table(schema = "app", name = "product_variant")
public class ProductVariantEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false)
    private String sku;

    private String barcode;

    /** Nhãn Biến thể, ví dụ "Đỏ / L"; {@code null} với Biến thể mặc định. */
    private String name;

    /** Giá bán, số nguyên đồng (AD-8). */
    @Column(name = "sale_price", nullable = false)
    private long salePrice;

    @Column(name = "is_default", nullable = false, updatable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductVariantEntity() {
    }

    private ProductVariantEntity(UUID id, UUID productId, String sku, String barcode, long salePrice, boolean isDefault,
                                 Instant now) {
        this.id = id;
        this.productId = productId;
        this.sku = sku;
        this.barcode = barcode;
        this.salePrice = salePrice;
        this.isDefault = isDefault;
        this.status = ProductEntity.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Biến thể mặc định ({@code is_default}, không nhãn) của Sản phẩm không có nhiều Biến thể. */
    static ProductVariantEntity defaultOf(UUID id, UUID productId, String sku, String barcode, long salePrice,
                                          Instant now) {
        return new ProductVariantEntity(id, productId, sku, barcode, salePrice, true, now);
    }

    public UUID id() {
        return id;
    }

    public String sku() {
        return sku;
    }
}
