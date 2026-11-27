package vn.nhom17.banhang.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.RoundRobinPartitioner;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;

class OrderCommandPublisherTest {
    private final FixedContext context = new FixedContext(UUID.randomUUID(), UUID.randomUUID());
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void doesNotReturnAcceptedUntilTheBrokerCallbackConfirmsTheSend() throws Exception {
        var broker = new MockProducer<String, String>(false, new RoundRobinPartitioner(), new StringSerializer(), new StringSerializer());
        var publisher = new OrderCommandPublisher(new KafkaTemplate<>(() -> broker), context, json, "orders", 5000);
        UUID operation = UUID.randomUUID();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var response = executor.submit(() -> publisher.publish(operation, request()));
            await().atMost(Duration.ofSeconds(2)).until(() -> broker.history().size() == 1);
            assertThat(response.isDone()).isFalse();
            broker.completeNext();
            assertThat(response.get(2, TimeUnit.SECONDS)).isEqualTo(new OrderCommandPublisher.Accepted(operation, "ACCEPTED"));
        }
    }

    @Test
    void anUnknownResultKeepsTheOperationAndNeverPretendsTheCommandWasAccepted() {
        var broker = new MockProducer<String, String>(false, new RoundRobinPartitioner(), new StringSerializer(), new StringSerializer());
        var publisher = new OrderCommandPublisher(new KafkaTemplate<>(() -> broker), context, json, "orders", 20);
        UUID operation = UUID.randomUUID();
        assertThatThrownBy(() -> publisher.publish(operation, request())).isInstanceOfSatisfying(ProblemException.class,
                problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(problem.type()).isEqualTo("/errors/sales/queue-unavailable");
                });
        assertThat(json.readTree(broker.history().getFirst().value()).path("operationId").asText())
                .isEqualTo(operation.toString());
        // Broker ACK đến trễ không thể biến HTTP 503 đã trả thành một lần bán với mã khác.
        broker.completeNext();
    }

    @Test
    void anUnavailableBrokerReturnsASafeRetryableError() {
        var kafka = new KafkaProperties();
        kafka.setBootstrapServers(List.of("127.0.0.1:1"));
        var factory = new OrderQueueConfiguration.Publisher().salesOrderProducerFactory(kafka, 300);
        try {
            var publisher = new OrderCommandPublisher(new KafkaTemplate<>(factory), context, json, "orders", 300);
            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), request()))
                    .isInstanceOfSatisfying(ProblemException.class, problem -> {
                        assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(problem.getMessage()).contains("mã yêu cầu").doesNotContain("127.0.0.1", "TimeoutException");
                    });
        } finally {
            factory.destroy();
        }
    }

    @Test
    void refusesAMissingOperationKeyBeforeSending() {
        var broker = new MockProducer<String, String>(true, new RoundRobinPartitioner(), new StringSerializer(), new StringSerializer());
        var publisher = new OrderCommandPublisher(new KafkaTemplate<>(() -> broker), context, json, "orders", 1000);
        assertThatThrownBy(() -> publisher.publish(null, request())).isInstanceOfSatisfying(ProblemException.class,
                problem -> assertThat(problem.type()).isEqualTo("/errors/common/idempotency-key-missing"));
        assertThat(broker.history()).isEmpty();
    }

    private OrderController.CreateOrder request() {
        return new OrderController.CreateOrder(List.of(new QuoteController.LineRequest(UUID.randomUUID(), BigDecimal.ONE, null)),
                null, "CASH", BigDecimal.valueOf(15000), BigDecimal.valueOf(15000), null, null);
    }

    private record FixedContext(UUID storeId, UUID userId) implements CurrentContext {
        @Override public Role role() { return Role.STAFF; }
        @Override public Optional<UUID> storeIdIfPresent() { return Optional.of(storeId); }
        @Override public Optional<UUID> userIdIfPresent() { return Optional.of(userId); }
    }
}
