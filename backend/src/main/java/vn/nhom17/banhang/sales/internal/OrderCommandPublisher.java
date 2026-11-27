package vn.nhom17.banhang.sales.internal;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

@Component
@ConditionalOnProperty(name = "app.sales.queue.publish-enabled", havingValue = "true")
class OrderCommandPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final CurrentContext context;
    private final JsonMapper json;
    private final String topic;
    private final long timeoutMillis;

    OrderCommandPublisher(@Qualifier("salesOrderKafkaTemplate") KafkaTemplate<String, String> kafka,
                          CurrentContext context, JsonMapper json,
                          @Value("${app.sales.queue.topic:sales.order-commands.v1}") String topic,
                          @Value("${app.sales.queue.publish-timeout-ms:10000}") long timeoutMillis) {
        this.kafka = kafka;
        this.context = context;
        this.json = json;
        this.topic = topic;
        this.timeoutMillis = timeoutMillis;
    }

    record Accepted(UUID operationId, String status) {}

    Accepted publish(UUID operationId, OrderController.CreateOrder request) {
        if (operationId == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Request tạo chứng từ phải có header Idempotency-Key (UUID).");
        }
        var command = new OrderCommand(operationId, context.storeId(), context.userId(), context.role(),
                OrderService.canonical(request));
        try {
            // Future hoàn thành sau ACK acks=all; đường này chưa mở giao dịch tạo đơn ở primary.
            kafka.send(topic, command.storeId().toString(), json.writeValueAsString(command))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            return new Accepted(operationId, "ACCEPTED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException | KafkaException | org.apache.kafka.common.KafkaException failure) {
            // ACK có thể đã đến broker nhưng chưa đến API. Không tạo khóa khác khi thử lại.
            throw unavailable();
        }
    }

    private static ProblemException unavailable() {
        return new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/errors/sales/queue-unavailable",
                "Chưa xác nhận tiếp nhận đơn",
                "Chưa nhận được xác nhận từ hàng đợi. Giữ nguyên giỏ hàng và mã yêu cầu để thử lại.");
    }
}
