package vn.nhom17.banhang.platform;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Chạy một đoạn code dưới ngữ cảnh một Cửa hàng mà không cần JWT. CHỈ dùng cho seed, test và đăng ký Cửa hàng
 * (AD-5 mục 7). Gọi bên ngoài transaction: hook của platform đặt {@code app.store_id} khi transaction bắt đầu.
 */
public final class TenantScope {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantScope() {
    }

    public record Scope(UUID storeId, UUID userId, Role role) {
    }

    public static <T> T runAs(UUID storeId, Supplier<T> work) {
        return runAs(storeId, null, Role.OWNER, work);
    }

    public static void runAs(UUID storeId, Runnable work) {
        runAs(storeId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T runAs(UUID storeId, UUID userId, Role role, Supplier<T> work) {
        Objects.requireNonNull(storeId, "storeId");
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(storeId, userId, role));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Dùng nội bộ bởi platform. */
    public static Scope current() {
        return CURRENT.get();
    }
}
