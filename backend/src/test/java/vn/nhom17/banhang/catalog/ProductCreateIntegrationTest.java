package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
 * Thêm Sản phẩm (FR-1, SP-01) trên PostgreSQL thật: toàn bộ ma trận I/O của spec, kèm kiểm {@link VariantCreated}
 * phát đúng một lần, đúng số, trong transaction tạo Sản phẩm (AD-14). Mỗi test dùng Cửa hàng mới đăng ký riêng.
 *
 * <p>{@link BeforeCommitProbe} đứng vào chỗ listener {@code BEFORE_COMMIT} của {@code inventory}: ghi lại sự kiện nó
 * nhận, lúc đó có đang trong transaction không, và đã thấy dòng {@code product_variant} chưa (khóa ngoại của
 * {@code stock}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import({TestcontainersConfiguration.class, ProductCreateIntegrationTest.ProbeConfig.class})
class ProductCreateIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";
    static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** "ệ" gõ dạng tổ hợp NFD: 3 code point (e + dấu nặng + dấu mũ), sau NFC là 1 ký tự. */
    static final String NFD_E = Normalizer.normalize("ệ", Normalizer.Form.NFD);
    /** Emoji ngoài BMP: 2 đơn vị UTF-16, 1 ký tự. */
    static final String EMOJI = "\ud83d\ude00";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired ApplicationEvents events;
    @Autowired BeforeCommitProbe probe;

    record Owner(Cookie cookie, UUID storeId) {
    }

    @BeforeEach
    void clearProbe() {
        probe.deliveries.clear();
    }

    // --- Thêm đủ, tối thiểu -------------------------------------------------------------------------------

    @Test
    void createWithEveryFieldReturnsTheDetailWithOneDefaultVariantAndPublishesTheOpeningStock() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        Map<String, Object> body = body("  Sữa tươi Vinamilk 1L ", 15000);
        body.put("categoryId", drinks.toString());
        body.put("unit", " hộp ");
        body.put("description", "Dòng 1\r\nDòng 2");
        body.put("sku", "VNM-1L");
        body.put("barcode", "8934673123456");
        body.put("openingQuantity", 24);
        body.put("openingUnitCost", 11000);

        String key = UUID.randomUUID().toString();
        String response = create(owner, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(PRODUCTS + "/" + UUID_REGEX)))
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.name").value("Sữa tươi Vinamilk 1L"))
                .andExpect(jsonPath("$.categoryId").value(drinks.toString()))
                .andExpect(jsonPath("$.categoryName").value("Đồ uống"))
                .andExpect(jsonPath("$.unit").value("hộp"))
                .andExpect(jsonPath("$.description").value("Dòng 1\nDòng 2"))
                .andExpect(jsonPath("$.hasVariants").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].id").value(matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.variants[0].name").isEmpty())
                .andExpect(jsonPath("$.variants[0].sku").value("VNM-1L"))
                .andExpect(jsonPath("$.variants[0].barcode").value("8934673123456"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(15000))
                .andExpect(jsonPath("$.variants[0].status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // AD-15, AD-16: không có Giá vốn hay tồn đầu kỳ trong phản hồi (Nhân viên cũng đọc DTO này)
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");

        JsonNode created = json.readTree(response);
        UUID productId = UUID.fromString(created.get("id").asString());
        UUID variantId = UUID.fromString(created.get("variants").get(0).get("id").asString());
        assertThat(variantCreated()).containsExactly(new VariantCreated(owner.storeId(), variantId, 24, 11000));
        assertThat(probe.deliveries).containsExactly(new Delivery(
                new VariantCreated(owner.storeId(), variantId, 24, 11000), true, true));

        Map<String, Object> product = asStore(owner.storeId(), () -> jdbc.queryForMap("""
                select store_id, category_id, name, unit, has_variants, status, created_at, updated_at, changed_at
                from app.product where id = ?
                """, productId));
        assertThat(product).containsEntry("store_id", owner.storeId()).containsEntry("category_id", drinks)
                .containsEntry("unit", "hộp").containsEntry("has_variants", false).containsEntry("status", "ACTIVE");
        // AD-12: changed_at là thời điểm tạo (cùng mốc Clock với created_at)
        assertThat(product.get("changed_at")).isEqualTo(product.get("created_at")).isEqualTo(product.get("updated_at"));
        Map<String, Object> variant = asStore(owner.storeId(), () -> jdbc.queryForMap("""
                select product_id, sku, barcode, sale_price, is_default, status, name, attributes::text as attributes
                from app.product_variant where id = ?
                """, variantId));
        assertThat(variant).containsEntry("product_id", productId).containsEntry("sku", "VNM-1L")
                .containsEntry("sale_price", 15000L).containsEntry("is_default", true)
                .containsEntry("attributes", "{}").containsEntry("name", null);

        // GET chi tiết trả đúng như lúc tạo
        String detail = send(owner, get(PRODUCTS + "/" + productId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(detail)).isEqualTo(created);
    }

    @Test
    void createWithOnlyNameAndPriceUsesTheDefaults() throws Exception {
        Owner owner = newStore();
        String response = create(owner, body("Bút bi", 5000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("cái"))
                .andExpect(jsonPath("$.categoryId").isEmpty())
                .andExpect(jsonPath("$.categoryName").isEmpty())
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.variants[0].barcode").isEmpty())
                .andExpect(jsonPath("$.variants[0].sku").value(matchesPattern("SP-[0-9A-F]{8}")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode variant = json.readTree(response).get("variants").get(0);
        UUID variantId = UUID.fromString(variant.get("id").asString());
        // SKU tự sinh = "SP-" + 8 ký tự đầu của id Biến thể, viết hoa
        assertThat(variant.get("sku").asString())
                .isEqualTo("SP-" + variantId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
        assertThat(variantCreated()).containsExactly(new VariantCreated(owner.storeId(), variantId, 0, 0));

        // Chuỗi trống hoặc chỉ khoảng trắng cũng là bỏ trống
        Map<String, Object> blanks = body("Thước kẻ", 0);
        blanks.put("unit", "  ");
        blanks.put("sku", "\u00a0");
        blanks.put("barcode", "");
        blanks.put("description", " \n ");
        blanks.put("categoryId", null);
        create(owner, blanks).andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("cái"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.variants[0].salePrice").value(0))
                .andExpect(jsonPath("$.variants[0].barcode").isEmpty())
                .andExpect(jsonPath("$.variants[0].sku").value(matchesPattern("SP-[0-9A-F]{8}")));
    }

    // --- Trùng mã vạch, SKU -------------------------------------------------------------------------------

    @Test
    void duplicateBarcodeInTheSameStoreIsRejectedWithoutAnEvent() throws Exception {
        Owner owner = newStore();
        Map<String, Object> first = body("Bia Hà Nội", 12000);
        first.put("barcode", "8935001800019");
        create(owner, first).andExpect(status().isCreated());

        Map<String, Object> second = body("Bia khác", 13000);
        second.put("barcode", " 8935001800019 ");
        second.put("openingQuantity", 10);
        second.put("openingUnitCost", 9000);
        create(owner, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/barcode-taken"))
                .andExpect(jsonPath("$.requestId").exists());

        assertThat(productCount(owner.storeId())).isEqualTo(1);
        assertThat(variantCreated()).hasSize(1);
        assertThat(probe.deliveries).hasSize(1);
    }

    @Test
    void anotherStoreMayUseTheSameBarcodeAndSku() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Map<String, Object> ofB = body("Mì Hảo Hảo", 4000);
        ofB.put("barcode", "8934563138165");
        ofB.put("sku", "MI-HH");
        create(b, ofB).andExpect(status().isCreated());

        Map<String, Object> ofA = body("Mì Hảo Hảo tôm chua cay", 4500);
        ofA.put("barcode", "8934563138165");
        ofA.put("sku", "MI-HH");
        create(a, ofA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].barcode").value("8934563138165"))
                .andExpect(jsonPath("$.variants[0].sku").value("MI-HH"));
    }

    @Test
    void duplicateSkuInTheSameStoreIsRejected() throws Exception {
        Owner owner = newStore();
        Map<String, Object> first = body("Nước suối", 5000);
        first.put("sku", "NS-500");
        create(owner, first).andExpect(status().isCreated());

        Map<String, Object> second = body("Nước suối Lavie", 6000);
        second.put("sku", "NS-500");
        create(owner, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/sku-taken"));
        assertThat(productCount(owner.storeId())).isEqualTo(1);
        assertThat(variantCreated()).hasSize(1);
    }

    @Test
    void skuAndBarcodeAreStoredUpperCaseSoTheyAreUniqueIgnoringCase() throws Exception {
        Owner owner = newStore();
        Map<String, Object> first = body("Hàng chữ thường", 1000);
        first.put("sku", " abc-1 ");
        first.put("barcode", "abc123");
        create(owner, first).andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].sku").value("ABC-1"))
                .andExpect(jsonPath("$.variants[0].barcode").value("ABC123"));

        Map<String, Object> sameSku = body("Hàng chữ hoa", 1000);
        sameSku.put("sku", "ABC-1");
        create(owner, sameSku).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/sku-taken"));
        Map<String, Object> sameBarcode = body("Hàng chữ hoa", 1000);
        sameBarcode.put("barcode", "ABC123");
        create(owner, sameBarcode).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/barcode-taken"));
        assertThat(productCount(owner.storeId())).isEqualTo(1);
    }

    @Test
    void codesOfASoftDeletedVariantCanBeReused() throws Exception {
        Owner owner = newStore();
        Map<String, Object> first = body("Kẹo cũ", 1000);
        first.put("sku", "KEO-1");
        first.put("barcode", "1234567890");
        UUID oldProduct = createdId(create(owner, first).andExpect(status().isCreated()));
        asStore(owner.storeId(), () -> jdbc.update(
                "update app.product_variant set deleted_at = now() where product_id = ?", oldProduct));

        create(owner, first).andExpect(status().isCreated());
    }

    // --- Dữ liệu sai --------------------------------------------------------------------------------------------

    @Test
    void aCategoryOfAnotherStoreOrAMissingOneIsAFieldError() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID ofB = createCategory(b, "Hàng của B");

        for (UUID categoryId : List.of(ofB, UUID.randomUUID())) {
            Map<String, Object> body = body("Sản phẩm của A", 10000);
            body.put("categoryId", categoryId.toString());
            create(a, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("categoryId"))
                    .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        }
        // Báo cùng lượt với lỗi của trường khác, không đợi sửa xong các trường kia
        Map<String, Object> alsoNoName = body("  ", 10000);
        alsoNoName.put("categoryId", ofB.toString());
        create(a, alsoNoName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("categoryId"));
        assertThat(productCount(a.storeId())).isZero();
        assertThat(productCount(b.storeId())).isZero();
        assertThat(variantCreated()).isEmpty();
    }

    @Test
    void openingStockNeedsAPositiveUnitCost() throws Exception {
        Owner owner = newStore();
        Map<String, Object> zeroCost = body("Dầu ăn", 50000);
        zeroCost.put("openingQuantity", 5);
        zeroCost.put("openingUnitCost", 0);
        Map<String, Object> noCost = body("Dầu ăn", 50000);
        noCost.put("openingQuantity", 5);
        for (Map<String, Object> body : List.of(zeroCost, noCost)) {
            create(owner, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("openingUnitCost"));
        }
        assertThat(productCount(owner.storeId())).isZero();
        assertThat(variantCreated()).isEmpty();

        // Không có tồn đầu thì Giá vốn ban đầu được để 0 hoặc có giá trị
        Map<String, Object> costWithoutStock = body("Dầu ăn", 50000);
        costWithoutStock.put("openingQuantity", 0);
        costWithoutStock.put("openingUnitCost", 42000);
        UUID variantId = createdVariantId(create(owner, costWithoutStock).andExpect(status().isCreated()));
        assertThat(variantCreated()).containsExactly(new VariantCreated(owner.storeId(), variantId, 0, 42000));
    }

    @Test
    void missingOrInvalidFieldsAreReportedPerField() throws Exception {
        Owner owner = newStore();
        // Thiếu cả tên lẫn giá: báo cả hai trong một lần
        send(owner, post(PRODUCTS).header("Idempotency-Key", UUID.randomUUID().toString()), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("Nhập tên Sản phẩm"))
                .andExpect(jsonPath("$.errors[1].field").value("salePrice"))
                .andExpect(jsonPath("$.errors[1].message").value("Nhập giá bán"));
        // Lỗi trường chung (kiểm chung với khi sửa) và lỗi Tồn kho đầu kỳ cùng một lần, đúng thứ tự trường
        Map<String, Object> noNameNegativeStock = body("", 10000);
        noNameNegativeStock.put("openingQuantity", -3);
        create(owner, noNameNegativeStock)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("openingQuantity"));

        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"name", "   "});
        cases.add(new Object[] {"name", "\u200b\ufeff"});
        cases.add(new Object[] {"name", "a".repeat(201)});
        // NFD: 3 code point thành 1 ký tự sau NFC; emoji: 2 đơn vị UTF-16 là 1 ký tự
        cases.add(new Object[] {"name", NFD_E.repeat(201)});
        cases.add(new Object[] {"name", EMOJI.repeat(201)});
        cases.add(new Object[] {"name", "Bia\u2028Hà Nội"});
        cases.add(new Object[] {"name", "Bia\tHà Nội"});
        cases.add(new Object[] {"unit", "u".repeat(31)});
        cases.add(new Object[] {"unit", "hộp\nlớn"});
        cases.add(new Object[] {"description", "d".repeat(2001)});
        cases.add(new Object[] {"description", "có\u0000NUL"});
        cases.add(new Object[] {"sku", "s".repeat(51)});
        cases.add(new Object[] {"barcode", "1".repeat(51)});
        cases.add(new Object[] {"barcode", "893-456"});
        cases.add(new Object[] {"barcode", "893 456"});
        cases.add(new Object[] {"barcode", "mãvạch"});
        cases.add(new Object[] {"salePrice", -1});
        cases.add(new Object[] {"salePrice", 1500.5});
        cases.add(new Object[] {"salePrice", 9_007_199_254_740_992L});
        cases.add(new Object[] {"openingQuantity", -3});
        cases.add(new Object[] {"openingQuantity", 2.5});
        cases.add(new Object[] {"openingQuantity", 2_147_483_648L});
        cases.add(new Object[] {"openingUnitCost", -100});
        cases.add(new Object[] {"openingUnitCost", 100.25});
        for (Object[] c : cases) {
            Map<String, Object> body = body("Sản phẩm thử", 10000);
            body.put((String) c[0], c[1]);
            create(owner, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value(c[0]))
                    .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        }
        // Surrogate lẻ (chuỗi UTF-16 hỏng): viết thẳng mã thoát JSON vì JsonMapper không ghi được chuỗi này
        String loneSurrogate = "{\"name\":\"a" + "\\" + "ud83d" + "b\",\"salePrice\":1000}";
        send(owner, post(PRODUCTS).header("Idempotency-Key", UUID.randomUUID().toString()), loneSurrogate)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        // Tồn kho đầu kỳ × Giá vốn ban đầu phải còn là số tiền hợp lệ
        Map<String, Object> tooValuable = body("Sản phẩm thử", 10000);
        tooValuable.put("openingQuantity", 2_000_000);
        tooValuable.put("openingUnitCost", 5_000_000_000L);
        create(owner, tooValuable)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("openingUnitCost"));
        assertThat(productCount(owner.storeId())).isZero();
        assertThat(variantCreated()).isEmpty();

        // Khoảng trắng Unicode bên trong thành dấu cách thường
        create(owner, body("Bánh\u00a0mì\u3000que", 5000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Bánh mì que"));
        // NFD và emoji đúng 200 ký tự thì được; tên lưu dạng NFC
        create(owner, body(NFD_E.repeat(200), 1000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ệ".repeat(200)));
        create(owner, body(EMOJI.repeat(200), 1000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(EMOJI.repeat(200)));
        // Tích đúng bằng giới hạn tiền thì được
        Map<String, Object> atMoneyMax = body("Tồn đầu kỳ lớn", 1000);
        atMoneyMax.put("openingQuantity", 1);
        atMoneyMax.put("openingUnitCost", 9_007_199_254_740_991L);
        create(owner, atMoneyMax).andExpect(status().isCreated());

        // Đúng giới hạn thì được; độ dài đếm theo ký tự (code point) sau khi chuẩn hóa
        Map<String, Object> limits = body("ệ".repeat(200), 9_007_199_254_740_991L);
        limits.put("unit", "u".repeat(30));
        limits.put("description", "d".repeat(1000) + "\n\t" + "d".repeat(998));
        limits.put("sku", "s".repeat(50));
        limits.put("barcode", "Ab1".repeat(16) + "Z9");
        limits.put("openingQuantity", Integer.MAX_VALUE);
        limits.put("openingUnitCost", 1);
        create(owner, limits).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ệ".repeat(200)))
                .andExpect(jsonPath("$.variants[0].salePrice").value(9_007_199_254_740_991L));
        // 1500.0 là số nguyên
        create(owner, body("Giá dạng thập phân chẵn", 1500.0)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].salePrice").value(1500));
    }

    // --- Idempotency-Key ------------------------------------------------------------------------------------

    @Test
    void resendingWithTheSameKeyReturnsTheSameProductWithoutASecondEvent() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = body("Gạo ST25 5kg", 180000);
        body.put("openingQuantity", 10);
        body.put("openingUnitCost", 150000);
        String key = UUID.randomUUID().toString();

        String first = create(owner, key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String again = create(owner, key, body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        assertThat(productCount(owner.storeId())).isEqualTo(1);
        assertThat(variantCreated()).hasSize(1);
        assertThat(probe.deliveries).hasSize(1);

        // Cùng khóa nhưng khác nội dung
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("salePrice", 175000);
        create(owner, key, changed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));
        assertThat(productCount(owner.storeId())).isEqualTo(1);
        assertThat(variantCreated()).hasSize(1);
    }

    @Test
    void aFailedAttemptDoesNotUseUpTheKey() throws Exception {
        Owner owner = newStore();
        Map<String, Object> taken = body("Sữa chua", 7000);
        taken.put("sku", "SC-1");
        create(owner, taken).andExpect(status().isCreated());

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = body("Sữa chua nha đam", 8000);
        body.put("sku", "SC-1");
        create(owner, key, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/sku-taken"));
        body.put("sku", "SC-2");
        create(owner, key, body).andExpect(status().isCreated());
        assertThat(productCount(owner.storeId())).isEqualTo(2);
    }

    @Test
    void theKeyIsRequiredAndMustBeAUuid() throws Exception {
        Owner owner = newStore();
        String body = json.writeValueAsString(body("Bánh mì", 15000));
        send(owner, post(PRODUCTS), body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        for (String key : List.of("", "khong-phai-uuid", "1-1-1-1-1")) {
            send(owner, post(PRODUCTS).header("Idempotency-Key", key), body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        }
        assertThat(productCount(owner.storeId())).isZero();
        assertThat(variantCreated()).isEmpty();
    }

    // --- Phân quyền, tách dữ liệu, danh sách --------------------------------------------------------------------

    @Test
    void staffCannotCreateButCanReadWithoutCost() throws Exception {
        Cookie staff = login("dev.nv.a");
        Cookie ownerA = login("dev.chu.a");
        mvc.perform(post(PRODUCTS).cookie(staff).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body("NV thêm", 1000))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        assertThat(variantCreated()).isEmpty();

        Map<String, Object> body = body("Hàng Nhân viên xem", 20000);
        body.put("openingQuantity", 3);
        body.put("openingUnitCost", 15000);
        String created = mvc.perform(post(PRODUCTS).cookie(ownerA).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(created).get("id").asString();

        String detail = mvc.perform(get(PRODUCTS + "/" + id).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hàng Nhân viên xem"))
                .andExpect(jsonPath("$.variants[0].salePrice").value(20000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
        // Cửa hàng dev dùng chung giữa các test: sắp mới nhất trước để Sản phẩm vừa thêm đứng đầu
        mvc.perform(get(PRODUCTS + "?sort=createdAt,desc").cookie(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void anotherStoresProductIsNotFound() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID ofB = createdId(create(b, body("Hàng của B", 1000)).andExpect(status().isCreated()));

        send(a, get(PRODUCTS + "/" + ofB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        send(a, get(PRODUCTS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
        send(a, get(PRODUCTS)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        send(b, get(PRODUCTS + "/" + ofB)).andExpect(status().isOk());
    }

    /** Mặc định sắp theo tên không dấu từ SP-05; {@code sort=createdAt,desc} cho thứ tự mới nhất trước của SP-01. */
    @Test
    void listIsPagedAndCanBeNewestFirst() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        List<String> names = List.of("Thứ nhất", "Thứ hai", "Thứ ba");
        for (String name : names) {
            Map<String, Object> body = body(name, 1000);
            body.put("categoryId", drinks.toString());
            create(owner, body).andExpect(status().isCreated());
        }

        send(owner, get(PRODUCTS + "?sort=createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].name").value("Thứ ba"))
                .andExpect(jsonPath("$.items[1].name").value("Thứ hai"))
                .andExpect(jsonPath("$.items[2].name").value("Thứ nhất"))
                .andExpect(jsonPath("$.items[0].categoryName").value("Đồ uống"))
                .andExpect(jsonPath("$.items[0].variants.length()").value(1))
                .andExpect(jsonPath("$.items[0].variants[0].isDefault").value(true));
        send(owner, get(PRODUCTS + "?page=1&size=2&sort=createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Thứ nhất"));
        send(owner, get(PRODUCTS + "?size=500")).andExpect(jsonPath("$.size").value(100));
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Sự kiện {@link BeforeCommitProbe} nhận được, kèm trạng thái lúc nhận. */
    record Delivery(VariantCreated event, boolean inTransaction, boolean variantVisible) {
    }

    /** Đứng vào chỗ listener {@code BEFORE_COMMIT} của inventory (AD-14). */
    public static class BeforeCommitProbe {

        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        private final JdbcTemplate jdbc;

        BeforeCommitProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void on(VariantCreated event) {
            Long variants = jdbc.queryForObject("select count(*) from app.product_variant where id = ?", Long.class,
                    event.variantId());
            deliveries.add(new Delivery(event, TransactionSynchronizationManager.isActualTransactionActive(),
                    variants != null && variants == 1));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        @Bean
        BeforeCommitProbe beforeCommitProbe(JdbcTemplate jdbc) {
            return new BeforeCommitProbe(jdbc);
        }
    }

    List<VariantCreated> variantCreated() {
        return events.stream(VariantCreated.class).toList();
    }

    static Map<String, Object> body(String name, Object salePrice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", salePrice);
        return body;
    }

    ResultActions create(Owner owner, Map<String, Object> body) throws Exception {
        return create(owner, UUID.randomUUID().toString(), body);
    }

    ResultActions create(Owner owner, String idempotencyKey, Map<String, Object> body) throws Exception {
        return send(owner, post(PRODUCTS).header("Idempotency-Key", idempotencyKey), json.writeValueAsString(body));
    }

    UUID createdId(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("id").asString());
    }

    UUID createdVariantId(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("variants").get(0).get("id").asString());
    }

    ResultActions send(Owner owner, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.cookie(owner.cookie()));
    }

    ResultActions send(Owner owner, MockHttpServletRequestBuilder request,
                       String body) throws Exception {
        return mvc.perform(request.cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    UUID createCategory(Owner owner, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/categories").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Sản phẩm");
        body.put("loginKey", "sanpham." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
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

    long productCount(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.product", Long.class));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
