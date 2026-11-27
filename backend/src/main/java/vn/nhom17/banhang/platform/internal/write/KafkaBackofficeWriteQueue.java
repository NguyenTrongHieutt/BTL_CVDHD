package vn.nhom17.banhang.platform.internal.write;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

@Component
class KafkaBackofficeWriteQueue implements BackofficeWriteQueue {
    private final ObjectProvider<KafkaTemplate<String, String>> kafka;
    private final BackofficeHandlers handlers;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final JsonMapper json;
    private final boolean enabled;
    private final String topic;
    private final String fileTopic;
    private final long timeoutMillis;

    KafkaBackofficeWriteQueue(@Qualifier("backofficeKafkaTemplate") ObjectProvider<KafkaTemplate<String, String>> kafka,
            BackofficeHandlers handlers, CurrentContext context, IdempotencyService idempotency, JsonMapper json,
            @Value("${app.backoffice.queue.publish-enabled:false}") boolean enabled,
            @Value("${app.backoffice.queue.topic:backoffice.write-commands.v1}") String topic,
            @Value("${app.backoffice.queue.file-topic:backoffice.file-commands.v1}") String fileTopic,
            @Value("${app.backoffice.queue.publish-timeout-ms:10000}") long timeoutMillis) {
        this.kafka = kafka;
        this.handlers = handlers;
        this.context = context;
        this.idempotency = idempotency;
        this.json = json;
        this.enabled = enabled;
        this.topic = topic;
        this.fileTopic = fileTopic;
        this.timeoutMillis = timeoutMillis;
    }

    @Override public boolean enabled() { return enabled; }

    @Override
    public UUID operation(String action, UUID key, Object payload) {
        return command(action, key, payload).operationId();
    }

    private BackofficeCommand command(String action, UUID key, Object payload) {
        if (!enabled) throw new IllegalStateException("Back-office queue publishing is disabled");
        if (key == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
        }
        var handler = handlers.require(action);
        BackofficeHandlers.authorize(handler, context.role());
        if (payload == null || !handler.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Wrong payload type for " + action);
        }
        String hash = idempotency.hash(handlers.fingerprint(handler, payload));
        UUID operation = operationId(context.storeId(), context.userId(), action, key, hash);
        return new BackofficeCommand(operation, context.storeId(), context.userId(), context.role(), key,
                action, hash, json.valueToTree(payload));
    }

    @Override
    public Accepted submit(String action, UUID key, Object payload) {
        var command = command(action, key, payload);
        var handler = handlers.require(action);
        String wire = json.writeValueAsString(command);
        if (wire.getBytes(StandardCharsets.UTF_8).length > 900_000) {
            throw new ProblemException(HttpStatus.PAYLOAD_TOO_LARGE, "/errors/common/payload-too-large",
                    "Dữ liệu quá lớn", "Thu gọn yêu cầu trước khi gửi lại.");
        }
        try {
            kafka.getObject().send(handler.lane() == BackofficeCommandLane.FILE ? fileTopic : topic,
                    context.storeId().toString(), wire).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException | org.springframework.kafka.KafkaException
                 | org.apache.kafka.common.KafkaException failure) {
            throw unavailable();
        }
        return new Accepted(command.operationId(), "ACCEPTED", STATUS_PATH + command.operationId());
    }

    static UUID operationId(UUID store, UUID actor, String action, UUID key, String hash) {
        return UUID.nameUUIDFromBytes((store + ":" + actor + ":" + action + ":" + key + ":" + hash)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static ProblemException unavailable() {
        return new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/errors/platform/write-queue-unavailable",
                "Chưa xác nhận tiếp nhận yêu cầu",
                "Chưa nhận được xác nhận từ hàng đợi. Giữ nguyên dữ liệu và mã yêu cầu để thử lại.");
    }
}
