package vn.nhom17.banhang.platform.internal.user;

/** Trạng thái tài khoản ({@code app_user.status}): {@code LOCKED} thì không đăng nhập được, phiên đang mở bị chặn. */
enum UserStatus {
    ACTIVE,
    LOCKED
}
