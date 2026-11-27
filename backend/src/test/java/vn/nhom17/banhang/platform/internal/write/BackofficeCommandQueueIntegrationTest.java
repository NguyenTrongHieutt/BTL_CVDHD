package vn.nhom17.banhang.platform.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest(properties = {"app.backoffice.queue.publish-enabled=true", "app.backoffice.queue.consume-enabled=true",
        "app.backoffice.queue.file-consume-enabled=true", "app.backoffice.queue.auto-startup=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, BackofficeCommandQueueIntegrationTest.KafkaConfiguration.class})
class BackofficeCommandQueueIntegrationTest {
    private static final String TOPIC = "backoffice.write-commands.v1";
    private static final String PRODUCTS = "/api/v1/catalog/products";
    private static final String CUSTOMERS = "/api/v1/catalog/customers";
    private static final String FILE_TOPIC = "backoffice.file-commands.v1";
    private static final String CSV = "Tên Sản phẩm,Giá bán\nHàng nhập từ Kafka,15000\n";
    @TempDir static Path fileDirectory;

    @DynamicPropertySource
    static void fileStorage(DynamicPropertyRegistry registry) {
        registry.add("app.backoffice.files.directory", () -> fileDirectory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaContainer kafka;
    @Autowired PostgreSQLContainer postgres;
    @Autowired JsonMapper json;
    @Autowired BackofficeCommandProcessor processor;
    @Autowired BackofficeResultPruner pruner;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @AfterEach
    void stopWorkers() {
        listeners.getListenerContainers().forEach(container -> container.stop());
    }

    @Test
    void brokerAcceptsBeforePrimaryWritesAndOnlyTheAuthenticatedActorCanReadTheResult() throws Exception {
        Session owner = newStore();
        Session staff = newStaff(owner);
        Session colleague = newStaff(owner);
        UUID key = UUID.randomUUID();
        String body = "{\"phone\":\"0912345678\",\"name\":\"Khách Kafka\",\"actorRole\":\"OWNER\"}";
        UUID operation = accepted(staff, "POST", CUSTOMERS, key, body);
        assertThat(accepted(staff, "POST", CUSTOMERS, key, body)).isEqualTo(operation);
        assertThat(count(owner, "SELECT count(*) FROM app.customer")).isZero();
        assertThat(count(owner, "SELECT count(*) FROM app.backoffice_write_request")).isZero();
        writeStatus(staff, operation).andExpect(jsonPath("$.status").value("PENDING"));

        var command = command(operation);
        assertThat(command.actorId()).isEqualTo(staff.userId());
        assertThat(command.actorRole().name()).isEqualTo("STAFF");
        assertThat(json.writeValueAsString(command)).doesNotContain("access_token", "jwt", "password");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> processor.process(command, BackofficeCommandLane.QUICK));
            var duplicate = executor.submit(() -> processor.process(command, BackofficeCommandLane.QUICK));
            first.get(15, TimeUnit.SECONDS);
            duplicate.get(15, TimeUnit.SECONDS);
        }
        assertThat(count(owner, "SELECT count(*) FROM app.customer")).isEqualTo(1);
        writeStatus(staff, operation).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.responseStatus").value(201))
                .andExpect(jsonPath("$.result.phone").value("0912345678"))
                .andExpect(jsonPath("$.responseHeaders.Location").isString());
        for (Session other : List.of(owner, colleague, newStore())) {
            writeStatus(other, operation).andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.result").doesNotExist());
        }
        mvc.perform(get("/api/v1/platform/write-requests/" + operation)).andExpect(status().isUnauthorized());
        mvc.perform(post(PRODUCTS).cookie(staff.cookie()).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cấm\",\"salePrice\":1}"))
                .andExpect(status().isForbidden());

        UUID changed = accepted(staff, "POST", CUSTOMERS, key, "{\"phone\":\"0987654321\"}");
        assertThat(changed).isNotEqualTo(operation);
        processor.process(command(changed), BackofficeCommandLane.QUICK);
        writeStatus(staff, changed).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.problem.type").value("/errors/common/idempotency-key-reuse"));
        assertThat(count(owner, "SELECT count(*) FROM app.customer")).isEqualTo(1);
    }

    @Test
    void productVariantCustomerAndInventoryWritesKeepTheirOriginalResultsAndDeduplicateEffects() throws Exception {
        Session owner = newStore();
        JsonNode product = run(owner, "POST", PRODUCTS, """
                {"name":"Hàng Kafka","salePrice":15000,"openingQuantity":5,"openingUnitCost":10000}
                """, 201).path("result");
        UUID productId = UUID.fromString(product.path("id").asText());
        UUID variantId = UUID.fromString(product.path("variants").get(0).path("id").asText());
        run(owner, "PUT", PRODUCTS + "/" + productId, "{\"name\":\"Hàng đổi tên\",\"salePrice\":16000}", 200);
        JsonNode expanded = run(owner, "POST", PRODUCTS + "/" + productId + "/variants", """
                {"existingVariantName":"Gốc","name":"Mới","salePrice":17000,"openingQuantity":2,"openingUnitCost":11000}
                """, 201).path("result");
        String secondVariant = null;
        for (var variant : expanded.path("variants")) {
            if (!variant.path("id").asText().equals(variantId.toString())) secondVariant = variant.path("id").asText();
        }
        assertThat(secondVariant).isNotNull();
        run(owner, "PUT", PRODUCTS + "/" + productId + "/variants/" + secondVariant,
                "{\"name\":\"Mới sửa\",\"salePrice\":18000}", 200);
        run(owner, "POST", PRODUCTS + "/" + productId + "/variants/" + secondVariant + "/deactivate", "{}", 200);
        run(owner, "PUT", "/api/v1/inventory/stock/" + variantId + "/min-quantity", "{\"minQuantity\":6}", 204);
        String supplierBody = mvc.perform(post("/api/v1/catalog/suppliers").cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nhà cung cấp thử\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID supplierId = UUID.fromString(json.readTree(supplierBody).path("supplierId").asText());
        run(owner, "POST", "/api/v1/inventory/purchase-receipts", """
                {"supplierId":"%s","lines":[{"variantId":"%s","quantity":4,"unitCost":12000}]}
                """.formatted(supplierId, variantId), 201);
        assertThat(count(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variantId)).isEqualTo(9);
        run(owner, "POST", "/api/v1/inventory/stocktakes", """
                {"lines":[{"variantId":"%s","systemQuantity":9,"countedQuantity":7,"reason":"Hàng hỏng"}]}
                """.formatted(variantId), 201);
        assertThat(count(owner, "SELECT quantity FROM app.stock WHERE variant_id = ?", variantId)).isEqualTo(7);
        assertThat(count(owner, "SELECT min_quantity FROM app.stock WHERE variant_id = ?", variantId)).isEqualTo(6);
        assertThat(count(owner, "SELECT count(*) FROM app.purchase_receipt")).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM app.stocktake")).isEqualTo(1);
        run(owner, "POST", PRODUCTS + "/" + productId + "/deactivate", "{}", 200);

        JsonNode customer = run(owner, "POST", CUSTOMERS, "{\"phone\":\"0911111111\"}", 201).path("result");
        run(owner, "PUT", CUSTOMERS + "/" + customer.path("customerId").asText(),
                "{\"phone\":\"0911111111\",\"name\":\"Đã sửa\"}", 200);
    }

    @Test
    void domainFailureRollsBackBeforeItsDurableProblemIsStoredAndRemainsTerminal() throws Exception {
        Session owner = newStore();
        JsonNode customer = run(owner, "POST", CUSTOMERS, "{\"phone\":\"0922222222\"}", 201).path("result");
        UUID operation = accepted(owner, "POST", CUSTOMERS, UUID.randomUUID(), "{\"phone\":\"0922222222\"}");
        var command = command(operation);
        processor.process(command, BackofficeCommandLane.QUICK);
        processor.process(command, BackofficeCommandLane.QUICK);
        writeStatus(owner, operation).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.responseStatus").value(409))
                .andExpect(jsonPath("$.problem.type").value("/errors/catalog/customer-phone-taken"))
                .andExpect(jsonPath("$.problem.existingCustomerId").value(customer.path("customerId").asText()));
        assertThat(count(owner, "SELECT count(*) FROM app.customer")).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM app.backoffice_write_request WHERE operation_id = ?", operation)).isEqualTo(1);
    }

    @Test
    void aResultCommitFailureRollsBackBusinessEffectsAndRetryCanCompleteExactlyOnce() throws Exception {
        Session owner = newStore();
        UUID operation = accepted(owner, "POST", PRODUCTS, UUID.randomUUID(),
                "{\"name\":\"Atomic result\",\"salePrice\":15000,\"openingQuantity\":3,\"openingUnitCost\":10000}");
        var command = command(operation);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String trigger = "backoffice_result_failure_" + suffix;
        String function = "app." + trigger;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var ddl = connection.createStatement()) {
            ddl.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.store_id = '%s'::uuid THEN
                            RAISE EXCEPTION 'transient result failure' USING ERRCODE = '40001';
                        END IF;
                        RETURN NEW;
                    END $$
                    """.formatted(function, owner.storeId()));
            ddl.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON app.backoffice_write_request FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");
            try {
                assertThatThrownBy(() -> processor.process(command, BackofficeCommandLane.QUICK)).isInstanceOf(RuntimeException.class);
                assertThat(count(owner, "SELECT count(*) FROM app.product")).isZero();
                assertThat(count(owner, "SELECT count(*) FROM app.stock")).isZero();
                assertThat(count(owner, "SELECT count(*) FROM app.idempotency_record WHERE idempotency_key = ?",
                        command.idempotencyKey())).isZero();
                assertThat(count(owner, "SELECT count(*) FROM app.backoffice_write_request")).isZero();
            } finally {
                ddl.execute("DROP TRIGGER " + trigger + " ON app.backoffice_write_request");
                ddl.execute("DROP FUNCTION " + function + "()");
            }
        }
        processor.process(command, BackofficeCommandLane.QUICK);
        processor.process(command, BackofficeCommandLane.QUICK);
        writeStatus(owner, operation).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(count(owner, "SELECT count(*) FROM app.product")).isEqualTo(1);
        assertThat(count(owner, "SELECT quantity FROM app.stock")).isEqualTo(3);
    }

    @Test
    void theKafkaListenerCommitsTheTerminalResultAndPruningKeepsADeduplicationTombstone() throws Exception {
        Session owner = newStore();
        UUID operation = accepted(owner, "POST", CUSTOMERS, UUID.randomUUID(), "{\"phone\":\"0933333333\"}");
        var command = command(operation);
        assertThat(listeners.getListenerContainer("backoffice-file-worker").isRunning()).isFalse();
        listeners.getListenerContainer("backoffice-write-worker").start();
        await().atMost(Duration.ofSeconds(25)).until(() -> count(owner,
                "SELECT count(*) FROM app.backoffice_write_request WHERE operation_id = ?", operation) == 1);
        listeners.getListenerContainer("backoffice-write-worker").stop();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.prepareStatement("UPDATE app.backoffice_write_request SET created_at = now() - interval '8 days' WHERE operation_id = ?")) {
            statement.setObject(1, operation);
            statement.executeUpdate();
        }
        pruner.prune();
        writeStatus(owner, operation).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.responseStatus").value(410))
                .andExpect(jsonPath("$.problem.type").value("/errors/platform/write-result-expired"));
        processor.process(command, BackofficeCommandLane.QUICK);
        assertThat(count(owner, "SELECT count(*) FROM app.customer")).isEqualTo(1);
        assertThat(count(owner, "SELECT count(*) FROM app.backoffice_write_request WHERE pruned_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void terminalFileValidationFailureReleasesBytesAndACompletedDuplicateReleasesReuploadedBytes() throws Exception {
        Session owner = newStore();
        var invalid = fileCommand(owner, "/import/preview", UUID.randomUUID(), "missing required headers\n1\n", null);
        assertThat(Files.exists(stagedPath(invalid))).isTrue();
        processor.process(invalid, BackofficeCommandLane.FILE);
        writeStatus(owner, invalid.operationId()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.responseStatus").value(400));
        assertThat(Files.exists(stagedPath(invalid))).isFalse();

        UUID key = UUID.randomUUID();
        var preview = fileCommand(owner, "/import/preview", key, CSV, null);
        processor.process(preview, BackofficeCommandLane.FILE);
        writeStatus(owner, preview.operationId()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(Files.exists(stagedPath(preview))).isFalse();
        var duplicate = fileCommand(owner, "/import/preview", key, CSV, null);
        assertThat(duplicate.operationId()).isEqualTo(preview.operationId());
        assertThat(Files.exists(stagedPath(duplicate))).isTrue();
        processor.process(duplicate, BackofficeCommandLane.FILE);
        assertThat(Files.exists(stagedPath(duplicate))).isFalse();
    }

    @Test
    void failedTerminalCommitRetainsTheFileUntilItsRejectionCanBeCommitted() throws Exception {
        Session owner = newStore();
        var command = fileCommand(owner, "/import/preview", UUID.randomUUID(), "invalid headers\n1\n", null);
        String trigger = "file_result_failure_" + UUID.randomUUID().toString().replace("-", "");
        String function = "app." + trigger;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var ddl = connection.createStatement()) {
            ddl.execute("""
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.store_id = '%s'::uuid THEN
                            RAISE EXCEPTION 'transient terminal failure' USING ERRCODE = '40001';
                        END IF;
                        RETURN NEW;
                    END $$
                    """.formatted(function, owner.storeId()));
            ddl.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON app.backoffice_write_request FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");
            try {
                assertThatThrownBy(() -> processor.process(command, BackofficeCommandLane.FILE)).isInstanceOf(RuntimeException.class);
                assertThat(Files.exists(stagedPath(command))).isTrue();
                writeStatus(owner, command.operationId()).andExpect(jsonPath("$.status").value("PENDING"));
            } finally {
                ddl.execute("DROP TRIGGER " + trigger + " ON app.backoffice_write_request");
                ddl.execute("DROP FUNCTION " + function + "()");
            }
        }
        processor.process(command, BackofficeCommandLane.FILE);
        writeStatus(owner, command.operationId()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(Files.exists(stagedPath(command))).isFalse();
    }

    @Test
    void aStaleTerminalCommandCannotDiscardAFileOfAnotherAcceptedRequest() throws Exception {
        Session owner = newStore();
        var completed = fileCommand(owner, "/import", UUID.randomUUID(), CSV, "stale-preview");
        processor.process(completed, BackofficeCommandLane.FILE);
        writeStatus(owner, completed.operationId()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.responseStatus").value(409));
        assertThat(Files.exists(stagedPath(completed))).isFalse();
        var pending = fileCommand(owner, "/import", UUID.randomUUID(), CSV, "stale-preview");
        var stalePayload = (tools.jackson.databind.node.ObjectNode) completed.payload().deepCopy();
        // Product confirmation fingerprints only file bytes, so this stale reference passes the identity hash check.
        stalePayload.set("file", pending.payload().path("file"));
        var stale = new BackofficeCommand(completed.operationId(), completed.storeId(), completed.actorId(),
                completed.actorRole(), completed.idempotencyKey(), completed.action(), completed.requestHash(), stalePayload);
        processor.process(stale, BackofficeCommandLane.FILE);
        assertThat(Files.exists(stagedPath(pending))).isTrue();
        writeStatus(owner, pending.operationId()).andExpect(jsonPath("$.status").value("PENDING"));
        processor.process(pending, BackofficeCommandLane.FILE);
        assertThat(Files.exists(stagedPath(pending))).isFalse();
    }

    private BackofficeCommand fileCommand(Session session, String suffix, UUID key, String contents, String preview) throws Exception {
        var request = multipart(PRODUCTS + suffix).file(new MockMultipartFile("file", "source.csv", "text/csv",
                contents.getBytes(StandardCharsets.UTF_8))).cookie(session.cookie()).header("Idempotency-Key", key);
        if (preview != null) request.header("X-Import-Preview", preview);
        var response = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return command(UUID.fromString(json.readTree(response).path("operationId").asText()), FILE_TOPIC);
    }

    private Path stagedPath(BackofficeCommand command) {
        JsonNode reference = command.payload().has("file") ? command.payload().path("file") : command.payload();
        return fileDirectory.resolve(reference.path("key").asText() + ".data");
    }

    private JsonNode run(Session session, String method, String path, String body, int expectedStatus) throws Exception {
        UUID operation = accepted(session, method, path, UUID.randomUUID(), body);
        var command = command(operation);
        processor.process(command, BackofficeCommandLane.QUICK);
        processor.process(command, BackofficeCommandLane.QUICK);
        String response = writeStatus(session, operation).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.responseStatus").value(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private UUID accepted(Session session, String method, String path, UUID key, String body) throws Exception {
        String response = mvc.perform(request(HttpMethod.valueOf(method), path).cookie(session.cookie())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.statusUrl").isString()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("operationId").asText());
    }

    private org.springframework.test.web.servlet.ResultActions writeStatus(Session session, UUID operation) throws Exception {
        return mvc.perform(get("/api/v1/platform/write-requests/" + operation).cookie(session.cookie())).andExpect(status().isOk());
    }

    private BackofficeCommand command(UUID operation) {
        return command(operation, TOPIC);
    }

    private BackofficeCommand command(UUID operation, String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "backoffice-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<String, String>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    var command = json.readValue(record.value(), BackofficeCommand.class);
                    if (command.operationId().equals(operation)) {
                        assertThat(record.key()).isEqualTo(command.storeId().toString());
                        return command;
                    }
                }
            }
        }
        throw new AssertionError("Missing broker command: " + operation);
    }

    private long count(Session session, String sql, Object... args) {
        return inStore(session, () -> jdbc.queryForObject(sql, Long.class, args));
    }
    private <T> T inStore(Session session, Supplier<T> work) {
        return TenantScope.runAs(session.storeId(), session.userId(), vn.nhom17.banhang.platform.Role.OWNER,
                () -> new TransactionTemplate(transactions).execute(tx -> work.get()));
    }
    private Session newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "storeName", "Cửa hàng hàng đợi", "displayName", "Chủ thử",
                        "loginKey", "backoffice." + UUID.randomUUID().toString().replace("-", "") + "@test.vn",
                        "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        return new Session(response.getCookie("access_token"), UUID.fromString(body.path("storeId").asText()),
                UUID.fromString(body.path("userId").asText()));
    }
    private Session newStaff(Session owner) throws Exception {
        String login = "backoffice." + UUID.randomUUID().toString().replace("-", "");
        String response = mvc.perform(post("/api/v1/platform/users").cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginKey", login, "displayName", "Nhân viên thử", "password", "matkhau123"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginKey", login, "password", "matkhau123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        return new Session(cookie, owner.storeId(), UUID.fromString(json.readTree(response).path("userId").asText()));
    }
    private record Session(Cookie cookie, UUID storeId, UUID userId) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConfiguration {
        @Bean NewTopic quickCommandsTopic() { return new NewTopic(TOPIC, 3, (short) 1); }
        @Bean NewTopic fileCommandsTopic() { return new NewTopic("backoffice.file-commands.v1", 3, (short) 1); }
        @Bean KafkaContainer kafkaContainer() { return new KafkaContainer("apache/kafka:4.1.1"); }
        @Bean DynamicPropertyRegistrar kafkaProperties(KafkaContainer kafka) {
            return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        }
    }
}
