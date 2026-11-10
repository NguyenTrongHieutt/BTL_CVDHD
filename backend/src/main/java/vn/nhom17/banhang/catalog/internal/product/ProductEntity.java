package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Sản phẩm (FR-1). {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2). Không có cột
 * Giá vốn (AD-15): Giá vốn ban đầu chỉ đi qua sự kiện {@code VariantCreated} sang {@code inventory}.
 *
 * <p>Id gán sẵn ở Java (không sinh khi INSERT) để tạo cùng Biến thể mặc định trong một lần flush; luôn lưu bằng
 * {@code EntityManager.persist}.
 */
@Entity
@Table(schema = "app", name = "product")
public class ProductEntity {

    static final String ACTIVE = "ACTIVE";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String unit;

    private String description;

    /** {@code false} khi chỉ có Biến thể mặc định; nhiều Biến thể là SP-07 (GĐ3). */
    @Column(name = "has_variants", nullable = false)
    private boolean hasVariants;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Con trỏ change-feed (AD-12). */
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected ProductEntity() {
    }

    /** Sản phẩm mới đang kinh doanh, chỉ có Biến thể mặc định; mọi mốc giờ là {@code now} (lấy từ {@code Clock}). */
    ProductEntity(UUID id, String name, UUID categoryId, String unit, String description, Instant now) {
        this.id = id;
        this.name = name;
        this.categoryId = categoryId;
        this.unit = unit;
        this.description = description;
        this.hasVariants = false;
        this.status = ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.changedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }
}
