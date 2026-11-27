package vn.nhom17.banhang.platform;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/** Tiếp nhận sau ACK Kafka; request API không ghi primary trước khi worker nhận lệnh. */
public interface BackofficeWriteQueue {
    String STATUS_PATH = "/api/v1/platform/write-requests/";
    boolean enabled();
    Accepted submit(String action, UUID idempotencyKey, Object payload);

    record Accepted(UUID operationId, String status, String statusUrl) {
        public ResponseEntity<Accepted> response() {
            return ResponseEntity.accepted().location(URI.create(statusUrl)).body(this);
        }
    }

    /** UUID chuẩn; null được submit chuyển thành lỗi 422 như hợp đồng chứng từ cũ. */
    static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            UUID key = UUID.fromString(raw.strip());
            return key.toString().equalsIgnoreCase(raw.strip()) ? key : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Đường ghi trước đây không bắt buộc khóa vẫn nhận được caller cũ; giao diện mới luôn tự giữ khóa khi retry. */
    static UUID optionalKey(String raw) {
        return raw == null || raw.isBlank() ? UUID.randomUUID() : parseKey(raw);
    }
}
