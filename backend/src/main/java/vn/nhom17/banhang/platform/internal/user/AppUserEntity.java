package vn.nhom17.banhang.platform.internal.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Tài khoản người dùng. {@code store_id} do Hibernate tự điền và tự lọc qua {@code @TenantId} (AD-5 mục 2). */
@Entity
@Table(schema = "app", name = "app_user")
public class AppUserEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "login_key", nullable = false)
    private String loginKey;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String status;

    private String phone;
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUserEntity() {
    }

    public AppUserEntity(UUID id, String loginKey, String displayName, String passwordHash, String role, Instant now) {
        this(id, loginKey, displayName, passwordHash, role, null, now);
    }

    public AppUserEntity(UUID id, String loginKey, String displayName, String passwordHash, String role, String phone,
                         Instant now) {
        this.id = id;
        this.loginKey = loginKey;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.phone = phone;
        this.status = UserStatus.ACTIVE.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String loginKey() {
        return loginKey;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }

    public String status() {
        return status;
    }

    public String phone() {
        return phone;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Đặt trạng thái; trả {@code false} (không đổi gì) khi đã ở trạng thái đó. */
    boolean changeStatus(UserStatus next, Instant now) {
        if (next.name().equals(status)) {
            return false;
        }
        status = next.name();
        updatedAt = now;
        return true;
    }

    void changePassword(String newHash, Instant now) {
        passwordHash = newHash;
        updatedAt = now;
    }
}
