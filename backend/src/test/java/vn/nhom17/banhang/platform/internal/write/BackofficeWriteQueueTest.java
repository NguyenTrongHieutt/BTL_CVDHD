package vn.nhom17.banhang.platform.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RoundRobinPartitioner;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;

class BackofficeWriteQueueTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final FixedContext context = new FixedContext(UUID.randomUUID(), UUID.randomUUID(), Role.OWNER);

    @Test
    void waitsForAllBrokerAcknowledgmentAndRetriesTheSameRequestIdentity() throws Exception {
        var broker = broker(false);
        var queue = queue(broker, context, new Handler(BackofficeCommandLane.QUICK), 5000);
        UUID key = UUID.randomUUID();
        var payload = new Payload("same business value", "preview-one");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> queue.submit("TEST_WRITE", key, payload));
            await().atMost(Duration.ofSeconds(2)).until(() -> broker.history().size() == 1);
            assertThat(pending.isDone()).isFalse();
            var command = json.readTree(broker.history().getFirst().value());
            assertThat(broker.history().getFirst().key()).isEqualTo(context.storeId().toString());
            assertThat(command.path("actorId").asText()).isEqualTo(context.userId().toString());
            assertThat(command.path("actorRole").asText()).isEqualTo("OWNER");
            assertThat(command.toString()).doesNotContain("access_token", "jwt", "password");
            broker.completeNext();
            var accepted = pending.get(2, TimeUnit.SECONDS);
            assertThat(accepted.status()).isEqualTo("ACCEPTED");
            assertThat(accepted.statusUrl()).isEqualTo("/api/v1/platform/write-requests/" + accepted.operationId());
            assertThat(accepted.response().getStatusCode().value()).isEqualTo(202);
            assertThat(accepted.response().getHeaders().getLocation().toString()).isEqualTo(accepted.statusUrl());
            var retry = executor.submit(() -> queue.submit("TEST_WRITE", key, new Payload("same business value", "preview-two")));
            await().atMost(Duration.ofSeconds(2)).until(() -> broker.history().size() == 2);
            broker.completeNext();
            assertThat(retry.get(2, TimeUnit.SECONDS).operationId()).isEqualTo(accepted.operationId());
        }
    }

    @Test
    void choosesAnIndependentFileTopicAndPayloadFingerprint() {
        var broker = broker(true);
        var queue = queue(broker, context, new Handler(BackofficeCommandLane.FILE), 1000);
        UUID key = UUID.randomUUID();
        var first = queue.submit("TEST_WRITE", key, new Payload("one", "preview-one"));
        var retry = queue.submit("TEST_WRITE", key, new Payload("one", "preview-two"));
        var changed = queue.submit("TEST_WRITE", key, new Payload("two", "preview-three"));
        assertThat(first.operationId()).isEqualTo(retry.operationId()).isNotEqualTo(changed.operationId());
        assertThat(broker.history()).allSatisfy(record -> assertThat(record.topic()).isEqualTo("files"));
    }

    @Test
    void unknownMissingKeyAndUnauthorizedActionsNeverReachKafka() {
        var broker = broker(true);
        var handler = new Handler(BackofficeCommandLane.QUICK);
        var queue = queue(broker, context, handler, 1000);
        assertThatThrownBy(() -> queue.submit("UNKNOWN", UUID.randomUUID(), new Payload("a", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.submit("TEST_WRITE", null, new Payload("a", null)))
                .isInstanceOfSatisfying(ProblemException.class,
                        error -> assertThat(error.status().value()).isEqualTo(422));
        var staffQueue = queue(broker, new FixedContext(context.storeId(), context.userId(), Role.STAFF), handler, 1000);
        assertThatThrownBy(() -> staffQueue.submit("TEST_WRITE", UUID.randomUUID(), new Payload("a", null)))
                .isInstanceOfSatisfying(ProblemException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(broker.history()).isEmpty();
    }

    @Test
    void anUnconfirmedPublishReturnsARetryableProblemAndRetainsTheIdentity() {
        var broker = broker(false);
        var queue = queue(broker, context, new Handler(BackofficeCommandLane.QUICK), 20);
        var payload = new Payload("one", null);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> queue.submit("TEST_WRITE", key, payload)).isInstanceOfSatisfying(ProblemException.class,
                error -> assertThat(error.type()).isEqualTo("/errors/platform/write-queue-unavailable"));
        assertThatThrownBy(() -> queue.submit("TEST_WRITE", key, payload)).isInstanceOf(ProblemException.class);
        assertThat(json.readTree(broker.history().getFirst().value()).path("operationId").asText())
                .isEqualTo(json.readTree(broker.history().getLast().value()).path("operationId").asText());
    }

    @Test
    void environmentCannotLowerTheProducerDurabilitySettings() {
        var props = new KafkaProperties();
        props.getProducer().setAcks("0");
        var producer = new BackofficeQueueConfiguration.Publisher().backofficeProducerFactory(props, 1000,
                new StaticListableBeanFactory().getBeanProvider(DefaultKafkaProducerFactoryCustomizer.class));
        try {
            assertThat(producer.getConfigurationProperties()).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        } finally {
            producer.destroy();
        }
    }

    private MockProducer<String, String> broker(boolean autoComplete) {
        return new MockProducer<>(autoComplete, new RoundRobinPartitioner(), new StringSerializer(), new StringSerializer()) {
            @Override public void close() {}
            @Override public void close(Duration timeout) {}
        };
    }

    @SuppressWarnings("unchecked")
    private KafkaBackofficeWriteQueue queue(MockProducer<String, String> broker, FixedContext caller,
            Handler handler, long timeout) {
        ObjectProvider<BackofficeCommandHandler<?>> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(handler));
        ObjectProvider<KafkaTemplate<String, String>> kafka = mock(ObjectProvider.class);
        when(kafka.getObject()).thenReturn(new KafkaTemplate<>(() -> broker));
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.hash(any())).thenAnswer(invocation -> HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(invocation.getArgument(0)).getBytes(StandardCharsets.UTF_8))));
        return new KafkaBackofficeWriteQueue(kafka, new BackofficeHandlers(providers, json), caller, idempotency,
                json, true, "quick", "files", timeout);
    }

    record Payload(String value, String previewToken) {}
    record Handler(BackofficeCommandLane lane) implements BackofficeCommandHandler<Payload> {
        public String action() { return "TEST_WRITE"; }
        public Class<Payload> payloadType() { return Payload.class; }
        public Object fingerprint(Payload payload) { return payload.value(); }
        public BackofficeCommandResult execute(Payload payload) { return BackofficeCommandResult.ok(payload); }
    }
    record FixedContext(UUID storeId, UUID userId, Role role) implements CurrentContext {
        public Optional<UUID> storeIdIfPresent() { return Optional.of(storeId); }
        public Optional<UUID> userIdIfPresent() { return Optional.of(userId); }
    }
}
