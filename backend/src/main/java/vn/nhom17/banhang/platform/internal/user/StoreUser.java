package vn.nhom17.banhang.platform.internal.user;

import java.time.Instant;
import java.util.UUID;
import vn.nhom17.banhang.platform.Role;

/**
 * Tài khoản trả cho frontend (FR-45, TK-02), tên {@code StoreUser} như trong {@code docs/api/contracts.md} và
 * {@code frontend/src/shared/types/platform.ts}: danh sách, tạo, khóa, mở khóa. Không có {@code password_hash};
 * {@code phone} là {@code null} khi bỏ trống.
 */
record StoreUser(UUID userId, String loginKey, String displayName, Role role, UserStatus status, String phone,
                 Instant createdAt) {

    static StoreUser of(AppUserEntity user) {
        return new StoreUser(user.id(), user.loginKey(), user.displayName(), Role.valueOf(user.role()),
                UserStatus.valueOf(user.status()), user.phone(), user.createdAt());
    }
}
