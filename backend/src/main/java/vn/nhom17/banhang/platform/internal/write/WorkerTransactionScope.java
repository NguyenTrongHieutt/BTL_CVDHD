package vn.nhom17.banhang.platform.internal.write;

import java.util.function.Supplier;

/** Chỉ worker đã khóa chống trùng lệnh được cho phép IdempotencyService tham gia transaction bao ngoài. */
public final class WorkerTransactionScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private WorkerTransactionScope() {}
    public static boolean active() { return Boolean.TRUE.equals(ACTIVE.get()); }
    static <T> T run(Supplier<T> work) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return work.get();
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
}
