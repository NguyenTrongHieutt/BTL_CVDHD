package vn.nhom17.banhang.catalog.internal.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Khách hàng (FR-18). {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2). Số điện
 * thoại lưu dạng {@code 0…}, duy nhất trong một Cửa hàng giữa các Khách hàng chưa xóa mềm (unique
 * {@code customer_phone_uq}); tên, ghi chú có thể trống ({@code null}): tạo nhanh chỉ cần số điện thoại (FR-14).
 *
 * <p>Không có điểm tích lũy (KH-04 của sales) và chưa có thao tác xóa: {@code deleted_at} chỉ đọc, để mọi truy vấn bỏ
 * qua Khách hàng đã xóa mềm.
 */
@Entity
@Table(schema = "app", name = "customer")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    private String name;

    @Column(nullable = false)
    private String phone;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at", insertable = false, updatable = false)
    private Instant deletedAt;

    protected CustomerEntity() {
    }

    /** Khách hàng mới; {@code name}, {@code note} là {@code null} khi bỏ trống. */
    CustomerEntity(String phone, String name, String note, Instant now) {
        this.phone = phone;
        this.name = name;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Thay toàn bộ hồ sơ; trả {@code false} (không đổi gì, kể cả {@code updated_at}) khi y nguyên. */
    boolean replace(String newPhone, String newName, String newNote, Instant now) {
        if (phone.equals(newPhone) && Objects.equals(name, newName) && Objects.equals(note, newNote)) {
            return false;
        }
        this.phone = newPhone;
        this.name = newName;
        this.note = newNote;
        this.updatedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String phone() {
        return phone;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
