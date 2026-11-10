package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Ngừng kinh doanh Sản phẩm (FR-3, SP-03) trên PostgreSQL thật: ma trận I/O của spec, kèm kiểm
 * {@link VariantDeactivated} phát đúng một lần cho từng Biến thể vừa ngừng, trong transaction ngừng kinh doanh (AD-14),
 * và đúng một dòng Nhật ký thao tác. Mỗi test dùng Cửa hàng mới đăng ký riêng.
 *
 * <p>{@link TestClock} thay {@code Clock} của ứng dụng để thấy {@code changed_at} lấy từ {@code Clock} (AD-8, AD-12).
 * {@link BeforeCommitProbe} đứng vào chỗ listener {@code BEFORE_COMMIT} của {@code inventory}: ghi lại sự kiện nó
 * nhận, lúc đó có đang trong transaction không, và đã thấy Biến thể, Sản phẩm ở trạng thái {@code INACTIVE} chưa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, ProductDeactivateIntegrationTest.ProbeConfig.class})
class ProductDeactivateIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired BeforeCommitProbe probe;
    @Autowired TestClock clock;

    record Owner(Cookie cookie, UUID storeId, UUID userId) {
    }

    record Created(UUID productId, UUID variantId) {
    }

    @BeforeEach
    void reset() {
        probe.deliveries.clear();
        probe.throwNext = false;
        clock.useSystemTime();
    }

    // --- Ngừng Sản phẩm đang bán -------------------------------------------------------------------------------

    @Test
    void deactivatingAnActiveProductStopsItAndItsVariantInOneTransaction() throws Exception {
        Owner owner = newStore();
        Instant createdAt = clock.freezeAt(Duration.ZERO);
        Created product = create(owner, "Sữa tươi Vinamilk 1L", 15000, 24, 11000);
        Instant stoppedAt = clock.freezeAt(Duration.ofMinutes(10));

        String response = deactivate(owner, product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.productId().toString()))
                .andExpect(jsonPath("$.name").value("Sữa tươi Vinamilk 1L"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].id").value(product.variantId().toString()))
                .andExpect(jsonPath("$.variants[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(15000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // AD-15, AD-16: như GET chi tiết, không có Giá vốn hay tồn đầu kỳ
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
        JsonNode stopped = json.readTree(response);
        assertThat(Instant.parse(stopped.get("createdAt").asString())).isEqualTo(createdAt);
        assertThat(Instant.parse(stopped.get("updatedAt").asString())).isEqualTo(stoppedAt);
        assertThat(json.readTree(detail(owner, product.productId()))).isEqualTo(stopped);

        // AD-14: một sự kiện cho Biến thể vừa ngừng, nhận BEFORE_COMMIT trong transaction đã thấy dữ liệu mới
        VariantDeactivated expected = new VariantDeactivated(owner.storeId(), product.variantId());
        assertThat(variantDeactivated()).containsExactly(expected);
        assertThat(probe.deliveries).containsExactly(new Delivery(expected, true, "INACTIVE", "INACTIVE"));

        // AD-12: changed_at lấy từ Clock; không xóa hẳn, không đặt deleted_at
        Map<String, Object> row = productRow(owner.storeId(), product.productId());
        assertThat(row).containsEntry("status", "INACTIVE").containsEntry("deleted_at", null);
        assertThat(instant(row.get("changed_at"))).isEqualTo(stoppedAt).isAfter(createdAt);
        assertThat(instant(row.get("updated_at"))).isEqualTo(stoppedAt);
        assertThat(instant(row.get("created_at"))).isEqualTo(createdAt);
        Map<String, Object> variant = variantRow(owner.storeId(), product.variantId());
        assertThat(variant).containsEntry("status", "INACTIVE").containsEntry("deleted_at", null);
        assertThat(instant(variant.get("updated_at"))).isEqualTo(stoppedAt);

        // Change-feed báo INACTIVE để dịch vụ AI xóa embedding
        Map<String, Object> change = asStore(owner.storeId(), () -> jdbc.queryForMap(
                "select status, changed_at from app.catalog_product_changes where product_id = ?",
                product.productId()));
        assertThat(change.get("status")).isEqualTo("INACTIVE");
        assertThat(instant(change.get("changed_at"))).isEqualTo(stoppedAt);

        // Một dòng Nhật ký thao tác, đúng người làm và trạng thái trước, sau
        List<Map<String, Object>> audit = auditRows(owner.storeId(), product.productId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst())
                .containsEntry("action", "catalog.product.deactivate")
                .containsEntry("entity", "product")
                .containsEntry("actor_id", owner.userId());
        assertThat(json.readTree((String) audit.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"status\":\"ACTIVE\"}"));
        assertThat(json.readTree((String) audit.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"status\":\"INACTIVE\"}"));
        assertThat(audit.getFirst().get("request_id")).isNotNull();
    }

    @Test
    void everyVariantStillOnSaleIsStoppedWithOneEventEach() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, "Áo thun", 120000, 0, 0);
        // Chưa có API nhiều Biến thể (SP-07, GĐ3): thêm thẳng một Biến thể đang bán, một đã ngừng, một đã xóa mềm
        UUID second = UUID.randomUUID();
        UUID alreadyStopped = UUID.randomUUID();
        UUID softDeleted = UUID.randomUUID();
        Instant past = Instant.parse("2026-01-01T00:00:00Z");
        asStore(owner.storeId(), () -> {
            String insert = """
                    insert into app.product_variant (id, store_id, product_id, sku, name, sale_price, status,
                                                     created_at, updated_at, deleted_at)
                    values (?, ?, ?, ?, ?, 120000, ?, ?, ?, ?)
                    """;
            OffsetDateTime at = OffsetDateTime.ofInstant(past, ZoneOffset.UTC);
            jdbc.update(insert, second, owner.storeId(), product.productId(), "AO-L", "L", "ACTIVE", at, at, null);
            jdbc.update(insert, alreadyStopped, owner.storeId(), product.productId(), "AO-XL", "XL", "INACTIVE", at, at,
                    null);
            jdbc.update(insert, softDeleted, owner.storeId(), product.productId(), "AO-XXL", "XXL", "ACTIVE", at, at,
                    at);
            return null;
        });

        deactivate(owner, product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.variants[*].status").value(everyItem(is("INACTIVE"))));

        assertThat(variantDeactivated()).containsExactlyInAnyOrder(
                new VariantDeactivated(owner.storeId(), product.variantId()),
                new VariantDeactivated(owner.storeId(), second));
        assertThat(probe.deliveries).hasSize(2).allSatisfy(d -> {
            assertThat(d.inTransaction()).isTrue();
            assertThat(d.variantStatus()).isEqualTo("INACTIVE");
        });
        // Biến thể đã ngừng từ trước, Biến thể đã xóa mềm: không đổi gì, không có sự kiện
        assertThat(instant(variantRow(owner.storeId(), alreadyStopped).get("updated_at"))).isEqualTo(past);
        Map<String, Object> deleted = variantRow(owner.storeId(), softDeleted);
        assertThat(deleted).containsEntry("status", "ACTIVE");
        assertThat(instant(deleted.get("updated_at"))).isEqualTo(past);
        assertThat(auditRows(owner.storeId(), product.productId())).hasSize(1);
    }

    // --- Gọi lại ------------------------------------------------------------------------------------------------

    @Test
    void deactivatingAgainReturnsTheCurrentStateWithoutAnotherEventOrLogLine() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, "Bia Hà Nội", 12000, 0, 0);
        Instant stoppedAt = clock.freezeAt(Duration.ofMinutes(10));
        String first = deactivate(owner, product.productId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        clock.freezeAt(Duration.ofMinutes(20));
        String again = deactivate(owner, product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.variants[0].status").value("INACTIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        assertThat(variantDeactivated()).hasSize(1);
        assertThat(probe.deliveries).hasSize(1);
        assertThat(auditRows(owner.storeId(), product.productId())).hasSize(1);
        Map<String, Object> row = productRow(owner.storeId(), product.productId());
        assertThat(instant(row.get("changed_at"))).isEqualTo(stoppedAt);
        assertThat(instant(row.get("updated_at"))).isEqualTo(stoppedAt);
    }

    @Test
    void concurrentRequestsStopTheProductOnlyOnce() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, "Nước suối", 5000, 0, 0);
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return deactivate(owner, product.productId()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        // Sự kiện phát ở luồng khác luồng test nên ApplicationEvents không ghi; đếm qua listener BEFORE_COMMIT
        assertThat(probe.deliveries).containsExactly(new Delivery(
                new VariantDeactivated(owner.storeId(), product.variantId()), true, "INACTIVE", "INACTIVE"));
        assertThat(auditRows(owner.storeId(), product.productId())).hasSize(1);
    }

    // --- Rollback, xóa mềm ---------------------------------------------------------------------------------------

    @Test
    void aFailingInventoryListenerRollsTheWholeDeactivationBack() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, "Dầu ăn", 50000, 0, 0);
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());
        clock.freezeAt(Duration.ofMinutes(10));
        probe.throwNext = true;

        deactivate(owner, product.productId())
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.type").value("/errors/common/internal"))
                .andExpect(jsonPath("$.requestId").exists());

        // Listener đã được gọi (trong transaction) rồi ném lỗi: cả lần ngừng kinh doanh rollback
        assertThat(probe.deliveries).hasSize(1);
        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore)
                .containsEntry("status", "ACTIVE");
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore)
                .containsEntry("status", "ACTIVE");
        assertThat(auditRows(owner.storeId(), product.productId())).isEmpty();

        // Gọi lại khi inventory đã ổn thì ngừng được như thường
        deactivate(owner, product.productId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(auditRows(owner.storeId(), product.productId())).hasSize(1);
    }

    @Test
    void aSoftDeletedProductIsNotFoundAndNothingChanges() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, "Hàng đã xóa mềm", 1000, 0, 0);
        asStore(owner.storeId(), () -> jdbc.update(
                "update app.product set deleted_at = changed_at where id = ?", product.productId()));
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());
        clock.freezeAt(Duration.ofMinutes(10));

        deactivate(owner, product.productId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));

        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore)
                .containsEntry("status", "ACTIVE");
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore)
                .containsEntry("status", "ACTIVE");
        assertThat(variantDeactivated()).isEmpty();
        assertThat(probe.deliveries).isEmpty();
        assertThat(auditRows(owner.storeId(), product.productId())).isEmpty();
    }

    // --- Cửa hàng khác, Nhân viên --------------------------------------------------------------------------------

    @Test
    void anotherStoresProductOrAMissingIdIsNotFoundAndNothingChanges() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Created ofB = create(b, "Hàng của B", 1000, 0, 0);
        Map<String, Object> before = productRow(b.storeId(), ofB.productId());

        deactivate(a, ofB.productId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"))
                .andExpect(jsonPath("$.requestId").exists());
        deactivate(a, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));

        assertThat(productRow(b.storeId(), ofB.productId())).isEqualTo(before).containsEntry("status", "ACTIVE");
        assertThat(variantRow(b.storeId(), ofB.variantId())).containsEntry("status", "ACTIVE");
        assertThat(variantDeactivated()).isEmpty();
        assertThat(probe.deliveries).isEmpty();
        assertThat(auditRows(b.storeId(), ofB.productId())).isEmpty();
        assertThat(auditRows(a.storeId(), ofB.productId())).isEmpty();
    }

    @Test
    void staffIsForbiddenButCanStillReadTheProduct() throws Exception {
        Cookie staff = login("dev.nv.a");
        Cookie ownerA = login("dev.chu.a");
        String created = mvc.perform(post(PRODUCTS).cookie(ownerA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("Hàng Nhân viên không ngừng được", 20000, 0, 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(created).get("id").asString();

        mvc.perform(post(PRODUCTS + "/" + id + "/deactivate").cookie(staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(get(PRODUCTS + "/" + id).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(variantDeactivated()).isEmpty();
        assertThat(probe.deliveries).isEmpty();

        // Đã ngừng thì Nhân viên vẫn đọc được (lịch sử, báo cáo)
        mvc.perform(post(PRODUCTS + "/" + id + "/deactivate").cookie(ownerA)).andExpect(status().isOk());
        mvc.perform(get(PRODUCTS + "/" + id).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // --- Danh sách sau khi ngừng ---------------------------------------------------------------------------------

    @Test
    void aStoppedProductStaysInTheListAndTheDetail() throws Exception {
        Owner owner = newStore();
        Created first = create(owner, "Thứ nhất", 1000, 0, 0);
        create(owner, "Thứ hai", 2000, 0, 0);
        deactivate(owner, first.productId()).andExpect(status().isOk());

        mvc.perform(get(PRODUCTS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Thứ hai"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].id").value(first.productId().toString()))
                .andExpect(jsonPath("$.items[1].status").value("INACTIVE"))
                .andExpect(jsonPath("$.items[1].variants[0].status").value("INACTIVE"));
        mvc.perform(get(PRODUCTS + "/" + first.productId()).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Sự kiện {@link BeforeCommitProbe} nhận được, kèm trạng thái thấy được trong transaction lúc nhận. */
    record Delivery(VariantDeactivated event, boolean inTransaction, String variantStatus, String productStatus) {
    }

    /** Đứng vào chỗ listener {@code BEFORE_COMMIT} của inventory (AD-14). */
    public static class BeforeCommitProbe {

        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        /** Lần nhận sự kiện tới ném lỗi, như {@code inventory} không đặt được {@code stock.is_active}. */
        volatile boolean throwNext;
        private final JdbcTemplate jdbc;

        BeforeCommitProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void on(VariantDeactivated event) {
            Map<String, Object> seen = jdbc.queryForMap("""
                    select v.status as variant_status, p.status as product_status
                    from app.product_variant v join app.product p on p.id = v.product_id
                    where v.id = ?
                    """, event.variantId());
            deliveries.add(new Delivery(event, TransactionSynchronizationManager.isActualTransactionActive(),
                    (String) seen.get("variant_status"), (String) seen.get("product_status")));
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("inventory giả: không đặt được stock.is_active");
            }
        }
    }

    /**
     * {@code Clock} của ứng dụng trong test: giờ hệ thống, hoặc đứng yên ở một mốc sau {@link #freezeAt};
     * {@link #useSystemTime} trả lại giờ hệ thống, nên giờ có thể lùi về so với mốc đã đứng. Token đăng nhập kiểm theo
     * cùng {@code Clock}, nên test chỉ cho đứng ở mốc sau giờ hệ thống (token cấp trước đó vẫn còn hạn).
     * {@link #withZone} trả một {@code Clock} cố định ở thời điểm gọi (ứng dụng không dùng).
     */
    public static class TestClock extends Clock {

        private volatile Instant frozen;

        /** Đứng yên ở giờ hệ thống + {@code ahead}, làm tròn tới micro giây như {@code timestamptz}; trả mốc đó. */
        Instant freezeAt(Duration ahead) {
            Instant at = Clock.systemUTC().instant().plus(ahead).truncatedTo(ChronoUnit.MICROS);
            frozen = at;
            return at;
        }

        void useSystemTime() {
            frozen = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            Instant at = frozen;
            return at != null ? at : Clock.systemUTC().instant();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        @Bean
        BeforeCommitProbe deactivateProbe(JdbcTemplate jdbc) {
            return new BeforeCommitProbe(jdbc);
        }

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    List<VariantDeactivated> variantDeactivated() {
        return events.stream(VariantDeactivated.class).toList();
    }

    static Map<String, Object> body(String name, long salePrice, int openingQuantity, long openingUnitCost) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", salePrice);
        body.put("openingQuantity", openingQuantity);
        body.put("openingUnitCost", openingUnitCost);
        return body;
    }

    Created create(Owner owner, String name, long salePrice, int openingQuantity, long openingUnitCost)
            throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(name, salePrice, openingQuantity, openingUnitCost))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode created = json.readTree(response);
        return new Created(UUID.fromString(created.get("id").asString()),
                UUID.fromString(created.get("variants").get(0).get("id").asString()));
    }

    ResultActions deactivate(Owner owner, UUID productId) throws Exception {
        return mvc.perform(post(PRODUCTS + "/" + productId + "/deactivate").cookie(owner.cookie()));
    }

    String detail(Owner owner, UUID productId) throws Exception {
        return mvc.perform(get(PRODUCTS + "/" + productId).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    Map<String, Object> productRow(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select status, created_at, updated_at, changed_at, deleted_at from app.product where id = ?
                """, productId));
    }

    Map<String, Object> variantRow(UUID storeId, UUID variantId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select status, updated_at, deleted_at from app.product_variant where id = ?
                """, variantId));
    }

    List<Map<String, Object>> auditRows(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForList("""
                select action, entity, actor_id, before::text as before, after::text as after, request_id
                from app.audit_log where entity_id = ?
                """, productId.toString()));
    }

    static Instant instant(Object timestamp) {
        if (timestamp instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return ((OffsetDateTime) timestamp).toInstant();
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử ngừng kinh doanh");
        body.put("loginKey", "ngung." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()),
                UUID.fromString(me.get("userId").asString()));
    }

    Cookie login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
