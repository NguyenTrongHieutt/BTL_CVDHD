package vn.nhom17.banhang.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Hợp đồng của {@code GET /api/v1/inventory/ledger} (CB-03 mục 3.3) mà màn KHO-07 (Hiếu, features/catalog) dựa vào,
 * trên PostgreSQL thật. Chỉ kiểm hành vi, không đổi code của inventory. Dòng sổ kho có sẵn nhờ Tồn kho đầu kỳ: thêm
 * Sản phẩm qua API catalog thì listener của inventory ghi một dòng {@code OPENING}. Mỗi test dùng Cửa hàng mới.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StockLedgerContractTest {

    static final String LEDGER = "/api/v1/inventory/ledger";
    static final String PRODUCTS = "/api/v1/catalog/products";
    static final String OWNER_NAME = "Chủ thử sổ kho";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;

    record Owner(Cookie cookie, UUID storeId) {
    }

    @Test
    void anEntryCarriesExactlyTheContractFields() throws Exception {
        Owner owner = newStore();
        UUID milk = createWithOpening(owner, "Sữa tươi", 24, 11000);

        JsonNode page = ledger(owner);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        JsonNode entry = page.get("items").get(0);
        assertThat(entry.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "createdAt", "documentType", "documentId", "variantId", "productName", "variantName", "delta",
                "quantityAfter", "actorName");
        assertThat(entry.get("documentType").asString()).isEqualTo("OPENING");
        assertThat(UUID.fromString(entry.get("documentId").asString())).isNotNull();
        assertThat(entry.get("variantId").asString()).isEqualTo(milk.toString());
        assertThat(entry.get("productName").asString()).isEqualTo("Sữa tươi");
        // Biến thể mặc định: không có tên Biến thể (trường vẫn có, là null)
        assertThat(entry.get("variantName").isNull()).isTrue();
        assertThat(entry.get("delta").asInt()).isEqualTo(24);
        assertThat(entry.get("quantityAfter").asInt()).isEqualTo(24);
        assertThat(entry.get("actorName").asString()).isEqualTo(OWNER_NAME);
        // Thời điểm ISO-8601 UTC (AD-19)
        OffsetDateTime createdAt = OffsetDateTime.parse(entry.get("createdAt").asString());
        assertThat(createdAt.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void variantIdAndDocumentTypeFilter() throws Exception {
        Owner owner = newStore();
        UUID milk = createWithOpening(owner, "Sữa tươi", 24, 11000);
        UUID water = createWithOpening(owner, "Nước suối", 10, 3000);

        assertThat(variantIds(ledger(owner))).containsExactlyInAnyOrder(milk.toString(), water.toString());
        assertThat(variantIds(ledger(owner, "variantId", milk.toString()))).containsExactly(milk.toString());
        assertThat(variantIds(ledger(owner, "variantId", water.toString()))).containsExactly(water.toString());
        assertThat(variantIds(ledger(owner, "documentType", "OPENING")))
                .containsExactlyInAnyOrder(milk.toString(), water.toString());
        assertThat(variantIds(ledger(owner, "variantId", milk.toString(), "documentType", "OPENING")))
                .containsExactly(milk.toString());
        for (String other : List.of("SALE", "SALE_CANCEL", "SALE_RETURN", "PURCHASE_RECEIPT", "STOCKTAKE")) {
            assertThat(ledger(owner, "documentType", other).get("total").asLong()).as(other).isZero();
        }
        // Biến thể của Cửa hàng khác: không ra dòng nào
        Owner other = newStore();
        assertThat(ledger(other, "variantId", milk.toString()).get("total").asLong()).isZero();
    }

    @Test
    void dateRangeIsWholeVietnamDaysIncludingBothEnds() throws Exception {
        Owner owner = newStore();
        UUID milk = createWithOpening(owner, "Sữa tươi", 24, 11000);
        UUID water = createWithOpening(owner, "Nước suối", 10, 3000);
        // 00:00 ngày 01/11 giờ Việt Nam (17:00 UTC ngày 31/10) và 23:59:59.999 ngày 15/11 giờ Việt Nam
        moveEntry(owner, milk, OffsetDateTime.parse("2026-11-01T00:00:00+07:00"));
        moveEntry(owner, water, OffsetDateTime.parse("2026-11-15T23:59:59.999+07:00"));

        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-01", "toDate", "2026-11-15")))
                .containsExactly(water.toString(), milk.toString());
        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-01", "toDate", "2026-11-01")))
                .containsExactly(milk.toString());
        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-15", "toDate", "2026-11-15")))
                .containsExactly(water.toString());
        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-02", "toDate", "2026-11-14"))).isEmpty();
        // Ngày theo lịch Việt Nam, không theo UTC: 31/10 (UTC của dòng Sữa tươi) không có gì
        assertThat(variantIds(ledger(owner, "fromDate", "2026-10-31", "toDate", "2026-10-31"))).isEmpty();
        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-16", "toDate", "2026-11-16"))).isEmpty();
        // Một đầu cũng lọc được
        assertThat(variantIds(ledger(owner, "fromDate", "2026-11-02"))).containsExactly(water.toString());
        assertThat(variantIds(ledger(owner, "toDate", "2026-11-14"))).containsExactly(milk.toString());
    }

    @Test
    void aRangeLongerThan366DaysIsRejectedOnToDate() throws Exception {
        Owner owner = newStore();
        createWithOpening(owner, "Sữa tươi", 24, 11000);

        // toDate − fromDate = 365 ngày (366 ngày tính cả hai đầu): được
        request(owner, "fromDate", "2025-11-15", "toDate", "2026-11-15").andExpect(status().isOk());
        // toDate − fromDate = 366 ngày (367 ngày tính cả hai đầu): 400 trên toDate
        request(owner, "fromDate", "2025-11-15", "toDate", "2026-11-16")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
        // Khoảng ngược cũng 400 trên toDate
        request(owner, "fromDate", "2026-11-15", "toDate", "2026-11-14")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    ResultActions request(Owner owner, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(LEDGER).cookie(owner.cookie());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    JsonNode ledger(Owner owner, String... params) throws Exception {
        String body = request(owner, params)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    static List<String> variantIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("variantId").asString()));
        return ids;
    }

    /** Thêm Sản phẩm có Tồn kho đầu kỳ qua API catalog; trả id Biến thể mặc định. */
    UUID createWithOpening(Owner owner, String name, int openingQuantity, long openingUnitCost) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", 15000);
        body.put("openingQuantity", openingQuantity);
        body.put("openingUnitCost", openingUnitCost);
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(response).get("variants").get(0).get("id").asString());
    }

    /** Dời thời điểm của dòng sổ kho của Biến thể (chỉ trong test), để kiểm ranh giới ngày. */
    void moveEntry(Owner owner, UUID variantId, OffsetDateTime at) {
        int updated = TenantScope.runAs(owner.storeId(), () -> new TransactionTemplate(txManager).execute(s ->
                jdbc.update("update app.stock_ledger set created_at = ? where variant_id = ?", at, variantId)));
        assertThat(updated).isEqualTo(1);
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử sổ kho");
        body.put("loginKey", "sokho." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", OWNER_NAME);
        var response = mvc.perform(post("/api/v1/platform/stores")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()));
    }
}
