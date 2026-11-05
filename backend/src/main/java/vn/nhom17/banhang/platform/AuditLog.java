package vn.nhom17.banhang.platform;

import java.util.Map;

/**
 * Ghi Nhật ký thao tác trong CÙNG transaction với thao tác (Consistency Conventions; FR-49).
 *
 * <p>{@code before} và {@code after} là map đã lọc trường, tối đa 8 KB, không chứa mật khẩu hay dữ liệu nhạy cảm.
 */
public interface AuditLog {

    void record(AuditAction action, String entityId, Map<String, ?> before, Map<String, ?> after);
}
