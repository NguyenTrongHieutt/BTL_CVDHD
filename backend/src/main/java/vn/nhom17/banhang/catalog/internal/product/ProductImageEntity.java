package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Ảnh của một Sản phẩm (FR-1, SP-01b), lưu ngay trong database: một dòng mỗi Sản phẩm, khóa chính là
 * {@code product_id}. {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2).
 *
 * <p>{@code version} tăng mỗi lần tải ảnh lên và nằm trong {@code product.image_url} ({@code ?v=}) để trình duyệt không
 * giữ ảnh cũ. Gỡ ảnh không xóa dòng mà để {@code content_type} {@code null}, {@code content} rỗng, giữ
 * {@code version}: lần tải sau vẫn ra số phiên bản mới, không trùng URL ảnh cũ trình duyệt còn giữ trong cache.
 */
@Entity
@Table(schema = "app", name = "product_image")
public class ProductImageEntity {

    private static final byte[] NO_CONTENT = new byte[0];

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    /** {@code image/jpeg}, {@code image/png} hoặc {@code image/webp}; {@code null} khi đã gỡ ảnh. */
    @Column(name = "content_type")
    private String contentType;

    @Column(nullable = false)
    private byte[] content;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductImageEntity() {
    }

    /** Ảnh đầu tiên của Sản phẩm: phiên bản 1. */
    ProductImageEntity(UUID productId, String contentType, byte[] content, Instant now) {
        this.productId = productId;
        this.contentType = contentType;
        this.content = content;
        this.version = 1;
        this.updatedAt = now;
    }

    /** Thay ảnh (kể cả tải lại sau khi đã gỡ): phiên bản tăng 1. */
    void replace(String newContentType, byte[] newContent, Instant now) {
        this.contentType = newContentType;
        this.content = newContent;
        this.version++;
        this.updatedAt = now;
    }

    /** Gỡ ảnh, giữ {@code version}. Trả {@code false} nếu đã gỡ từ trước (không đổi gì). */
    boolean remove(Instant now) {
        if (!hasImage()) {
            return false;
        }
        this.contentType = null;
        this.content = NO_CONTENT;
        this.updatedAt = now;
        return true;
    }

    boolean hasImage() {
        return contentType != null;
    }

    int version() {
        return version;
    }
}
