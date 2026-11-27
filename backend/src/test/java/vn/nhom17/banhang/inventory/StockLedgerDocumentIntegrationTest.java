package vn.nhom17.banhang.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StockLedgerDocumentIntegrationTest {
    private static final String DOCUMENTS = "/api/v1/inventory/ledger/documents/{type}/{id}";
    private static final String OWNER_NAME = "Chủ xem chứng từ kho";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @MockitoSpyBean VariantLookup variants;

    @Test
    void openingUsesItsOriginalQuantityAndCostAfterLaterReceipts() throws Exception {
        Owner owner = newStore();
        Product product = product(owner, "Sữa tươi", 5, 100);
        String supplier = supplier(owner);
        receipt(owner, supplier, null, null, List.of(receiptLine(product, 2, 300)));
        Snapshot before = snapshot(owner);

        String response = document(owner.cookie(), "OPENING", product.variantId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("OPENING"))
                .andExpect(jsonPath("$.documentId").value(product.variantId().toString()))
                .andExpect(jsonPath("$.actorName").value(OWNER_NAME))
                .andExpect(jsonPath("$.totalAmount").value(500))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Sữa tươi"))
                .andExpect(jsonPath("$.lines[0].sku").value(product.sku()))
                .andExpect(jsonPath("$.lines[0].quantity").value(5))
                .andExpect(jsonPath("$.lines[0].unitAmount").value(100))
                .andExpect(jsonPath("$.lines[0].amount").value(500))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(OffsetDateTime.parse(json.readTree(response).get("recordedAt").asString())).isNotNull();
        assertThat(snapshot(owner)).isEqualTo(before);
    }

    @Test
    void receiptReturnsAllSourceLinesAfterAFilteredLedgerAndProductDeactivation() throws Exception {
        Owner owner = newStore();
        Product milk = product(owner, "Sữa tươi", 5, 100);
        Product water = product(owner, "Nước suối", 5, 100);
        product(owner, "Hàng ngoài chứng từ", 5, 100);
        String receivedAt = "2026-11-20T09:30:00+07:00";
        JsonNode receipt = receipt(owner, supplier(owner), receivedAt, "Giao đủ hàng",
                List.of(receiptLine(milk, 2, 200), receiptLine(water, 3, 400)));
        UUID id = UUID.fromString(receipt.get("receiptId").asString());
        mvc.perform(post("/api/v1/catalog/products/{id}/deactivate", water.id()).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/inventory/ledger").cookie(owner.cookie())
                        .param("variantId", milk.variantId().toString()).param("documentType", "PURCHASE_RECEIPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].documentId").value(id.toString()));
        Snapshot before = snapshot(owner);
        clearInvocations(variants);
        String response = document(owner.cookie(), "PURCHASE_RECEIPT", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("PURCHASE_RECEIPT"))
                .andExpect(jsonPath("$.documentId").value(id.toString()))
                .andExpect(jsonPath("$.code").value(receipt.get("code").asString()))
                .andExpect(jsonPath("$.actorName").value(OWNER_NAME))
                .andExpect(jsonPath("$.supplierName").value("Nhà cung cấp chứng từ"))
                .andExpect(jsonPath("$.note").value("Giao đủ hàng"))
                .andExpect(jsonPath("$.totalAmount").value(1600))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Sữa tươi"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitAmount").value(200))
                .andExpect(jsonPath("$.lines[0].amount").value(400))
                .andExpect(jsonPath("$.lines[1].lineNo").value(2))
                .andExpect(jsonPath("$.lines[1].productName").value("Nước suối"))
                .andExpect(jsonPath("$.lines[1].sku").value(water.sku()))
                .andExpect(jsonPath("$.lines[1].quantity").value(3))
                .andExpect(jsonPath("$.lines[1].unitAmount").value(400))
                .andExpect(jsonPath("$.lines[1].amount").value(1200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(OffsetDateTime.parse(json.readTree(response).get("recordedAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse(receivedAt).toInstant());
        verify(variants).getForInventory(Set.of(milk.variantId(), water.variantId()));
        verify(variants, never()).findForInventory(null, null, true);
        assertThat(snapshot(owner)).isEqualTo(before);
    }

    @Test
    void stocktakeShowsRecordedCountsAndReasonsIncludingUnchangedAndZeroLines() throws Exception {
        Owner owner = newStore();
        Product shortage = product(owner, "Hàng hỏng", 5, 100);
        Product surplus = product(owner, "Hàng thừa", 5, 100);
        Product unchanged = product(owner, "Hàng đủ", 5, 100);
        JsonNode stocktake = stocktake(owner, List.of(
                Map.of("variantId", shortage.variantId(), "countedQuantity", 0, "reason", "Hỏng 5 cái"),
                Map.of("variantId", surplus.variantId(), "countedQuantity", 8, "reason", "Đếm thừa 3 cái"),
                Map.of("variantId", unchanged.variantId(), "countedQuantity", 5)));
        UUID id = UUID.fromString(stocktake.get("stocktakeId").asString());
        receipt(owner, supplier(owner), null, null, List.of(receiptLine(shortage, 2, 300)));
        Snapshot before = snapshot(owner);

        document(owner.cookie(), "STOCKTAKE", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("STOCKTAKE"))
                .andExpect(jsonPath("$.documentId").value(id.toString()))
                .andExpect(jsonPath("$.recordedAt").value(stocktake.get("createdAt").asString()))
                .andExpect(jsonPath("$.actorName").value(OWNER_NAME))
                .andExpect(jsonPath("$.note").value("Kiểm cuối ngày"))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Hàng hỏng"))
                .andExpect(jsonPath("$.lines[0].quantity").value(0))
                .andExpect(jsonPath("$.lines[0].systemQuantity").value(5))
                .andExpect(jsonPath("$.lines[0].delta").value(-5))
                .andExpect(jsonPath("$.lines[0].reason").value("Hỏng 5 cái"))
                .andExpect(jsonPath("$.lines[1].lineNo").value(2))
                .andExpect(jsonPath("$.lines[1].quantity").value(8))
                .andExpect(jsonPath("$.lines[1].systemQuantity").value(5))
                .andExpect(jsonPath("$.lines[1].delta").value(3))
                .andExpect(jsonPath("$.lines[1].reason").value("Đếm thừa 3 cái"))
                .andExpect(jsonPath("$.lines[2].lineNo").value(3))
                .andExpect(jsonPath("$.lines[2].quantity").value(5))
                .andExpect(jsonPath("$.lines[2].systemQuantity").value(5))
                .andExpect(jsonPath("$.lines[2].delta").value(0));
        assertThat(snapshot(owner)).isEqualTo(before);
    }

    @Test
    void missingForeignAndMismatchedSourceTypesReturnNotFound() throws Exception {
        Owner owner = newStore();
        Owner other = newStore();
        Product product = product(owner, "Chứng từ riêng", 5, 100);
        JsonNode receipt = receipt(owner, supplier(owner), null, null, List.of(receiptLine(product, 2, 200)));
        JsonNode stocktake = stocktake(owner, List.of(Map.of("variantId", product.variantId(), "countedQuantity", 7)));
        Map<String, UUID> sources = Map.of("OPENING", product.variantId(),
                "PURCHASE_RECEIPT", UUID.fromString(receipt.get("receiptId").asString()),
                "STOCKTAKE", UUID.fromString(stocktake.get("stocktakeId").asString()));
        for (var source : sources.entrySet()) {
            document(other.cookie(), source.getKey(), source.getValue())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
            document(owner.cookie(), source.getKey(), UUID.randomUUID())
                    .andExpect(status().isNotFound());
        }
        document(owner.cookie(), "OPENING", sources.get("PURCHASE_RECEIPT"))
                .andExpect(status().isNotFound());
        document(owner.cookie(), "PURCHASE_RECEIPT", sources.get("STOCKTAKE"))
                .andExpect(status().isNotFound());
        document(owner.cookie(), "SALE", sources.get("OPENING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffCannotReadAnyInventorySourceDocument() throws Exception {
        Cookie staff = mvc.perform(post("/api/v1/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginKey":"dev.nv.a","password":"demo1234"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        for (String type : List.of("OPENING", "PURCHASE_RECEIPT", "STOCKTAKE")) {
            document(staff, type, UUID.randomUUID()).andExpect(status().isForbidden());
        }
    }

    private ResultActions document(Cookie session, String type, UUID id) throws Exception {
        return mvc.perform(get(DOCUMENTS, type, id).cookie(session));
    }

    private JsonNode receipt(Owner owner, String supplier, String receivedAt, String note,
            List<Map<String, Object>> lines) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("supplierId", supplier);
        body.put("receivedAt", receivedAt);
        body.put("note", note);
        body.put("lines", lines);
        return postJson(owner, "/api/v1/inventory/purchase-receipts", body);
    }

    private static Map<String, Object> receiptLine(Product product, int quantity, long cost) {
        return Map.of("variantId", product.variantId(), "quantity", quantity, "unitCost", cost);
    }

    private JsonNode stocktake(Owner owner, List<Map<String, Object>> lines) throws Exception {
        return postJson(owner, "/api/v1/inventory/stocktakes", Map.of("note", "Kiểm cuối ngày", "lines", lines));
    }

    private String supplier(Owner owner) throws Exception {
        return postJson(owner, "/api/v1/catalog/suppliers", Map.of("name", "Nhà cung cấp chứng từ"))
                .get("supplierId").asString();
    }

    private Product product(Owner owner, String name, int quantity, long cost) throws Exception {
        String sku = "KHO-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        JsonNode created = postJson(owner, "/api/v1/catalog/products", Map.of("name", name, "sku", sku,
                "salePrice", 500, "openingQuantity", quantity, "openingUnitCost", cost));
        return new Product(created.get("id").asString(),
                UUID.fromString(created.get("variants").get(0).get("id").asString()), sku);
    }

    private JsonNode postJson(Owner owner, String path, Object body) throws Exception {
        String response = mvc.perform(post(path).cookie(owner.cookie()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    private Snapshot snapshot(Owner owner) {
        return TenantScope.runAs(owner.storeId(), () -> new TransactionTemplate(transactions).execute(tx ->
                new Snapshot(jdbc.queryForList("""
                        SELECT variant_id, quantity, avg_unit_cost, updated_at FROM app.stock
                        WHERE store_id = ? ORDER BY variant_id
                        """, owner.storeId()),
                        jdbc.queryForObject("SELECT count(*) FROM app.stock_ledger WHERE store_id = ?",
                                Long.class, owner.storeId()),
                        jdbc.queryForObject("SELECT count(*) FROM app.audit_log WHERE store_id = ?",
                                Long.class, owner.storeId()))));
    }

    private Owner newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores")
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeName", "Cửa hàng chứng từ kho",
                                "loginKey", "ledger-doc." + UUID.randomUUID() + "@test.vn",
                                "password", "matkhau123", "displayName", OWNER_NAME))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()));
    }

    private record Owner(Cookie cookie, UUID storeId) {}
    private record Product(String id, UUID variantId, String sku) {}
    private record Snapshot(List<Map<String, Object>> stock, long ledgerCount, long auditCount) {}
}
