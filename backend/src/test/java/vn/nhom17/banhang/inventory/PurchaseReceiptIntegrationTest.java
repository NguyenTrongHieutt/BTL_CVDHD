package vn.nhom17.banhang.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PurchaseReceiptIntegrationTest {
    private static final String PRODUCTS = "/api/v1/catalog/products";
    private static final String RECEIPTS = "/api/v1/inventory/purchase-receipts";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private Cookie owner;
    private UUID storeId;
    private String supplierId;

    @BeforeEach
    void prepareSupplier() throws Exception {
        owner = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginKey":"dev.chu.a","password":"demo1234"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        storeId = UUID.fromString(JsonPath.read(me, "$.storeId"));
        String supplier = mvc.perform(post("/api/v1/catalog/suppliers").cookie(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Nhà cung cấp test phiếu nhập"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        supplierId = JsonPath.read(supplier, "$.supplierId");
    }

    @Test
    void updatesStockAndRoundsHalfUpOnlyOnceWhenTheReceiptIsRetried() throws Exception {
        Product product = product();
        UUID key = UUID.randomUUID();
        String body = body(line(product, 101));
        String response = create(key, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(101))
                .andReturn().getResponse().getContentAsString();
        String receiptId = JsonPath.read(response, "$.receiptId");

        create(key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.receiptId").value(receiptId));
        create(key, body(line(product, 102))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));

        assertStock(product, 2, 101);
        TenantScope.runAs(storeId, () -> new TransactionTemplate(transactions).execute(tx -> {
            UUID id = UUID.fromString(receiptId);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM app.stock_ledger WHERE document_type = 'PURCHASE_RECEIPT' AND document_id = ?
                    """, Long.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT unit_cost FROM app.purchase_receipt_line WHERE receipt_id = ?
                    """, Long.class, id)).isEqualTo(101);
            return null;
        }));
    }

    @Test
    void rejectsInactiveProductsAndRollsBackEarlierLines() throws Exception {
        Product first = product();
        Product second = product();
        Product active = first.variantId().compareTo(second.variantId()) < 0 ? first : second;
        Product inactive = active == first ? second : first;
        mvc.perform(post(PRODUCTS + "/{id}/deactivate", inactive.id()).cookie(owner)
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk());

        create(UUID.randomUUID(), body(line(active, 120) + "," + line(inactive, 120)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/inventory/variant-inactive"));

        assertStock(active, 1, 100);
        long receipts = TenantScope.runAs(storeId, () -> new TransactionTemplate(transactions).execute(tx ->
                jdbc.queryForObject("SELECT count(*) FROM app.purchase_receipt WHERE supplier_id = ?",
                        Long.class, UUID.fromString(supplierId))));
        assertThat(receipts).isZero();
    }

    private ResultActions create(UUID key, String body) throws Exception {
        return mvc.perform(post(RECEIPTS).cookie(owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String lines) {
        return """
                {"supplierId":"%s","lines":[%s]}
                """.formatted(supplierId, lines);
    }

    private static String line(Product product, long cost) {
        return """
                {"variantId":"%s","quantity":1,"unitCost":%d}
                """.formatted(product.variantId(), cost);
    }

    private Product product() throws Exception {
        String name = "Test PN " + UUID.randomUUID();
        String content = mvc.perform(post(PRODUCTS).cookie(owner).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","salePrice":150,"openingQuantity":1,"openingUnitCost":100}
                                """.formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Product(JsonPath.read(content, "$.id"), UUID.fromString(JsonPath.read(content, "$.variants[0].id")), name);
    }

    private void assertStock(Product product, int quantity, long average) throws Exception {
        mvc.perform(get("/api/v1/inventory/stock").cookie(owner).param("q", product.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(quantity))
                .andExpect(jsonPath("$.items[0].avgUnitCost").value(average));
    }

    private record Product(String id, UUID variantId, String name) {}
}
