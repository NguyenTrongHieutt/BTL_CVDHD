package vn.nhom17.banhang.platform.internal.write;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TenantScope;

@Component
class BackofficeCommandProcessor {
    private static final Logger log = LoggerFactory.getLogger(BackofficeCommandProcessor.class);
    private final BackofficeHandlers handlers;
    private final BackofficeWriteRequests requests;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BackofficeFileStore files;

    BackofficeCommandProcessor(BackofficeHandlers handlers, BackofficeWriteRequests requests,
            IdempotencyService idempotency, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            BackofficeFileStore files) {
        this.handlers = handlers;
        this.requests = requests;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.files = files;
    }

    void process(BackofficeCommand command, BackofficeCommandLane lane) {
        Objects.requireNonNull(command.storeId(), "storeId");
        Objects.requireNonNull(command.actorId(), "actorId");
        Objects.requireNonNull(command.idempotencyKey(), "idempotencyKey");
        var handler = handlers.require(command.action());
        if (handler.lane() != lane) throw new IllegalArgumentException("Back-office command is in the wrong lane");
        Object payload = handlers.read(handler, command.payload());
        String hash = idempotency.hash(handlers.fingerprint(handler, payload));
        if (!hash.equals(command.requestHash()) || !KafkaBackofficeWriteQueue.operationId(command.storeId(),
                command.actorId(), command.action(), command.idempotencyKey(), hash).equals(command.operationId())) {
            throw new IllegalArgumentException("Back-office command identity does not match its payload");
        }
        TenantScope.runAs(command.storeId(), command.actorId(), command.actorRole(), () -> {
            try {
                tx.executeWithoutResult(transaction -> {
                    requests.lock(command);
                    if (requests.find(command.operationId()) != null) {
                        releaseAfterCommit(command, handler, payload);
                        return;
                    }
                    if (requests.reused(command)) throw reused();
                    BackofficeHandlers.authorize(handler, command.actorRole());
                    if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM app.app_user WHERE store_id = ? AND id = ?
                                AND role = ? AND status = 'ACTIVE')
                            """, Boolean.class, command.storeId(), command.actorId(), command.actorRole().name()))) {
                        throw new ProblemException(HttpStatus.FORBIDDEN, "/errors/common/forbidden", "Không có quyền",
                                "Tài khoản đã bị khóa hoặc quyền thực hiện thao tác đã thay đổi.");
                    }
                    var result = WorkerTransactionScope.run(() -> handlers.execute(handler, payload));
                    requests.succeed(command, result);
                    releaseAfterCommit(command, handler, payload);
                });
            } catch (ProblemException rejection) {
                if (!rejection.status().is4xxClientError()) throw rejection;
                // Transaction nghiệp vụ đã rollback hoàn toàn; chỉ kết quả lỗi được commit ở transaction mới.
                tx.executeWithoutResult(transaction -> {
                    requests.lock(command);
                    if (requests.find(command.operationId()) == null) {
                        requests.fail(command, requests.reused(command) ? reused() : rejection);
                    }
                    releaseAfterCommit(command, handler, payload);
                });
            }
            return null;
        });
    }

    private void releaseAfterCommit(BackofficeCommand command, BackofficeCommandHandler<?> handler, Object payload) {
        if (handler.lane() != BackofficeCommandLane.FILE) return;
        for (var reference : handlers.fileReferences(handler, payload)) {
            // Một lệnh cũ có thể chứa tham chiếu khác nhưng cùng fingerprint nghiệp vụ. Không dọn tệp của khóa khác.
            if (reference == null || !command.storeId().equals(reference.storeId())
                    || !command.actorId().equals(reference.userId()) || !command.action().equals(reference.action())
                    || !command.idempotencyKey().equals(reference.requestKey())) {
                log.warn("Không dọn tham chiếu file không thuộc lệnh back-office {}", command.operationId());
                continue;
            }
            try {
                files.discardAfterCommit(command.action(), reference);
            } catch (ProblemException invalidReference) {
                // Tham chiếu không xác minh được không được xóa và không được làm mất kết quả cuối đã chuẩn bị.
                log.warn("Không đăng ký dọn file của lệnh {}: {}", command.operationId(), invalidReference.type());
            }
        }
    }

    private static ProblemException reused() {
        return ProblemException.conflict("/errors/common/idempotency-key-reuse", "Idempotency-Key đã dùng",
                "Khóa này đã dùng cho một yêu cầu có nội dung khác. Hãy tạo khóa mới.");
    }
}
