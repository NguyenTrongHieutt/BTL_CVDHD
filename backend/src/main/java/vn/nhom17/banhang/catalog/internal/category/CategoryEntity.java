package vn.nhom17.banhang.catalog.internal.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Danh mục Sản phẩm (FR-4). {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2).
 * Tên duy nhất trong một Cửa hàng, không phân biệt hoa thường (unique {@code category_name_uq}).
 */
@Entity
@Table(schema = "app", name = "category")
public class CategoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CategoryEntity() {
    }

    public CategoryEntity(String name, Instant now) {
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String name() {
        return name;
    }
}
