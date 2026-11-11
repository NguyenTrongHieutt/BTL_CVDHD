package vn.nhom17.banhang.catalog.internal.supplier;

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
 * Nhà cung cấp (FR-23). {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2). Mã lưu
 * chữ HOA, duy nhất trong một Cửa hàng giữa các Nhà cung cấp chưa xóa mềm (unique {@code supplier_code_uq}, chỉ khi
 * có mã); tên được trùng. Mã, số điện thoại, địa chỉ, ghi chú có thể trống ({@code null}).
 *
 * <p>Không có thao tác xóa: {@code deleted_at} chỉ đọc, để mọi truy vấn bỏ qua Nhà cung cấp đã xóa mềm.
 */
@Entity
@Table(schema = "app", name = "supplier")
public class SupplierEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    private String code;

    @Column(nullable = false)
    private String name;

    private String phone;

    private String address;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at", insertable = false, updatable = false)
    private Instant deletedAt;

    protected SupplierEntity() {
    }

    /** Nhà cung cấp mới từ dữ liệu đã kiểm; trường bỏ trống là {@code null}. */
    SupplierEntity(SupplierService.SupplierData data, Instant now) {
        apply(data);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Thay toàn bộ; trả {@code false} (không đổi gì, kể cả {@code updated_at}) khi y nguyên. */
    boolean replace(SupplierService.SupplierData data, Instant now) {
        if (Objects.equals(code, data.code()) && name.equals(data.name()) && Objects.equals(phone, data.phone())
                && Objects.equals(address, data.address()) && Objects.equals(note, data.note())) {
            return false;
        }
        apply(data);
        this.updatedAt = now;
        return true;
    }

    private void apply(SupplierService.SupplierData data) {
        this.code = data.code();
        this.name = data.name();
        this.phone = data.phone();
        this.address = data.address();
        this.note = data.note();
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String phone() {
        return phone;
    }

    public String address() {
        return address;
    }

    public String note() {
        return note;
    }
}
