package vn.nhom17.banhang.platform.internal.user;

import java.util.UUID;

/** Nhãn tối thiểu để khôi phục lựa chọn người thực hiện từ URL. */
record UserOption(UUID userId, String displayName) {
}
