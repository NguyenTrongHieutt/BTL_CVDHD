package vn.nhom17.banhang.platform.internal.user;

import java.util.UUID;
import vn.nhom17.banhang.platform.Role;

/**
 * Hồ sơ của người đang đăng nhập (FR-47, TK-04): phản hồi của {@code GET /api/v1/platform/me/profile} và
 * {@code PUT /api/v1/platform/me}. Khớp với {@code Profile} trong {@code frontend/src/shared/types/platform.ts}. Không
 * có mật khẩu. {@code phone} (số liên hệ) và {@code email} là {@code null} khi không có. {@code phone} ghi từ TK-04
 * trở đi có dạng {@code 0…}; số lưu trước đó (Nhân viên tạo ở TK-02 cũ) có thể còn nguyên như lúc nhập, ví dụ
 * {@code 028 1234 5678}. Chỉ {@code displayName}, {@code phone} sửa được, {@code loginKey}, {@code email},
 * {@code role} thì không.
 */
record Profile(UUID userId, String loginKey, String displayName, Role role, String phone, String email) {

    static Profile of(AppUserEntity user) {
        return new Profile(user.id(), user.loginKey(), user.displayName(), Role.valueOf(user.role()), user.phone(),
                user.email());
    }
}
