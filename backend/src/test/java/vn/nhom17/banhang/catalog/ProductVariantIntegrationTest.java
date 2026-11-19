package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.ProductDeactivateIntegrationTest.TestClock;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Biến thể Sản phẩm (FR-7, SP-07) trên PostgreSQL thật: ma trận I/O của spec. Tạo kèm nhiều Biến thể, thêm, sửa, ngừng
 * từng Biến thể; kiểm sự kiện {@link VariantCreated}, {@link VariantDeactivated} (AD-14) và chính các dòng
 * {@code stock} mà listener thật của {@code inventory} (Anh) tạo, đặt {@code is_active} cho từng Biến thể; Nhật ký;
 * change-feed ({@code changed_at}, {@code search_text}); hàng bán được ({@link VariantLookup},
 * {@code /sales/sellable}). Mỗi test dùng Cửa hàng mới đăng ký riêng (trừ test quyền Nhân viên).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, ProductVariantIntegrationTest.ClockConfig.class})
class ProductVariantIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";
    static final String SKU_GENERATED = "SP-[0-9A-F]{8}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired TestClock clock;
    @Autowired VariantLookup variantLookup;

    record Owner(Cookie cookie, UUID storeId) {
    }

    /** Sản phẩm vừa tạo: id và id các Biến thể theo thứ tự trả về (mặc định đứng đầu). */
    record Created(UUID productId, List<UUID> variantIds, JsonNode body) {
    }

    @BeforeEach
    void systemTime() {
        clock.useSystemTime();
    }

    // --- Tạo kèm nhiều Biến thể ----------------------------------------------------------------------------------

    @Test
    void creatingWithTwoVariantsMakesTheFirstDefaultAndOpensStockForEachVariant() throws Exception {
        Owner owner = newStore();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = tshirt();

        String response = create(owner, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(PRODUCTS + "/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.name").value("Áo thun"))
                .andExpect(jsonPath("$.hasVariants").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priceRange.min").value(150000))
                .andExpect(jsonPath("$.priceRange.max").value(160000))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].name").value("Đỏ / L"))
                .andExpect(jsonPath("$.variants[0].attributes.Màu").value("Đỏ"))
                .andExpect(jsonPath("$.variants[0].attributes.Size").value("L"))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.variants[0].salePrice").value(150000))
                .andExpect(jsonPath("$.variants[0].sku").value("AO-DO-L"))
                .andExpect(jsonPath("$.variants[1].name").value("Xanh / M"))
                .andExpect(jsonPath("$.variants[1].isDefault").value(false))
                .andExpect(jsonPath("$.variants[1].salePrice").value(160000))
                .andExpect(jsonPath("$.variants[1].sku").value(matchesPattern(SKU_GENERATED)))
                .andExpect(jsonPath("$.variants[1].status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // AD-15, AD-16: không có Giá vốn hay tồn đầu kỳ trong phản hồi
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
        Created created = created(response);
        UUID red = created.variantIds().get(0);
        UUID blue = created.variantIds().get(1);

        // Mỗi Biến thể một sự kiện, đúng tồn đầu kỳ riêng
        assertThat(events.stream(VariantCreated.class)).containsExactly(
                new VariantCreated(owner.storeId(), red, 5, 70000),
                new VariantCreated(owner.storeId(), blue, 3, 72000));
        // Listener thật của inventory: hai dòng Tồn kho riêng, kèm Tồn đầu kỳ OPENING
        assertThat(stockOf(owner, red)).containsEntry("quantity", 5).containsEntry("avg_unit_cost", 70000L)
                .containsEntry("is_active", true);
        assertThat(stockOf(owner, blue)).containsEntry("quantity", 3).containsEntry("avg_unit_cost", 72000L)
                .containsEntry("is_active", true);
        assertThat(openingLedger(owner, red)).isEqualTo(5);
        assertThat(openingLedger(owner, blue)).isEqualTo(3);
        assertThat(asStore(owner, () -> jdbc.queryForObject(
                "select has_variants from app.product where id = ?", Boolean.class, created.productId()))).isTrue();

        // Chi tiết, danh sách: như lúc tạo; danh sách có khoảng giá
        assertThat(json.readTree(detail(owner, created.productId()))).isEqualTo(created.body());
        send(owner, get(PRODUCTS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].variants.length()").value(2))
                .andExpect(jsonPath("$.items[0].priceRange.min").value(150000))
                .andExpect(jsonPath("$.items[0].priceRange.max").value(160000));

        // Gửi lại cùng khóa, cùng nội dung: trả Sản phẩm cũ, không phát sự kiện lần hai
        create(owner, key, body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(created.productId().toString()));
        assertThat(events.stream(VariantCreated.class)).hasSize(2);
        assertThat(productCount(owner)).isEqualTo(1);
    }

    @Test
    void variantsAreKeptInTheOrderSentAndLabelsComeFromAttributeValuesInThatOrder() throws Exception {
        Owner owner = newStore();
        List<Map<String, Object>> variants = new ArrayList<>();
        for (String size : List.of("S", "M", "L", "XL")) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Size", size);
            attributes.put(" Màu ", "  Đen ");
            variants.add(variant(null, attributes, 99000));
        }
        // Nhãn nhập tay thắng thuộc tính
        variants.add(variant("Combo  2 áo", Map.of("Size", "Free"), 189000));
        Map<String, Object> body = product("Áo basic", variants);

        String response = create(owner, UUID.randomUUID().toString(), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants.length()").value(5))
                .andExpect(jsonPath("$.variants[0].name").value("S / Đen"))
                .andExpect(jsonPath("$.variants[0].attributes.Màu").value("Đen"))
                .andExpect(jsonPath("$.variants[1].name").value("M / Đen"))
                .andExpect(jsonPath("$.variants[2].name").value("L / Đen"))
                .andExpect(jsonPath("$.variants[3].name").value("XL / Đen"))
                .andExpect(jsonPath("$.variants[4].name").value("Combo  2 áo"))
                .andExpect(jsonPath("$.priceRange.min").value(99000))
                .andExpect(jsonPath("$.priceRange.max").value(189000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // SKU tự sinh không trùng nhau
        JsonNode created = json.readTree(response);
        List<String> skus = new ArrayList<>();
        created.get("variants").forEach(v -> skus.add(v.get("sku").asString()));
        assertThat(skus).doesNotHaveDuplicates().allMatch(sku -> sku.matches(SKU_GENERATED));
        assertThat(events.stream(VariantCreated.class)).hasSize(5);
    }

    @Test
    void productLevelPriceCodesOrOpeningStockWithVariantsAreRejectedPerField() throws Exception {
        Owner owner = newStore();
        for (String field : List.of("salePrice", "sku", "barcode", "openingQuantity", "openingUnitCost")) {
            Map<String, Object> body = tshirt();
            body.put(field, field.endsWith("Price") || field.startsWith("opening") ? 1000 : "MA1");
            create(owner, UUID.randomUUID().toString(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value(field));
        }
        // Ít hơn 2 Biến thể
        create(owner, UUID.randomUUID().toString(), product("Áo", List.of(variant("Đỏ", null, 1000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("variants"));
        create(owner, UUID.randomUUID().toString(), product("Áo", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("variants"));

        // Lỗi của từng Biến thể theo đường dẫn variants[i].<trường>, mọi trường trong một lần
        Map<String, Object> noPrice = variant("Đỏ", null, 1000);
        noPrice.remove("salePrice");
        Map<String, Object> badCodes = variant(null, null, 1000);
        badCodes.put("sku", "s".repeat(51));
        badCodes.put("barcode", "893-1");
        Map<String, Object> tooManyAttributes = variant(null, Map.of("a", "1", "b", "2", "c", "3", "d", "4"), 1000);
        Map<String, Object> openingWithoutCost = variant("Vàng", null, 1000);
        openingWithoutCost.put("openingQuantity", 2);
        create(owner, UUID.randomUUID().toString(),
                product("Áo", List.of(noPrice, badCodes, tooManyAttributes, openingWithoutCost)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("variants[0].salePrice", "variants[1].name",
                        "variants[1].sku", "variants[1].barcode", "variants[2].attributes",
                        "variants[3].openingUnitCost")))
                .andExpect(jsonPath("$.errors.length()").value(6));

        // Thuộc tính thiếu tên, thiếu giá trị, lặp tên (không phân biệt hoa thường); giá trị quá dài
        Map<String, String> repeated = new LinkedHashMap<>();
        repeated.put("Màu", "Đỏ");
        repeated.put("màu", "Xanh");
        for (Map<String, String> attributes : List.of(Map.of("", "Đỏ"), Map.of("Màu", " "), repeated,
                Map.of("Màu", "x".repeat(51)))) {
            create(owner, UUID.randomUUID().toString(),
                    product("Áo", List.of(variant(null, attributes, 1000), variant("Xanh", null, 1000))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("variants[0].attributes"));
        }
        assertThat(productCount(owner)).isZero();
        assertThat(events.stream(VariantCreated.class)).isEmpty();
    }

    @Test
    void twoVariantsWithTheSameLabelInOneProductAreAConflict() throws Exception {
        Owner owner = newStore();
        // Cùng nhãn sau khi ghép thuộc tính, khác hoa thường
        Map<String, Object> body = product("Áo thun", List.of(
                variant("Đỏ / L", null, 150000),
                variant(null, attributes("Màu", "đỏ", "Size", "l"), 150000)));

        create(owner, UUID.randomUUID().toString(), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/variant-name-taken"))
                .andExpect(jsonPath("$.requestId").exists());
        assertThat(productCount(owner)).isZero();
        assertThat(events.stream(VariantCreated.class)).isEmpty();

        // Sản phẩm khác dùng cùng nhãn thì được (SKU tự sinh để không trùng mã)
        for (int i = 0; i < 2; i++) {
            Map<String, Object> shirt = tshirt();
            stripSkus(shirt);
            create(owner, shirt).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.variants[0].name").value("Đỏ / L"));
        }
    }

    @Test
    void aStaffMemberCannotCreateWithVariantsOrChangeAnyVariant() throws Exception {
        Cookie staff = login("dev.nv.a");
        Cookie ownerA = login("dev.chu.a");
        Map<String, Object> body = tshirt();
        body.put("name", "Áo thun Nhân viên không sửa được");
        // SKU nhập tay của tshirt() có thể đã có ở Cửa hàng dev A: để tự sinh
        stripSkus(body);
        mvc.perform(post(PRODUCTS).cookie(staff).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        String response = mvc.perform(post(PRODUCTS).cookie(ownerA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Created created = created(response);
        String variantPath = PRODUCTS + "/" + created.productId() + "/variants";
        String one = variantPath + "/" + created.variantIds().get(1);

        for (MockHttpServletRequestBuilder write : List.of(
                post(variantPath).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(variant("Vàng / S", null, 1000))),
                put(one).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(variant("Xanh / M", null, 1))),
                post(one + "/deactivate"))) {
            mvc.perform(write.cookie(staff))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        }
        // Nhân viên vẫn xem được; không có gì đổi
        mvc.perform(get(PRODUCTS + "/" + created.productId()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[1].salePrice").value(160000))
                .andExpect(jsonPath("$.variants[1].status").value("ACTIVE"));
    }

    @Test
    void moreThanFiftyVariantsOrALabelBuiltFromAttributesOverAHundredCharactersAreRejected() throws Exception {
        Owner owner = newStore();
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(variant("Cỡ " + i, null, 1000));
        }
        create(owner, product("Áo nhiều cỡ", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("variants"));

        // Ba giá trị 50 ký tự ghép bằng " / " thành 156 ký tự: quá 100 ký tự của nhãn
        Map<String, String> longValues = attributes("Màu", "x".repeat(50), "Size", "y".repeat(50), "Chất liệu",
                "z".repeat(50));
        create(owner, product("Áo nhãn dài", List.of(variant("Ngắn", null, 1000), variant(null, longValues, 1000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("variants[1].name"));
        // Đúng 50 biến thể thì được
        create(owner, product("Áo nhiều cỡ", tooMany.subList(0, 50)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants.length()").value(50));
        assertThat(productCount(owner)).isEqualTo(1);
    }

    // --- Thêm Biến thể -------------------------------------------------------------------------------------------

    @Test
    void addingAVariantToASingleVariantProductLabelsTheExistingOneAndOpensStock() throws Exception {
        Owner owner = newStore();
        Created jacket = createSingle(owner, "Áo khoác", 200000);
        UUID existing = jacket.variantIds().getFirst();
        Instant addedAt = clock.freezeAt(Duration.ofMinutes(10));
        Map<String, Object> body = variant("Size M", null, 210000);
        body.put("existingVariantName", " Size S ");
        body.put("openingQuantity", 4);
        body.put("openingUnitCost", 100000);
        String key = UUID.randomUUID().toString();

        String response = addVariant(owner, jacket.productId(), key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(jacket.productId().toString()))
                .andExpect(jsonPath("$.hasVariants").value(true))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[0].id").value(existing.toString()))
                .andExpect(jsonPath("$.variants[0].name").value("Size S"))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.variants[0].salePrice").value(200000))
                .andExpect(jsonPath("$.variants[1].name").value("Size M"))
                .andExpect(jsonPath("$.variants[1].isDefault").value(false))
                .andExpect(jsonPath("$.variants[1].salePrice").value(210000))
                .andExpect(jsonPath("$.variants[1].sku").value(matchesPattern(SKU_GENERATED)))
                .andExpect(jsonPath("$.priceRange.min").value(200000))
                .andExpect(jsonPath("$.priceRange.max").value(210000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
        UUID added = created(response).variantIds().get(1);

        assertThat(events.stream(VariantCreated.class)).containsExactly(
                new VariantCreated(owner.storeId(), existing, 0, 0),
                new VariantCreated(owner.storeId(), added, 4, 100000));
        assertThat(stockOf(owner, added)).containsEntry("quantity", 4).containsEntry("is_active", true);
        assertThat(openingLedger(owner, added)).isEqualTo(4);
        Map<String, Object> row = productRow(owner, jacket.productId());
        assertThat(row).containsEntry("has_variants", true);
        assertThat(instant(row.get("changed_at"))).isEqualTo(addedAt);
        assertThat(instant(row.get("updated_at"))).isEqualTo(addedAt);
        assertThat(searchText(owner, jacket.productId())).isEqualTo("Áo khoác Size S Size M");

        // Gửi lại cùng khóa, cùng nội dung: không thêm lần hai; khác nội dung: dùng lại khóa
        addVariant(owner, jacket.productId(), key, body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.variants.length()").value(2));
        assertThat(events.stream(VariantCreated.class)).hasSize(2);
        body.put("salePrice", 220000);
        addVariant(owner, jacket.productId(), key, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));

        // Sản phẩm đã nhiều Biến thể: không cần nhãn Biến thể hiện có (gửi kèm thì bỏ qua). Giờ đứng yên phải nhích lên:
        // Biến thể xếp theo ngày tạo, hai lần thêm cùng một mốc giờ thì thứ tự theo id
        clock.freezeAt(Duration.ofMinutes(11));
        Map<String, Object> third = variant(null, attributes("Size", "L"), 220000);
        third.put("existingVariantName", "Không dùng");
        addVariant(owner, jacket.productId(), UUID.randomUUID().toString(), third)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[0].name").value("Size S"))
                .andExpect(jsonPath("$.variants[2].name").value("L"));
    }

    @Test
    void addingWithoutExistingVariantNameOrKeyOrWithATakenLabelChangesNothing() throws Exception {
        Owner owner = newStore();
        Created jacket = createSingle(owner, "Áo khoác", 200000);
        Map<String, Object> before = productRow(owner, jacket.productId());

        addVariant(owner, jacket.productId(), UUID.randomUUID().toString(), variant("Size M", null, 210000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("existingVariantName"));
        // Dữ liệu sai báo cùng lượt, trước thiếu khóa
        Map<String, Object> noLabel = variant(null, null, 210000);
        mvc.perform(post(PRODUCTS + "/" + jacket.productId() + "/variants").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(noLabel)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("existingVariantName", "name")));
        Map<String, Object> valid = variant("Size M", null, 210000);
        valid.put("existingVariantName", "Size S");
        mvc.perform(post(PRODUCTS + "/" + jacket.productId() + "/variants").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        // Nhãn mới trùng nhãn đặt cho Biến thể hiện có
        Map<String, Object> sameLabel = variant("size s", null, 210000);
        sameLabel.put("existingVariantName", "Size S");
        addVariant(owner, jacket.productId(), UUID.randomUUID().toString(), sameLabel)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/variant-name-taken"));

        assertThat(productRow(owner, jacket.productId())).isEqualTo(before).containsEntry("has_variants", false);
        send(owner, get(PRODUCTS + "/" + jacket.productId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].name").isEmpty());
        assertThat(events.stream(VariantCreated.class)).hasSize(1);

        // Sản phẩm không có, của Cửa hàng khác: 404
        addVariant(owner, UUID.randomUUID(), UUID.randomUUID().toString(), valid)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        Owner other = newStore();
        addVariant(other, jacket.productId(), UUID.randomUUID().toString(), valid)
                .andExpect(status().isNotFound());
    }

    // --- Sửa Biến thể --------------------------------------------------------------------------------------------

    @Test
    void changingOneVariantPriceWritesOnePriceChangeLineAndKeepsTheChangeFeedCursor() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID blue = shirt.variantIds().get(1);
        Instant before = instant(productRow(owner, shirt.productId()).get("changed_at"));
        Instant editedAt = clock.freezeAt(Duration.ofMinutes(10));
        Map<String, Object> body = variant(null, attributes("Màu", "Xanh", "Size", "M"), 165000);
        body.put("sku", " ao-xanh-m ");
        body.put("barcode", "8930000000002");

        updateVariant(owner, shirt.productId(), blue, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].id").value(blue.toString()))
                .andExpect(jsonPath("$.variants[1].name").value("Xanh / M"))
                .andExpect(jsonPath("$.variants[1].salePrice").value(165000))
                .andExpect(jsonPath("$.variants[1].sku").value("AO-XANH-M"))
                .andExpect(jsonPath("$.variants[1].barcode").value("8930000000002"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(150000))
                .andExpect(jsonPath("$.priceRange.max").value(165000));

        List<Map<String, Object>> audit = auditRows(owner, blue);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("action", "catalog.variant.price_change")
                .containsEntry("entity", "product_variant");
        assertThat(json.readTree((String) audit.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"salePrice\":160000}"));
        assertThat(json.readTree((String) audit.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"salePrice\":165000}"));
        // Chỉ đổi giá, mã: changed_at giữ nguyên, updated_at lấy giờ mới
        Map<String, Object> row = productRow(owner, shirt.productId());
        assertThat(instant(row.get("changed_at"))).isEqualTo(before);
        assertThat(instant(row.get("updated_at"))).isEqualTo(editedAt);

        // Y nguyên (sau chuẩn hóa, SKU trống là giữ): không ghi gì lần hai
        body.put("sku", "");
        clock.freezeAt(Duration.ofMinutes(20));
        updateVariant(owner, shirt.productId(), blue, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].sku").value("AO-XANH-M"));
        assertThat(auditRows(owner, blue)).hasSize(1);
        assertThat(instant(productRow(owner, shirt.productId()).get("updated_at"))).isEqualTo(editedAt);

        // Mã vạch trống thì xóa; trùng SKU với Biến thể khác: 409
        body.remove("barcode");
        updateVariant(owner, shirt.productId(), blue, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].barcode").isEmpty());
        body.put("sku", "ao-do-l");
        updateVariant(owner, shirt.productId(), blue, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/sku-taken"));
    }

    @Test
    void renamingAVariantMovesTheChangeFeedAndItsSearchText() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID red = shirt.variantIds().getFirst();
        assertThat(searchText(owner, shirt.productId())).isEqualTo("Áo thun Đỏ / L Đỏ L Xanh / M Xanh M");
        Instant renamedAt = clock.freezeAt(Duration.ofMinutes(10));

        updateVariant(owner, shirt.productId(), red, variant("Đỏ đô / L", attributes("Màu", "Đỏ đô", "Size", "L"),
                150000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].name").value("Đỏ đô / L"))
                .andExpect(jsonPath("$.variants[0].attributes.Màu").value("Đỏ đô"));

        assertThat(searchText(owner, shirt.productId())).isEqualTo("Áo thun Đỏ đô / L Đỏ đô L Xanh / M Xanh M");
        assertThat(instant(productRow(owner, shirt.productId()).get("changed_at"))).isEqualTo(renamedAt);
        assertThat(auditRows(owner, red)).isEmpty();

        // Trùng nhãn của Biến thể khác cùng Sản phẩm (khác hoa thường)
        updateVariant(owner, shirt.productId(), red, variant("XANH / m", null, 150000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/variant-name-taken"));
        // Thiếu nhãn lẫn thuộc tính: 400 (báo trước 404)
        updateVariant(owner, shirt.productId(), UUID.randomUUID(), variant(" ", null, 150000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void aSingleVariantProductHasNoLabelAndKeepsWorkingWithTheProductForm() throws Exception {
        Owner owner = newStore();
        Created pen = createSingle(owner, "Bút bi", 5000);
        UUID only = pen.variantIds().getFirst();

        updateVariant(owner, pen.productId(), only, variant("Xanh", null, 6000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        updateVariant(owner, pen.productId(), only, variant(null, Map.of("Màu", "Xanh"), 6000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("attributes"));
        updateVariant(owner, pen.productId(), only, variant(null, null, 6000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasVariants").value(false))
                .andExpect(jsonPath("$.variants[0].name").isEmpty())
                .andExpect(jsonPath("$.variants[0].salePrice").value(6000));
        assertThat(auditRows(owner, only)).hasSize(1);
    }

    @Test
    void editingAMultiVariantProductWithoutPriceOrCodesKeepsEveryVariant() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = tshirt();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) body.get("variants")).getFirst();
        first.put("barcode", "8930000000001");
        Created shirt = createOk(owner, body);
        Instant editedAt = clock.freezeAt(Duration.ofMinutes(10));

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "Áo thun cổ tròn");
        edit.put("unit", "chiếc");
        send(owner, put(PRODUCTS + "/" + shirt.productId()), json.writeValueAsString(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Áo thun cổ tròn"))
                .andExpect(jsonPath("$.unit").value("chiếc"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(150000))
                .andExpect(jsonPath("$.variants[0].sku").value("AO-DO-L"))
                .andExpect(jsonPath("$.variants[0].barcode").value("8930000000001"))
                .andExpect(jsonPath("$.variants[1].salePrice").value(160000));
        assertThat(instant(productRow(owner, shirt.productId()).get("changed_at"))).isEqualTo(editedAt);
        assertThat(auditRows(owner, shirt.variantIds().getFirst())).isEmpty();
    }

    @Test
    void aStoppedVariantCanBeRenamedSoANewVariantTakesItsOldLabel() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID blue = shirt.variantIds().get(1);
        deactivateVariant(owner, shirt.productId(), blue).andExpect(status().isOk());
        // Biến thể đã ngừng vẫn giữ nhãn
        addVariant(owner, shirt.productId(), UUID.randomUUID().toString(), variant("Xanh / M", null, 165000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/variant-name-taken"));

        updateVariant(owner, shirt.productId(), blue, variant("Xanh / M (lô cũ)", attributes("Màu", "Xanh", "Size", "M"),
                160000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].id").value(blue.toString()))
                .andExpect(jsonPath("$.variants[1].name").value("Xanh / M (lô cũ)"))
                .andExpect(jsonPath("$.variants[1].status").value("INACTIVE"));

        addVariant(owner, shirt.productId(), UUID.randomUUID().toString(), variant("Xanh / M", null, 165000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[2].name").value("Xanh / M"))
                .andExpect(jsonPath("$.variants[2].status").value("ACTIVE"));
        // Đổi nhãn không mở bán lại Biến thể đã ngừng
        assertThat(stockOf(owner, blue)).containsEntry("is_active", false);
    }

    // --- Ngừng Biến thể ------------------------------------------------------------------------------------------

    @Test
    void stoppingTheDefaultVariantMakesTheEarliestActiveOneTheDefault() throws Exception {
        Owner owner = newStore();
        List<Map<String, Object>> variants = List.of(variant("S", null, 100000), variant("M", null, 110000),
                variant("L", null, 120000));
        Created shirt = createOk(owner, product("Áo sơ mi", variants));
        UUID small = shirt.variantIds().get(0);
        UUID medium = shirt.variantIds().get(1);
        UUID large = shirt.variantIds().get(2);
        Instant stoppedAt = clock.freezeAt(Duration.ofMinutes(10));

        deactivateVariant(owner, shirt.productId(), small)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[0].id").value(medium.toString()))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.variants[1].id").value(small.toString()))
                .andExpect(jsonPath("$.variants[1].isDefault").value(false))
                .andExpect(jsonPath("$.variants[1].status").value("INACTIVE"))
                .andExpect(jsonPath("$.variants[2].id").value(large.toString()))
                .andExpect(jsonPath("$.priceRange.min").value(110000))
                .andExpect(jsonPath("$.priceRange.max").value(120000));

        assertThat(events.stream(VariantDeactivated.class))
                .containsExactly(new VariantDeactivated(owner.storeId(), small));
        // Listener thật của inventory
        assertThat(stockOf(owner, small)).containsEntry("is_active", false);
        assertThat(stockOf(owner, medium)).containsEntry("is_active", true);
        List<Map<String, Object>> audit = auditRows(owner, small);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("action", "catalog.variant.deactivate")
                .containsEntry("entity", "product_variant");
        assertThat(json.readTree((String) audit.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"status\":\"ACTIVE\"}"));
        assertThat(json.readTree((String) audit.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"status\":\"INACTIVE\"}"));
        assertThat(instant(productRow(owner, shirt.productId()).get("changed_at"))).isEqualTo(stoppedAt);
        // Hàng bán được: chỉ còn hai Biến thể
        assertThat(asStore(owner, () -> variantLookup.getForSale(List.of(small, medium, large))))
                .extracting(SellableVariant::variantId).containsExactly(medium, large);

        // Gọi lại: trả trạng thái hiện tại, không phát sự kiện, không ghi nhật ký lần hai
        clock.freezeAt(Duration.ofMinutes(20));
        deactivateVariant(owner, shirt.productId(), small).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].status").value("INACTIVE"));
        assertThat(events.stream(VariantDeactivated.class)).hasSize(1);
        assertThat(auditRows(owner, small)).hasSize(1);
        assertThat(instant(productRow(owner, shirt.productId()).get("changed_at"))).isEqualTo(stoppedAt);
    }

    @Test
    void theLastVariantOnSaleCannotBeStoppedAlone() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID red = shirt.variantIds().get(0);
        UUID blue = shirt.variantIds().get(1);
        deactivateVariant(owner, shirt.productId(), blue).andExpect(status().isOk());

        deactivateVariant(owner, shirt.productId(), red)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/last-active-variant"))
                .andExpect(jsonPath("$.requestId").exists());
        send(owner, get(PRODUCTS + "/" + shirt.productId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].id").value(red.toString()))
                .andExpect(jsonPath("$.variants[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true));
        assertThat(events.stream(VariantDeactivated.class)).hasSize(1);

        // Sản phẩm một Biến thể: Biến thể duy nhất cũng là Biến thể đang bán cuối cùng
        Created pen = createSingle(owner, "Bút bi", 5000);
        deactivateVariant(owner, pen.productId(), pen.variantIds().getFirst())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/last-active-variant"));
    }

    @Test
    void everyVariantOperationOnAStoppedProductIsAConflict() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        send(owner, post(PRODUCTS + "/" + shirt.productId() + "/deactivate")).andExpect(status().isOk());
        UUID blue = shirt.variantIds().get(1);

        addVariant(owner, shirt.productId(), UUID.randomUUID().toString(), variant("Vàng / S", null, 1000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));
        updateVariant(owner, shirt.productId(), blue, variant("Xanh / M", null, 1000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));
        deactivateVariant(owner, shirt.productId(), blue)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));
        send(owner, get(PRODUCTS + "/" + shirt.productId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.priceRange").isEmpty());
    }

    @Test
    void aVariantOfAnotherProductOrStoreIsNotFound() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        Created other = createOk(owner, product("Quần", List.of(variant("28", null, 1000),
                variant("30", null, 1000))));
        UUID otherVariant = other.variantIds().get(1);

        updateVariant(owner, shirt.productId(), otherVariant, variant("31", null, 1000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        deactivateVariant(owner, shirt.productId(), otherVariant).andExpect(status().isNotFound());
        Owner stranger = newStore();
        updateVariant(stranger, other.productId(), otherVariant, variant("31", null, 1000))
                .andExpect(status().isNotFound());
        deactivateVariant(stranger, other.productId(), otherVariant).andExpect(status().isNotFound());
        send(owner, get(PRODUCTS + "/" + other.productId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[1].name").value("30"))
                .andExpect(jsonPath("$.variants[1].status").value("ACTIVE"));
    }

    // --- Hàng bán được ---------------------------------------------------------------------------------------------

    @Test
    void eachActiveVariantIsSellableOnItsOwnRow() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID red = shirt.variantIds().get(0);
        UUID blue = shirt.variantIds().get(1);

        List<SellableVariant> sellable = asStore(owner, () -> variantLookup.getForSale(List.of(blue, red)));
        assertThat(sellable).extracting(SellableVariant::variantId).containsExactly(blue, red);
        assertThat(sellable).extracting(SellableVariant::variantName).containsExactly("Xanh / M", "Đỏ / L");
        assertThat(sellable).extracting(SellableVariant::salePrice).containsExactly(160000L, 150000L);

        send(owner, get("/api/v1/sales/sellable").param("q", "Áo thun")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].variantId").value(red.toString()))
                .andExpect(jsonPath("$.items[0].variantName").value("Đỏ / L"))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[1].variantId").value(blue.toString()))
                .andExpect(jsonPath("$.items[1].variantName").value("Xanh / M"))
                .andExpect(jsonPath("$.items[1].quantity").value(3));
    }

    @Test
    void searchingFindsTheVariantLabelInTheProductListAndForSale() throws Exception {
        Owner owner = newStore();
        Created shirt = createOk(owner, tshirt());
        UUID red = shirt.variantIds().get(0);
        UUID blue = shirt.variantIds().get(1);
        createOk(owner, product("Quần", List.of(variant("28", null, 1000), variant("30", null, 1000))));

        // Danh sách quản lý (PRODUCTS): nhãn của bất kỳ Biến thể nào, chứa, không cần dấu; vẫn một dòng mỗi Sản phẩm
        send(owner, get(PRODUCTS).param("q", "xanh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(shirt.productId().toString()))
                .andExpect(jsonPath("$.items[0].variants.length()").value(2));
        send(owner, get(PRODUCTS).param("q", "do / l")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(shirt.productId().toString()));
        send(owner, get(PRODUCTS).param("q", "vang")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // Hàng bán được (SELLABLE): nhãn của chính Biến thể, nên ra đúng dòng
        assertThat(asStore(owner, () -> variantLookup.searchForSale("xanh", null, null, null, 0, 20)).items())
                .extracting(SellableVariant::variantId).containsExactly(blue);
        assertThat(asStore(owner, () -> variantLookup.searchForSale("ĐỎ", null, null, null, 0, 20)).items())
                .extracting(SellableVariant::variantId).containsExactly(red);
        send(owner, get("/api/v1/sales/sellable").param("q", "xanh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(blue.toString()))
                .andExpect(jsonPath("$.items[0].variantName").value("Xanh / M"));
        // Tên Sản phẩm vẫn ra mọi Biến thể của nó
        assertThat(asStore(owner, () -> variantLookup.searchForSale("ao thun", null, null, null, 0, 20)).items())
                .extracting(SellableVariant::variantId).containsExactly(red, blue);
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        TestClock variantTestClock() {
            return new TestClock();
        }
    }

    /** Áo thun Đỏ/L 150 000 tồn 5 (SKU nhập tay), Xanh/M 160 000 tồn 3 (SKU tự sinh). */
    static Map<String, Object> tshirt() {
        Map<String, Object> red = variant(null, attributes("Màu", "Đỏ", "Size", "L"), 150000);
        red.put("sku", "ao-do-l");
        red.put("openingQuantity", 5);
        red.put("openingUnitCost", 70000);
        Map<String, Object> blue = variant(null, attributes("Màu", "Xanh", "Size", "M"), 160000);
        blue.put("openingQuantity", 3);
        blue.put("openingUnitCost", 72000);
        return product("Áo thun", new ArrayList<>(List.of(red, blue)));
    }

    static Map<String, Object> product(String name, List<Map<String, Object>> variants) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("variants", variants);
        return body;
    }

    /** Một Biến thể; {@code name}, {@code attributes} {@code null} thì không gửi. */
    static Map<String, Object> variant(String name, Map<String, String> attributes, long salePrice) {
        Map<String, Object> variant = new LinkedHashMap<>();
        if (name != null) {
            variant.put("name", name);
        }
        if (attributes != null) {
            variant.put("attributes", attributes);
        }
        variant.put("salePrice", salePrice);
        return variant;
    }

    /** Thuộc tính giữ đúng thứ tự (tên, giá trị, tên, giá trị…). */
    static Map<String, String> attributes(String... pairs) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1]);
        }
        return attributes;
    }

    @SuppressWarnings("unchecked")
    static void stripSkus(Map<String, Object> body) {
        ((List<Map<String, Object>>) body.get("variants")).forEach(v -> v.remove("sku"));
    }

    ResultActions create(Owner owner, Map<String, Object> body) throws Exception {
        return create(owner, UUID.randomUUID().toString(), body);
    }

    ResultActions create(Owner owner, String idempotencyKey, Map<String, Object> body) throws Exception {
        return send(owner, post(PRODUCTS).header("Idempotency-Key", idempotencyKey), json.writeValueAsString(body));
    }

    /** Tạo (phải {@code 201}) và đọc Sản phẩm vừa tạo. */
    Created createOk(Owner owner, Map<String, Object> body) throws Exception {
        return created(create(owner, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    Created createSingle(Owner owner, String name, long salePrice) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", salePrice);
        String response = send(owner, post(PRODUCTS).header("Idempotency-Key", UUID.randomUUID().toString()),
                json.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return created(response);
    }

    Created created(String response) {
        JsonNode body = json.readTree(response);
        List<UUID> variantIds = new ArrayList<>();
        body.get("variants").forEach(v -> variantIds.add(UUID.fromString(v.get("id").asString())));
        return new Created(UUID.fromString(body.get("id").asString()), List.copyOf(variantIds), body);
    }

    ResultActions addVariant(Owner owner, UUID productId, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        return send(owner, post(PRODUCTS + "/" + productId + "/variants").header("Idempotency-Key", idempotencyKey),
                json.writeValueAsString(body));
    }

    ResultActions updateVariant(Owner owner, UUID productId, UUID variantId, Map<String, Object> body)
            throws Exception {
        return send(owner, put(PRODUCTS + "/" + productId + "/variants/" + variantId), json.writeValueAsString(body));
    }

    ResultActions deactivateVariant(Owner owner, UUID productId, UUID variantId) throws Exception {
        return send(owner, post(PRODUCTS + "/" + productId + "/variants/" + variantId + "/deactivate"));
    }

    String detail(Owner owner, UUID productId) throws Exception {
        return send(owner, get(PRODUCTS + "/" + productId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    ResultActions send(Owner owner, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.cookie(owner.cookie()));
    }

    ResultActions send(Owner owner, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    Map<String, Object> stockOf(Owner owner, UUID variantId) {
        return asStore(owner, () -> jdbc.queryForMap(
                "select quantity, avg_unit_cost, is_active from app.stock where variant_id = ?", variantId));
    }

    /** Tổng số lượng Tồn đầu kỳ ({@code OPENING}) trong sổ kho của Biến thể. */
    Integer openingLedger(Owner owner, UUID variantId) {
        return asStore(owner, () -> jdbc.queryForObject("""
                select coalesce(sum(delta), 0)::int from app.stock_ledger
                where variant_id = ? and document_type = 'OPENING'
                """, Integer.class, variantId));
    }

    Map<String, Object> productRow(Owner owner, UUID productId) {
        return asStore(owner, () -> jdbc.queryForMap(
                "select has_variants, status, updated_at, changed_at from app.product where id = ?", productId));
    }

    String searchText(Owner owner, UUID productId) {
        return asStore(owner, () -> jdbc.queryForObject(
                "select search_text from app.catalog_product_changes where product_id = ?", String.class, productId));
    }

    List<Map<String, Object>> auditRows(Owner owner, UUID variantId) {
        return asStore(owner, () -> jdbc.queryForList("""
                select action, entity, before::text as before, after::text as after
                from app.audit_log where entity_id = ? order by created_at
                """, variantId.toString()));
    }

    long productCount(Owner owner) {
        return asStore(owner, () -> jdbc.queryForObject("select count(*) from app.product", Long.class));
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
        body.put("storeName", "Cửa hàng thử Biến thể");
        body.put("loginKey", "bienthe." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        UUID storeId = UUID.fromString(json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("storeId").asString());
        return new Owner(response.getCookie("access_token"), storeId);
    }

    Cookie login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    <T> T asStore(Owner owner, Supplier<T> work) {
        return TenantScope.runAs(owner.storeId(), () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
