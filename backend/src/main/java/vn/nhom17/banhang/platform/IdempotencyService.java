package vn.nhom17.banhang.platform;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Chống tạo trùng chứng từ (AD-7). Gọi từ controller, KHÔNG gọi bên trong một transaction khác: dịch vụ này tự mở
 * transaction, giữ khóa và chạy {@code create} trong cùng transaction đó.
 *
 * <ul>
 *   <li>Lần gửi đầu: chạy {@code create}, trả {@code replayed = false} (controller trả 201).</li>
 *   <li>Trùng khóa, cùng nội dung: trả chứng từ cũ qua {@code load}, {@code replayed = true}
 *       (controller trả 200 + header {@code Idempotent-Replay: true}).</li>
 *   <li>Trùng khóa, khác nội dung: ném 409 {@code /errors/common/idempotency-key-reuse}.</li>
 *   <li>Hai yêu cầu trùng đến cùng lúc: bắt lỗi unique, đọc lại bản đã thắng.</li>
 * </ul>
 */
public interface IdempotencyService {

    <T> IdempotentResult<T> execute(String documentType, UUID idempotencyKey, String requestHash,
                                    Supplier<UUID> create, Function<UUID, T> load);

    /** SHA-256 của nội dung request đã chuẩn hóa (JSON). */
    String hash(Object request);

    record IdempotentResult<T>(T value, boolean replayed) {
    }
}
