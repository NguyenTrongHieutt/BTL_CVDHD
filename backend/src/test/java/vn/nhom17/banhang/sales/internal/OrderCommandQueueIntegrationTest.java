package vn.nhom17.banhang.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest(properties = {"app.sales.queue.publish-enabled=true", "app.sales.queue.consume-enabled=true",
        "app.sales.queue.auto-startup=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OrderCommandQueueIntegrationTest.KafkaConfiguration.class})
class OrderCommandQueueIntegrationTest {
    private static final String TOPIC = "sales.order-commands.v1";
    private static final String ORDERS = "/api/v1/sales/orders";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaContainer kafka;
    @Autowired JsonMapper json;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PostgreSQLContainer postgres;

    @AfterEach
    void stopWorker() {
        if (listeners.getListenerContainer("sales-order-worker") != null) {
            listeners.getListenerContainer("sales-order-worker").stop();
        }
    }

    @Test
    void acknowledgesTheBrokerBeforeAnyOrderIsWrittenAndCapturesOnlyTheAuthenticatedActor() throws Exception {
        Session owner = login("dev.chu.a");
        Session staff = login("dev.nv.a");
        UUID variant = product(owner, 10);
        UUID operation = UUID.randomUUID();
        String request = """
                {"lines":[{"variantId":"%s","quantity":1}],"paymentMethod":"CASH",
                 "cashReceived":15000,"quotedTotal":15000,"storeId":"%s","actorRole":"OWNER"}
                """.formatted(variant, UUID.randomUUID());

        mvc.perform(post(ORDERS).cookie(staff.cookie()).header("Idempotency-Key", operation)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.orderId").doesNotExist());

        assertThat(count(staff, "SELECT count(*) FROM app.sales_order_line WHERE variant_id = ?", variant)).isZero();
        ConsumerRecord<String, String> record = command(operation);
        assertThat(record.key()).isEqualTo(staff.storeId().toString());
        var wire = json.readTree(record.value());
        assertThat(wire.path("storeId").asText()).isEqualTo(staff.storeId().toString());
        assertThat(wire.path("actorId").asText()).isEqualTo(staff.userId().toString());
        assertThat(wire.path("actorRole").asText()).isEqualTo("STAFF");
        assertThat(record.value()).doesNotContain("access_token", "demo1234", "dev.nv.a", "displayName");
    }

    @Test
    void repeatedCommandsWriteTheOrderStockPaymentAndLoyaltyOnlyOnce() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        UUID variant = product(owner, 10);
        String customer = mvc.perform(post("/api/v1/catalog/customers").cookie(staff.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"0912345678\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(customer, "$.customerId"));
        UUID operation = UUID.randomUUID();
        String cart = cart(variant, 1, 15000);
        var request = json.readTree(cart).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) request).put("customerId", customerId.toString());
        for (int attempt = 0; attempt < 2; attempt++) {
            create(staff, operation, json.writeValueAsString(request)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.operationId").value(operation.toString()));
        }
        startAndDrain();

        assertThat(count(staff, "SELECT count(*) FROM app.sales_order_line WHERE variant_id = ?", variant)).isEqualTo(1);
        assertThat(count(staff, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(9);
        assertThat(count(staff, "SELECT count(*) FROM app.payment")).isEqualTo(1);
        assertThat(count(staff, "SELECT balance FROM app.loyalty_account WHERE customer_id = ?", customerId)).isEqualTo(1);
        assertThat(count(staff, "SELECT count(*) FROM app.loyalty_ledger WHERE customer_id = ?", customerId)).isEqualTo(1);
        assertThat(count(staff, "SELECT count(*) FROM app.sales_order WHERE created_by = ?", staff.userId())).isEqualTo(1);
    }

    @Test
    void terminalRejectionIsDurableAndDoesNotChangeStockPaymentOrLoyalty() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        UUID variant = product(owner, 1);
        UUID operation = UUID.randomUUID();
        create(staff, operation, cart(variant, 2, 30000)).andExpect(status().isAccepted());
        startAndDrain();

        mvc.perform(get("/api/v1/sales/order-requests/failures").cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].operationId").value(operation.toString()))
                .andExpect(jsonPath("$.items[0].reason").isString())
                .andExpect(jsonPath("$.items[0].request").doesNotExist());
        assertThat(count(staff, "SELECT count(*) FROM app.sales_order")).isZero();
        assertThat(count(staff, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(1);
        assertThat(count(staff, "SELECT count(*) FROM app.payment")).isZero();
        assertThat(count(staff, "SELECT count(*) FROM app.loyalty_ledger")).isZero();

        // Tồn đã đủ nhưng phát lại lệnh bị từ chối vẫn giữ kết quả cuối, không bán lại ngầm.
        inStore(staff, () -> jdbc.update("UPDATE app.stock SET quantity = 10 WHERE variant_id = ?", variant));
        create(staff, operation, cart(variant, 2, 30000)).andExpect(status().isAccepted());
        startAndDrain();
        assertThat(count(staff, "SELECT count(*) FROM app.sales_order")).isZero();
        assertThat(count(staff, "SELECT count(*) FROM app.sales_order_request_failure")).isEqualTo(1);
    }

    @Test
    void failureInboxIsLimitedToTheStoreAndTheStaffActor() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        Session colleague = newStaff(owner);
        Session foreignOwner = newStore();
        UUID variant = product(owner, 1);
        UUID ownOperation = UUID.randomUUID();
        UUID colleagueOperation = UUID.randomUUID();
        create(staff, ownOperation, cart(variant, 2, 30000)).andExpect(status().isAccepted());
        create(colleague, colleagueOperation, cart(variant, 2, 30000)).andExpect(status().isAccepted());
        startAndDrain();

        String failures = "/api/v1/sales/order-requests/failures";
        mvc.perform(get(failures)).andExpect(status().isUnauthorized());
        mvc.perform(get(failures).cookie(foreignOwner.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(failures).cookie(staff.cookie()).param("actorId", colleague.userId().toString())
                        .param("storeId", foreignOwner.storeId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].operationId").value(ownOperation.toString()));
        mvc.perform(get(failures).cookie(owner.cookie()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void theSameOperationKeyIsIsolatedByBothStoreAndActor() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        Session otherOwner = newStore();
        UUID variant = product(owner, 10);
        UUID otherVariant = product(otherOwner, 10);
        UUID operation = UUID.randomUUID();
        create(owner, operation, cart(variant, 1, 15000)).andExpect(status().isAccepted());
        create(staff, operation, cart(variant, 1, 15000)).andExpect(status().isAccepted());
        create(otherOwner, operation, cart(otherVariant, 1, 15000)).andExpect(status().isAccepted());
        startAndDrain();

        assertThat(count(owner, "SELECT count(*) FROM app.sales_order WHERE created_by = ?", owner.userId())).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM app.sales_order WHERE created_by = ?", staff.userId())).isEqualTo(1);
        assertThat(count(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(8);
        assertThat(count(otherOwner, "SELECT count(*) FROM app.sales_order")).isEqualTo(1);
        assertThat(count(otherOwner, "SELECT quantity FROM app.stock WHERE variant_id = ?", otherVariant)).isEqualTo(9);
    }

    @Test
    void aTransientPrimaryFailureRollsBackAllEffectsAndBlocksLaterCommandsForTheStore() throws Exception {
        Session owner = newStore();
        UUID variant = product(owner, 10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String sequence = "app.queue_attempt_" + suffix;
        String function = "app.queue_fail_payment_" + suffix;
        String trigger = "queue_fail_payment_" + suffix;
        try (var privileged = DriverManager.getConnection(postgres.getJdbcUrl(), "app_owner",
                TestcontainersConfiguration.OWNER_PASSWORD); var ddl = privileged.createStatement()) {
            ddl.execute("CREATE SEQUENCE " + sequence);
            // nextval sống qua rollback, chỉ dùng để xác nhận worker đã thật sự gặp lỗi ghi payment.
            ddl.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
                    SET search_path = app, pg_temp AS $$
                    BEGIN
                        IF NEW.store_id = '%s'::uuid THEN
                            PERFORM nextval('%s');
                            RAISE EXCEPTION 'transient primary test failure' USING ERRCODE = '40001';
                        END IF;
                        RETURN NEW;
                    END
                    $$
                    """.formatted(function, owner.storeId(), sequence));
            ddl.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON app.payment FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");
            try {
                create(owner, first, cart(variant, 1, 15000)).andExpect(status().isAccepted());
                create(owner, second, cart(variant, 2, 30000)).andExpect(status().isAccepted());
                ConsumerRecord<String, String> firstRecord = command(first);
                listeners.getListenerContainer("sales-order-worker").start();
                await().atMost(Duration.ofSeconds(15)).until(() -> {
                    try (var attempts = ddl.executeQuery("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM " + sequence)) {
                        return attempts.next() && attempts.getLong(1) >= 2;
                    }
                });
                assertThat(count(owner, "SELECT count(*) FROM app.sales_order")).isZero();
                assertThat(count(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(10);
                assertThat(count(owner, "SELECT count(*) FROM app.payment")).isZero();
                assertThat(count(owner, "SELECT count(*) FROM app.sales_order_request_failure")).isZero();
                try (Admin admin = Admin.create(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
                    var offset = admin.listConsumerGroupOffsets("sales-order-worker-v1").partitionsToOffsetAndMetadata()
                            .get().get(new org.apache.kafka.common.TopicPartition(TOPIC, firstRecord.partition()));
                    assertThat(offset == null || offset.offset() <= firstRecord.offset()).isTrue();
                }
            } finally {
                ddl.execute("DROP TRIGGER " + trigger + " ON app.payment");
                ddl.execute("DROP FUNCTION " + function + "()");
                ddl.execute("DROP SEQUENCE " + sequence);
            }
        }
        startAndDrain();
        assertThat(count(owner, "SELECT count(*) FROM app.sales_order")).isEqualTo(2);
        assertThat(count(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variant)).isEqualTo(7);
        assertThat(count(owner, "SELECT count(*) FROM app.payment")).isEqualTo(2);
        assertThat(count(owner, "SELECT total_amount FROM app.sales_order ORDER BY order_code LIMIT 1")).isEqualTo(15000);
    }

    private void startAndDrain() {
        listeners.getListenerContainer("sales-order-worker").start();
        try (Admin admin = Admin.create(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var ends = admin.listOffsets(admin.describeTopics(java.util.List.of(TOPIC)).allTopicNames().get()
                    .get(TOPIC).partitions().stream().collect(java.util.stream.Collectors.toMap(
                            part -> new org.apache.kafka.common.TopicPartition(TOPIC, part.partition()),
                            part -> org.apache.kafka.clients.admin.OffsetSpec.latest())))
                    .all().get();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets("sales-order-worker-v1")
                        .partitionsToOffsetAndMetadata().get();
                for (var end : ends.entrySet()) {
                    if (end.getValue().offset() > 0) {
                        assertThat(committed.get(end.getKey())).isNotNull();
                        assertThat(committed.get(end.getKey()).offset()).isGreaterThanOrEqualTo(end.getValue().offset());
                    }
                }
            });
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        stopWorker();
    }

    private org.springframework.test.web.servlet.ResultActions create(Session session, UUID operation, String request)
            throws Exception {
        return mvc.perform(post(ORDERS).cookie(session.cookie()).header("Idempotency-Key", operation)
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }

    private String cart(UUID variant, int quantity, long total) {
        return """
                {"lines":[{"variantId":"%s","quantity":%d}],"paymentMethod":"CASH",
                 "cashReceived":%d,"quotedTotal":%d}
                """.formatted(variant, quantity, total, total);
    }

    private ConsumerRecord<String, String> command(UUID operation) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "sales-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, String>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(java.util.List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.value().contains(operation.toString())) return record;
                }
            }
        }
        throw new AssertionError("Broker không có lệnh đã trả ACCEPTED: " + operation);
    }

    private UUID product(Session owner, int openingQuantity) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/products").cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hàng thử Kafka %s","salePrice":15000,
                                 "openingQuantity":%d,"openingUnitCost":10000}
                                """.formatted(UUID.randomUUID(), openingQuantity)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.variants[0].id"));
    }

    private long count(Session session, String sql, Object... args) {
        return inStore(session, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    private <T> T inStore(Session session, Supplier<T> work) {
        return TenantScope.runAs(session.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> work.get()));
    }

    private Session newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "storeName", "Cửa hàng thử Kafka", "displayName", "Chủ thử",
                                "loginKey", "queue." + UUID.randomUUID().toString().replace("-", "") + "@test.vn",
                                "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return new Session(response.getCookie("access_token"),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.storeId")),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.userId")));
    }

    private Session newStaff(Session owner) throws Exception {
        String loginKey = "queue." + UUID.randomUUID().toString().replace("-", "");
        String body = mvc.perform(post("/api/v1/platform/users").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "loginKey", loginKey, "displayName", "Nhân viên thử", "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", "matkhau123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        return new Session(cookie, owner.storeId(), UUID.fromString(JsonPath.read(body, "$.userId")));
    }

    private Session login(String loginKey) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"%s\",\"password\":\"demo1234\"}".formatted(loginKey)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(cookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(cookie, UUID.fromString(JsonPath.read(me, "$.storeId")),
                UUID.fromString(JsonPath.read(me, "$.userId")));
    }

    private record Session(Cookie cookie, UUID storeId, UUID userId) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConfiguration {
        @Bean
        NewTopic salesCommandsTopic() {
            return new NewTopic(TOPIC, 3, (short) 1);
        }

        @Bean
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka:4.1.1");
        }

        @Bean
        DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
            return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        }
    }
}
