package vn.nhom17.banhang.platform;

import java.util.List;

/**
 * Phản hồi phân trang chung (AD-19): {@code page} bắt đầu từ 0, {@code size} mặc định 20, tối đa 100.
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /** Chuẩn hóa {@code size} từ request. */
    public static int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
