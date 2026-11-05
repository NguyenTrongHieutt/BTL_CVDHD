package vn.nhom17.banhang.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Người dùng và Cửa hàng của request hiện tại (AD-5, AD-9).
 *
 * <p>Lấy từ JWT đã xác thực, hoặc từ {@link TenantScope#runAs} khi chạy seed và test.
 */
public interface CurrentContext {

    /** Cửa hàng hiện tại; ném lỗi nếu chưa có ngữ cảnh. */
    UUID storeId();

    /** Người dùng hiện tại; ném lỗi nếu chưa đăng nhập. */
    UUID userId();

    Role role();

    Optional<UUID> storeIdIfPresent();

    Optional<UUID> userIdIfPresent();

    default boolean isOwner() {
        return role() == Role.OWNER;
    }
}
