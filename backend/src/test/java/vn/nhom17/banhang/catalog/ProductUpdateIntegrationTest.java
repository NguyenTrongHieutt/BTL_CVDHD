package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.ProductDeactivateIntegrationTest.BeforeCommitProbe;
import vn.nhom17.banhang.catalog.ProductDeactivateIntegrationTest.TestClock;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Sửa Sản phẩm (FR-2, SP-02) trên PostgreSQL thật: ma trận I/O của spec. Đổi giá ghi đúng một dòng Nhật ký thao tác
 * {@code catalog.variant.price_change} (entity là id Biến thể) trong transaction sửa; {@code changed_at} (change-feed,
 * AD-12) chỉ lấy giờ mới khi tên, Danh mục, mô tả hoặc đơn vị đổi. Mỗi test dùng Cửa hàng mới đăng ký riêng.
 *
 * <p>Dùng chung cấu hình test (và nên cùng một context Spring, cùng container) với
 * {@link ProductDeactivateIntegrationTest}: {@link TestClock} đóng băng giờ của ứng dụng để so {@code changed_at},
 * {@code updated_at} với đúng mốc đã đặt; {@link BeforeCommitProbe} ghi lại {@link VariantDeactivated}, để thấy sửa
 * không phát sự kiện nào cho {@code inventory}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, ProductDeactivateIntegrationTest.ProbeConfig.class})
class ProductUpdateIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";
    static final String PRICE_CHANGE = "catalog.variant.price_change";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired BeforeCommitProbe probe;
    @Autowired TestClock clock;
    @Autowired PasswordEncoder passwords;

    record Owner(Cookie cookie, UUID storeId, UUID userId) {
    }

    record Created(UUID productId, UUID variantId) {
    }

    @BeforeEach
    void reset() {
        probe.deliveries.clear();
        probe.throwNext = false;
        probe.releaseNext = null;
        clock.useSystemTime();
    }

    // --- Đổi giá ----------------------------------------------------------------------------------------------

    @Test
    void changingThePriceLogsOneLineAndKeepsChangedAt() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        Instant createdAt = clock.freezeAt(Duration.ZERO);
        Map<String, Object> milk = milk(drinks);
        Created product = create(owner, milk);
        Instant editedAt = clock.freezeAt(Duration.ofMinutes(10));

        milk.put("salePrice", 16000);
        String response = update(owner, product.productId(), milk)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.productId().toString()))
                .andExpect(jsonPath("$.name").value("Sữa tươi Vinamilk 1L"))
                .andExpect(jsonPath("$.categoryId").value(drinks.toString()))
                .andExpect(jsonPath("$.categoryName").value("Đồ uống"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].id").value(product.variantId().toString()))
                .andExpect(jsonPath("$.variants[0].salePrice").value(16000))
                .andExpect(jsonPath("$.variants[0].sku").value("VNM-1L"))
                .andExpect(jsonPath("$.variants[0].barcode").value("8934673123456"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // AD-15, AD-16: như GET chi tiết, không có Giá vốn hay Tồn kho
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening")
                .doesNotContain("quantity");
        JsonNode edited = json.readTree(response);
        assertThat(Instant.parse(edited.get("createdAt").asString())).isEqualTo(createdAt);
        assertThat(Instant.parse(edited.get("updatedAt").asString())).isEqualTo(editedAt);
        assertThat(json.readTree(detail(owner, product.productId()))).isEqualTo(edited);

        // Một dòng Nhật ký: entity là Biến thể, giá cũ và giá mới, đúng người làm
        List<Map<String, Object>> audit = auditRows(owner.storeId(), product.variantId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst())
                .containsEntry("action", PRICE_CHANGE)
                .containsEntry("entity", "product_variant")
                .containsEntry("actor_id", owner.userId());
        assertThat(json.readTree((String) audit.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"salePrice\":15000}"));
        assertThat(json.readTree((String) audit.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"salePrice\":16000}"));
        assertThat(audit.getFirst().get("request_id")).isNotNull();
        assertThat(auditRows(owner.storeId(), product.productId())).isEmpty();

        // Chỉ đổi giá: change-feed không có gì mới (searchText không đổi)
        Map<String, Object> row = productRow(owner.storeId(), product.productId());
        assertThat(instant(row.get("changed_at"))).isEqualTo(createdAt);
        assertThat(instant(row.get("updated_at"))).isEqualTo(editedAt);
        assertThat(instant(variantRow(owner.storeId(), product.variantId()).get("updated_at"))).isEqualTo(editedAt);
        assertThat(instant(change(owner.storeId(), product.productId()).get("changed_at"))).isEqualTo(createdAt);

        // Bảng hiện giá mới ngay
        mvc.perform(get(PRODUCTS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(product.productId().toString()))
                .andExpect(jsonPath("$.items[0].variants[0].salePrice").value(16000));
        // Sửa không phát sự kiện nào cho inventory
        assertThat(events.stream(VariantCreated.class)).hasSize(1);
        assertThat(events.stream(VariantDeactivated.class)).isEmpty();
    }

    @Test
    void eachRealPriceChangeIsLoggedOnce() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = body("Bánh mì", 15000);
        Created product = create(owner, body);

        body.put("salePrice", 16000);
        update(owner, product.productId(), body).andExpect(status().isOk());
        // Gửi lại cùng giá (kể cả viết 16000.0): không ghi thêm
        update(owner, product.productId(), body).andExpect(status().isOk());
        body.put("salePrice", 16000.0);
        update(owner, product.productId(), body).andExpect(status().isOk());
        // Đổi tên thôi: không ghi nhật ký
        body.put("name", "Bánh mì que");
        update(owner, product.productId(), body).andExpect(status().isOk());
        // Giảm về giá cũ, rồi về 0: mỗi lần một dòng
        body.put("salePrice", 15000);
        update(owner, product.productId(), body).andExpect(status().isOk());
        body.put("salePrice", 0);
        update(owner, product.productId(), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].salePrice").value(0));

        assertThat(priceChanges(owner.storeId(), product.variantId()))
                .containsExactly(List.of(15000L, 16000L), List.of(16000L, 15000L), List.of(15000L, 0L));
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.audit_log", Long.class))).isEqualTo(3);
    }

    @Test
    void concurrentPriceChangesAreLoggedOneAfterAnother() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, body("Nước suối", 15000));
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 1; i <= callers; i++) {
                long price = 15000 + i * 1000L;
                results.add(pool.submit(() -> {
                    start.await();
                    return update(owner, product.productId(), body("Nước suối", price))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        // Khóa dòng khi sửa: mỗi lần thấy giá lần trước vừa lưu, nên các dòng nối thành một chuỗi từ 15000 tới giá
        // cuối cùng, không lần nào ghi đè mất dấu
        List<List<Long>> changes = priceChanges(owner.storeId(), product.variantId());
        assertThat(changes).hasSize(callers);
        Map<Long, Long> next = new HashMap<>();
        changes.forEach(c -> next.put(c.get(0), c.get(1)));
        assertThat(next).hasSize(callers);
        long price = 15000;
        for (int i = 0; i < callers; i++) {
            assertThat(next).containsKey(price);
            price = next.get(price);
        }
        assertThat(variantRow(owner.storeId(), product.variantId())).containsEntry("sale_price", price);
    }

    @Test
    void anEditRacingADeactivationWaitsForItAndIsRejected() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, body("Bia Hà Nội", 12000));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        probe.enteredNext = entered;
        probe.releaseNext = release;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        MvcResult edit;
        try {
            // Ngừng kinh doanh dừng ở BEFORE_COMMIT: đã UPDATE, đang khóa dòng Sản phẩm và Biến thể
            Future<Integer> stopping = pool.submit(() -> mvc.perform(post(PRODUCTS + "/" + product.productId()
                    + "/deactivate").cookie(owner.cookie())).andReturn().getResponse().getStatus());
            assertThat(entered.await(30, TimeUnit.SECONDS)).as("ngừng kinh doanh tới BEFORE_COMMIT").isTrue();
            Future<MvcResult> editing = pool.submit(() -> update(owner, product.productId(),
                    body("Bia Hà Nội", 13000)).andReturn());
            // Sửa phải chờ khóa dòng (chưa đọc được trạng thái cũ) rồi mới được thả lần ngừng kinh doanh
            assertThat(someoneWaitsForARowLock(owner.storeId())).as("PUT chờ khóa dòng Sản phẩm").isTrue();
            release.countDown();
            assertThat(stopping.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            edit = editing.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // Thấy Sản phẩm đã ngừng sau khi lần ngừng kinh doanh commit: 409, không deadlock, không 500
        assertThat(edit.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(edit.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("type").asString())
                .isEqualTo("/errors/catalog/product-inactive");
        assertThat(productRow(owner.storeId(), product.productId())).containsEntry("status", "INACTIVE");
        // Không có giá mới trên Biến thể đã ngừng, không có dòng Nhật ký đổi giá
        assertThat(variantRow(owner.storeId(), product.variantId()))
                .containsEntry("status", "INACTIVE").containsEntry("sale_price", 12000L);
        assertThat(priceChanges(owner.storeId(), product.variantId())).isEmpty();
        assertThat(probe.deliveries).hasSize(1);
    }

    @Test
    void anOrderAlreadyMadeKeepsThePriceAndCostCapturedAtSale() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, body("Dầu ăn", 15000));
        // Chưa có API bán hàng ở catalog: ghi thẳng một Đơn hàng đã bán 2 cái giá 15000, Giá vốn 11000
        UUID orderId = UUID.randomUUID();
        asStore(owner.storeId(), () -> {
            OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update("""
                    insert into app.sales_order (id, store_id, order_code, status, subtotal, total_amount, created_by,
                                                 created_at)
                    values (?, ?, 'DH-000001', 'COMPLETED', 30000, 30000, ?, ?)
                    """, orderId, owner.storeId(), owner.userId(), at);
            jdbc.update("""
                    insert into app.sales_order_line (store_id, order_id, line_no, variant_id, product_name, quantity,
                                                      unit_price, net_line_amount, unit_cost)
                    values (?, ?, 1, ?, ?, 2, 15000, 30000, 11000)
                    """, owner.storeId(), orderId, product.variantId(), "Dầu ăn");
            return null;
        });

        update(owner, product.productId(), body("Dầu ăn Neptune", 18000)).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].salePrice").value(18000));

        Map<String, Object> line = asStore(owner.storeId(), () -> jdbc.queryForMap(
                "select product_name, unit_price, net_line_amount, unit_cost from app.sales_order_line where order_id = ?",
                orderId));
        assertThat(line).containsEntry("product_name", "Dầu ăn").containsEntry("unit_price", 15000L)
                .containsEntry("net_line_amount", 30000L).containsEntry("unit_cost", 11000L);
    }

    // --- Đổi thông tin khác -----------------------------------------------------------------------------------

    @Test
    void renamingBumpsChangedAtWithoutALogLine() throws Exception {
        Owner owner = newStore();
        Instant createdAt = clock.freezeAt(Duration.ZERO);
        Map<String, Object> milk = milk(null);
        Created product = create(owner, milk);
        Instant editedAt = clock.freezeAt(Duration.ofMinutes(10));

        milk.put("name", "  Sữa tươi Vinamilk 1 lít ");
        update(owner, product.productId(), milk)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sữa tươi Vinamilk 1 lít"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(15000));

        Map<String, Object> row = productRow(owner.storeId(), product.productId());
        assertThat(instant(row.get("changed_at"))).isEqualTo(editedAt).isAfter(createdAt);
        assertThat(instant(row.get("updated_at"))).isEqualTo(editedAt);
        // Biến thể không đổi gì
        assertThat(instant(variantRow(owner.storeId(), product.variantId()).get("updated_at"))).isEqualTo(createdAt);
        Map<String, Object> change = change(owner.storeId(), product.productId());
        assertThat(instant(change.get("changed_at"))).isEqualTo(editedAt);
        assertThat((String) change.get("search_text")).contains("Sữa tươi Vinamilk 1 lít");
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();
        assertThat(auditRows(owner.storeId(), product.productId())).isEmpty();
    }

    @Test
    void changingTheCategoryDescriptionOrUnitAlsoBumpsChangedAt() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        UUID chilled = createCategory(owner, "Hàng lạnh");
        clock.freezeAt(Duration.ZERO);
        Map<String, Object> milk = milk(drinks);
        Created product = create(owner, milk);

        List<Map.Entry<String, Object>> edits = List.of(
                Map.entry("categoryId", chilled.toString()),
                Map.entry("description", "Sữa tươi tiệt trùng, không đường"),
                Map.entry("unit", "lốc"));
        int minute = 1;
        for (Map.Entry<String, Object> edit : edits) {
            Instant at = clock.freezeAt(Duration.ofMinutes(minute++));
            milk.put(edit.getKey(), edit.getValue());
            update(owner, product.productId(), milk).andExpect(status().isOk());
            assertThat(instant(productRow(owner.storeId(), product.productId()).get("changed_at")))
                    .as(edit.getKey()).isEqualTo(at);
        }
        // Danh mục mới (tên không có trong tên Sản phẩm) vào searchText của change-feed, Danh mục cũ ra khỏi đó
        assertThat((String) change(owner.storeId(), product.productId()).get("search_text")).contains("Hàng lạnh")
                .doesNotContain("Đồ uống");
        mvc.perform(get(PRODUCTS + "/" + product.productId()).cookie(owner.cookie()))
                .andExpect(jsonPath("$.categoryId").value(chilled.toString()))
                .andExpect(jsonPath("$.categoryName").value("Hàng lạnh"))
                .andExpect(jsonPath("$.description").value("Sữa tươi tiệt trùng, không đường"))
                .andExpect(jsonPath("$.unit").value("lốc"));
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.audit_log", Long.class))).isZero();
    }

    @Test
    void changingOnlySkuOrBarcodeKeepsChangedAtAndIsNotLogged() throws Exception {
        Owner owner = newStore();
        Instant createdAt = clock.freezeAt(Duration.ZERO);
        Map<String, Object> milk = milk(null);
        Created product = create(owner, milk);
        Instant editedAt = clock.freezeAt(Duration.ofMinutes(10));

        milk.put("sku", " vnm-1l-moi ");
        milk.put("barcode", "abc123");
        update(owner, product.productId(), milk)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].sku").value("VNM-1L-MOI"))
                .andExpect(jsonPath("$.variants[0].barcode").value("ABC123"));

        assertThat(instant(productRow(owner.storeId(), product.productId()).get("changed_at"))).isEqualTo(createdAt);
        assertThat(instant(productRow(owner.storeId(), product.productId()).get("updated_at"))).isEqualTo(editedAt);
        assertThat(instant(variantRow(owner.storeId(), product.variantId()).get("updated_at"))).isEqualTo(editedAt);
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();
    }

    @Test
    void optionalFieldsLeftEmptyAreClearedButTheSkuIsKept() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        Map<String, Object> milk = milk(drinks);
        Created product = create(owner, milk);

        Map<String, Object> bare = body("Sữa tươi Vinamilk 1L", 15000);
        bare.put("description", "  ");
        bare.put("sku", "");
        update(owner, product.productId(), bare)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").isEmpty())
                .andExpect(jsonPath("$.categoryName").isEmpty())
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.unit").value("cái"))
                .andExpect(jsonPath("$.variants[0].barcode").isEmpty())
                // SKU trống thì giữ SKU hiện tại (Biến thể luôn có SKU)
                .andExpect(jsonPath("$.variants[0].sku").value("VNM-1L"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(15000));
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();
        // Danh mục không còn Sản phẩm nào: xóa được
        mvc.perform(delete("/api/v1/catalog/categories/" + drinks).cookie(owner.cookie()))
                .andExpect(status().isNoContent());
    }

    @Test
    void sendingTheSameDetailsAgainChangesNothing() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        clock.freezeAt(Duration.ZERO);
        Created product = create(owner, milk(drinks));
        JsonNode before = detailNode(owner, product.productId());
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());
        clock.freezeAt(Duration.ofMinutes(10));

        // PUT y nguyên những gì GET trả về
        String same = update(owner, product.productId(), bodyOf(before)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(same)).isEqualTo(before);
        // Khác cách viết nhưng chuẩn hóa ra như cũ: chữ thường, khoảng trắng thừa, NFD, 15000.0
        Map<String, Object> spelledDifferently = bodyOf(before);
        // NFD, thêm dấu cách và U+00A0 (dấu cách không ngắt) ở cuối
        spelledDifferently.put("name", " " + Normalizer.normalize("Sữa tươi Vinamilk 1L", Normalizer.Form.NFD)
                + "\u00a0");
        spelledDifferently.put("sku", " vnm-1l ");
        spelledDifferently.put("barcode", "8934673123456 ");
        spelledDifferently.put("unit", "hộp ");
        spelledDifferently.put("salePrice", 15000.0);
        update(owner, product.productId(), spelledDifferently).andExpect(status().isOk());

        assertThat(json.readTree(detail(owner, product.productId()))).isEqualTo(before);
        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore);
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore);
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.audit_log", Long.class))).isZero();
    }

    // --- Trùng mã vạch, SKU -------------------------------------------------------------------------------------

    @Test
    void aBarcodeOrSkuOfAnotherProductIsTakenAndNothingChanges() throws Exception {
        Owner owner = newStore();
        Map<String, Object> first = body("Bia Hà Nội", 12000);
        first.put("sku", "BIA-HN");
        first.put("barcode", "8935001800019");
        create(owner, first);
        Map<String, Object> second = body("Bia Sài Gòn", 13000);
        second.put("sku", "BIA-SG");
        second.put("barcode", "8935001800026");
        Created other = create(owner, second);
        Map<String, Object> productBefore = productRow(owner.storeId(), other.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), other.variantId());

        // Đổi giá cùng lúc với mã vạch trùng: cả lần sửa bị hủy, kể cả giá và nhật ký
        Map<String, Object> takenBarcode = new LinkedHashMap<>(second);
        takenBarcode.put("name", "Bia Sài Gòn đỏ");
        takenBarcode.put("salePrice", 14000);
        for (String barcode : List.of("8935001800019", " 8935001800019 ")) {
            takenBarcode.put("barcode", barcode);
            update(owner, other.productId(), takenBarcode)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("/errors/catalog/barcode-taken"))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        Map<String, Object> takenSku = new LinkedHashMap<>(second);
        takenSku.put("salePrice", 14000);
        for (String sku : List.of("BIA-HN", "bia-hn")) {
            takenSku.put("sku", sku);
            update(owner, other.productId(), takenSku)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("/errors/catalog/sku-taken"))
                    // Khi sửa, SKU trống là giữ SKU hiện tại, không tự sinh
                    .andExpect(jsonPath("$.detail").value(
                            "Cửa hàng đã có Sản phẩm khác dùng SKU này. Hãy nhập SKU khác, hoặc để trống để giữ SKU "
                                    + "hiện tại."));
        }

        assertThat(productRow(owner.storeId(), other.productId())).isEqualTo(productBefore);
        assertThat(variantRow(owner.storeId(), other.variantId())).isEqualTo(variantBefore);
        assertThat(detailNode(owner, other.productId()).get("variants").get(0).get("salePrice").asLong())
                .isEqualTo(13000);
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.audit_log", Long.class))).isZero();
    }

    @Test
    void keepingItsOwnBarcodeAndSkuIsFine() throws Exception {
        Owner owner = newStore();
        Map<String, Object> beer = body("Bia Hà Nội", 12000);
        beer.put("sku", "BIA-HN");
        beer.put("barcode", "ABC123");
        Created product = create(owner, beer);

        beer.put("name", "Bia Hà Nội lon");
        beer.put("salePrice", 12500);
        beer.put("sku", "bia-hn");
        beer.put("barcode", "abc123");
        update(owner, product.productId(), beer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bia Hà Nội lon"))
                .andExpect(jsonPath("$.variants[0].sku").value("BIA-HN"))
                .andExpect(jsonPath("$.variants[0].barcode").value("ABC123"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(12500));
    }

    @Test
    void anotherStoreMayUseTheSameBarcodeAndSku() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Map<String, Object> ofB = body("Mì Hảo Hảo", 4000);
        ofB.put("sku", "MI-HH");
        ofB.put("barcode", "8934563138165");
        create(b, ofB);
        Created ofA = create(a, body("Mì tôm", 4500));

        Map<String, Object> edit = new LinkedHashMap<>(ofB);
        edit.put("name", "Mì tôm chua cay");
        update(a, ofA.productId(), edit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].sku").value("MI-HH"))
                .andExpect(jsonPath("$.variants[0].barcode").value("8934563138165"));
    }

    // --- Đã ngừng, Nhân viên, Cửa hàng khác ---------------------------------------------------------------------

    @Test
    void aStoppedProductCannotBeEditedAndNothingChanges() throws Exception {
        Owner owner = newStore();
        Map<String, Object> beer = body("Bia Hà Nội", 12000);
        Created product = create(owner, beer);
        mvc.perform(post(PRODUCTS + "/" + product.productId() + "/deactivate").cookie(owner.cookie()))
                .andExpect(status().isOk());
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());
        clock.freezeAt(Duration.ofMinutes(10));

        beer.put("name", "Bia Hà Nội mở bán lại");
        beer.put("salePrice", 13000);
        update(owner, product.productId(), beer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"))
                .andExpect(jsonPath("$.requestId").exists());
        // Gửi y nguyên cũng không được: không mở bán lại, không sửa Sản phẩm đã ngừng
        update(owner, product.productId(), body("Bia Hà Nội", 12000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));

        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore)
                .containsEntry("status", "INACTIVE");
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore)
                .containsEntry("status", "INACTIVE");
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();
        // Dữ liệu sai vẫn báo 400 trước
        update(owner, product.productId(), body("", 12000)).andExpect(status().isBadRequest());
    }

    @Test
    void staffIsForbiddenAndNothingChanges() throws Exception {
        Owner owner = newStore();
        Cookie staff = addStaff(owner);
        Created product = create(owner, body("Hàng Nhân viên không sửa được", 20000));
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());
        clock.freezeAt(Duration.ofMinutes(10));

        mvc.perform(put(PRODUCTS + "/" + product.productId()).cookie(staff).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("Nhân viên đổi giá", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(get(PRODUCTS + "/" + product.productId()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hàng Nhân viên không sửa được"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(20000));
        // Không đổi gì, kể cả updated_at, changed_at; không có dòng Nhật ký
        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore);
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore);
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.audit_log", Long.class))).isZero();
    }

    @Test
    void fieldsThatCannotBeEditedHereAreIgnored() throws Exception {
        Owner owner = newStore();
        Created product = create(owner, body("Bánh quy", 30000));
        Map<String, Object> stockBefore = stockOf(owner.storeId(), product.variantId());

        for (String status : List.of("ACTIVE", "INACTIVE")) {
            Map<String, Object> sneaky = body("Bánh quy bơ " + status, 30000);
            sneaky.put("status", status);
            sneaky.put("hasVariants", true);
            sneaky.put("openingQuantity", 50);
            sneaky.put("openingUnitCost", 20000);
            String response = update(owner, product.productId(), sneaky)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Bánh quy bơ " + status))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.hasVariants").value(false))
                    .andExpect(jsonPath("$.variants.length()").value(1))
                    .andExpect(jsonPath("$.variants[0].status").value("ACTIVE"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
        }
        // Không có Tồn kho đầu kỳ, Giá vốn mới: không phát sự kiện nào cho inventory, bảng stock không đổi
        assertThat(events.stream(VariantCreated.class)).hasSize(1);
        assertThat(events.stream(VariantDeactivated.class)).isEmpty();
        assertThat(probe.deliveries).isEmpty();
        assertThat(stockOf(owner.storeId(), product.variantId())).isEqualTo(stockBefore);
        assertThat(productRow(owner.storeId(), product.productId())).containsEntry("status", "ACTIVE");
    }

    @Test
    void anotherStoresProductOrAMissingOrSoftDeletedIdIsNotFound() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Created ofB = create(b, body("Hàng của B", 1000));
        Map<String, Object> before = productRow(b.storeId(), ofB.productId());
        Map<String, Object> variantBefore = variantRow(b.storeId(), ofB.variantId());

        update(a, ofB.productId(), body("A sửa hàng của B", 2000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"))
                .andExpect(jsonPath("$.requestId").exists());
        update(a, UUID.randomUUID(), body("Không có", 2000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        assertThat(productRow(b.storeId(), ofB.productId())).isEqualTo(before);
        assertThat(variantRow(b.storeId(), ofB.variantId())).isEqualTo(variantBefore);
        assertThat(auditRows(b.storeId(), ofB.variantId())).isEmpty();
        assertThat(auditRows(a.storeId(), ofB.variantId())).isEmpty();

        // Đã xóa mềm: không có
        asStore(b.storeId(), () -> jdbc.update(
                "update app.product set deleted_at = changed_at where id = ?", ofB.productId()));
        update(b, ofB.productId(), body("Hàng đã xóa mềm", 2000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        assertThat(variantRow(b.storeId(), ofB.variantId())).isEqualTo(variantBefore);
    }

    // --- Dữ liệu sai --------------------------------------------------------------------------------------------

    @Test
    void invalidFieldsAreReportedPerFieldLikeWhenAdding() throws Exception {
        Owner owner = newStore();
        Owner other = newStore();
        UUID ofOther = createCategory(other, "Danh mục của Cửa hàng khác");
        Created product = create(owner, body("Sản phẩm thử", 10000));
        Map<String, Object> productBefore = productRow(owner.storeId(), product.productId());
        Map<String, Object> variantBefore = variantRow(owner.storeId(), product.variantId());

        // Thiếu cả tên lẫn giá: báo cả hai trong một lần
        mvc.perform(put(PRODUCTS + "/" + product.productId()).cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("Nhập tên Sản phẩm"))
                .andExpect(jsonPath("$.errors[1].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[1].message").value("Nhập giá bán"));

        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"name", "   "});
        cases.add(new Object[] {"name", "a".repeat(201)});
        // U+2028 LINE SEPARATOR: trường một dòng không nhận ngắt dòng Unicode
        cases.add(new Object[] {"name", "Bia\u2028Hà Nội"});
        cases.add(new Object[] {"unit", "u".repeat(31)});
        cases.add(new Object[] {"description", "d".repeat(2001)});
        cases.add(new Object[] {"sku", "s".repeat(51)});
        cases.add(new Object[] {"barcode", "1".repeat(51)});
        cases.add(new Object[] {"barcode", "893-456"});
        cases.add(new Object[] {"categoryId", ofOther.toString()});
        cases.add(new Object[] {"categoryId", UUID.randomUUID().toString()});
        cases.add(new Object[] {"salePrice", -1});
        cases.add(new Object[] {"salePrice", 1500.5});
        cases.add(new Object[] {"salePrice", 9_007_199_254_740_992L});
        for (Object[] c : cases) {
            Map<String, Object> body = body("Sản phẩm thử", 20000);
            body.put((String) c[0], c[1]);
            update(owner, product.productId(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value(c[0]))
                    .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        }
        // Dữ liệu sai báo 400 trước 404
        update(owner, UUID.randomUUID(), body("", 1000)).andExpect(status().isBadRequest());

        assertThat(productRow(owner.storeId(), product.productId())).isEqualTo(productBefore);
        assertThat(variantRow(owner.storeId(), product.variantId())).isEqualTo(variantBefore);
        assertThat(auditRows(owner.storeId(), product.variantId())).isEmpty();

        // Đúng giới hạn thì được
        Map<String, Object> limits = body("ệ".repeat(200), 9_007_199_254_740_991L);
        limits.put("unit", "u".repeat(30));
        limits.put("description", "d".repeat(1000) + "\n\t" + "d".repeat(998));
        limits.put("sku", "s".repeat(50));
        limits.put("barcode", "Ab1".repeat(16) + "Z9");
        update(owner, product.productId(), limits).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].salePrice").value(9_007_199_254_740_991L));
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Sữa tươi đủ mọi trường; {@code categoryId} có thể {@code null}. */
    static Map<String, Object> milk(UUID categoryId) {
        Map<String, Object> body = body("Sữa tươi Vinamilk 1L", 15000);
        if (categoryId != null) {
            body.put("categoryId", categoryId.toString());
        }
        body.put("unit", "hộp");
        body.put("description", "Sữa tươi tiệt trùng");
        body.put("sku", "VNM-1L");
        body.put("barcode", "8934673123456");
        return body;
    }

    static Map<String, Object> body(String name, Object salePrice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", salePrice);
        return body;
    }

    /** Body sửa lấy y nguyên từ chi tiết Sản phẩm (Biến thể mặc định đứng đầu). */
    static Map<String, Object> bodyOf(JsonNode product) {
        JsonNode variant = product.get("variants").get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", product.get("name").asString());
        body.put("categoryId", product.get("categoryId").isNull() ? null : product.get("categoryId").asString());
        body.put("unit", product.get("unit").asString());
        body.put("description", product.get("description").isNull() ? null : product.get("description").asString());
        body.put("salePrice", variant.get("salePrice").asLong());
        body.put("sku", variant.get("sku").asString());
        body.put("barcode", variant.get("barcode").isNull() ? null : variant.get("barcode").asString());
        return body;
    }

    Created create(Owner owner, Map<String, Object> body) throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode created = json.readTree(response);
        return new Created(UUID.fromString(created.get("id").asString()),
                UUID.fromString(created.get("variants").get(0).get("id").asString()));
    }

    ResultActions update(Owner owner, UUID productId, Map<String, Object> body) throws Exception {
        return mvc.perform(put(PRODUCTS + "/" + productId).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    String detail(Owner owner, UUID productId) throws Exception {
        return mvc.perform(get(PRODUCTS + "/" + productId).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    JsonNode detailNode(Owner owner, UUID productId) throws Exception {
        return json.readTree(detail(owner, productId));
    }

    UUID createCategory(Owner owner, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/categories").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    Map<String, Object> productRow(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select name, category_id, unit, description, status, created_at, updated_at, changed_at, deleted_at
                from app.product where id = ?
                """, productId));
    }

    Map<String, Object> variantRow(UUID storeId, UUID variantId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select sku, barcode, sale_price, status, updated_at, deleted_at from app.product_variant where id = ?
                """, variantId));
    }

    Map<String, Object> change(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForMap(
                "select search_text, status, changed_at from app.catalog_product_changes where product_id = ?",
                productId));
    }

    List<Map<String, Object>> auditRows(UUID storeId, UUID entityId) {
        return asStore(storeId, () -> jdbc.queryForList("""
                select action, entity, actor_id, before::text as before, after::text as after, request_id
                from app.audit_log where entity_id = ?
                order by created_at, id
                """, entityId.toString()));
    }

    /** Các lần đổi giá của Biến thể theo thứ tự ghi: {@code [giá cũ, giá mới]}. */
    List<List<Long>> priceChanges(UUID storeId, UUID variantId) {
        return asStore(storeId, () -> jdbc.query("""
                select (before ->> 'salePrice')::bigint as old_price, (after ->> 'salePrice')::bigint as new_price
                from app.audit_log where action = ? and entity = 'product_variant' and entity_id = ?
                order by created_at, id
                """, (rs, i) -> List.of(rs.getLong("old_price"), rs.getLong("new_price")), PRICE_CHANGE,
                variantId.toString()));
    }

    static Instant instant(Object timestamp) {
        return ProductDeactivateIntegrationTest.instant(timestamp);
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử sửa Sản phẩm");
        body.put("loginKey", "sua." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
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

    /**
     * Thêm một Nhân viên vào Cửa hàng của {@code owner} (chưa có API tạo Nhân viên, TK-02), đăng nhập và trả cookie
     * phiên của Nhân viên đó.
     */
    Cookie addStaff(Owner owner) throws Exception {
        String loginKey = "nv." + UUID.randomUUID().toString().replace("-", "") + "@test.vn";
        asStore(owner.storeId(), () -> jdbc.update("""
                insert into app.app_user (store_id, login_key, display_name, password_hash, role)
                values (?, ?, 'Nhân viên thử', ?, 'STAFF')
                """, owner.storeId(), loginKey, passwords.encode("matkhau123")));
        Map<String, String> credentials = Map.of("loginKey", loginKey, "password", "matkhau123");
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.storeId").value(owner.storeId().toString()))
                .andReturn().getResponse().getCookie("access_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    /** Dòng Tồn kho của Biến thể (số dòng, Giá vốn, số lượng); chưa có thì cả ba là 0. */
    Map<String, Object> stockOf(UUID storeId, UUID variantId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select count(*) as lines, coalesce(sum(avg_unit_cost), 0) as cost, coalesce(sum(quantity), 0) as quantity
                from app.stock where variant_id = ?
                """, variantId));
    }

    /** Có phiên khác đang chờ khóa (khóa dòng của {@code FOR UPDATE}); chờ tối đa 30 giây. */
    boolean someoneWaitsForARowLock(UUID storeId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Long waiting = asStore(storeId, () -> jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query like '%for update%'
                    """, Long.class));
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
