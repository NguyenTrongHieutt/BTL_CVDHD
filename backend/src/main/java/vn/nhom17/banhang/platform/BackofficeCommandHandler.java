package vn.nhom17.banhang.platform;

import java.util.Set;
import java.util.List;

/** Adapter do module chủ cung cấp. Payload chỉ chứa dữ liệu JSON hoặc tham chiếu tệp bền, không chứa JWT/tệp nhị phân. */
public interface BackofficeCommandHandler<T> {
    String action();
    Class<T> payloadType();
    default BackofficeCommandLane lane() { return BackofficeCommandLane.QUICK; }
    default Set<Role> allowedRoles() { return Set.of(Role.OWNER); }
    /** Thuần hàm; bỏ token preview hoặc metadata tạm khi hợp đồng chống trùng của nghiệp vụ không dùng chúng. */
    default Object fingerprint(T payload) { return payload; }
    /** Tệp thuộc chính yêu cầu này; platform chỉ giải phóng sau khi đã commit hoặc đọc được kết quả cuối. */
    default List<BackofficeFileStore.Reference> fileReferences(T payload) { return List.of(); }
    /** Worker gọi trong cùng transaction với kết quả cuối, dưới ngữ cảnh actor đã xác thực lúc gửi. */
    BackofficeCommandResult execute(T payload);
}
