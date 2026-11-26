package vn.nhom17.banhang.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LowStockPageIntegrationTest {

    private static final String LOW_STOCK = "/api/v1/inventory/low-stock";
    private static final String PAGE = LOW_STOCK + "/page";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JsonMapper json;

    @Test
    void pagesHaveAccurateTotalStableOrderAndClampAfterTheLastPage() throws Exception {
        Session owner = newStore();
        List<String> expected = new ArrayList<>();
        inStore(owner, () -> {
            for (int index = 0; index < 5; index++) {
                expected.add(variant(owner, "Hàng " + index, index, 10).variantId().toString());
            }
        });
        expected.sort(String::compareTo);

        JsonNode first = page(owner, "size", "2");
        assertThat(first.get("total").asLong()).isEqualTo(5);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(ids(first.get("items"))).containsExactlyElementsOf(expected.subList(0, 2));

        JsonNode second = page(owner, "page", "1", "size", "2");
        assertThat(second.get("total").asLong()).isEqualTo(5);
        assertThat(ids(second.get("items"))).containsExactlyElementsOf(expected.subList(2, 4));
        assertThat(page(owner, "page", "1", "size", "2")).isEqualTo(second);

        JsonNode last = page(owner, "page", "2147483647", "size", "2");
        assertThat(last.get("total").asLong()).isEqualTo(5);
        assertThat(last.get("page").asInt()).isEqualTo(2);
        assertThat(ids(last.get("items"))).containsExactly(expected.get(4));
        assertFields(first.get("items"));
    }

    @Test
    void totalAndRowsApplyThresholdActiveCatalogRecordsAndTenantBeforePagination() throws Exception {
        Session owner = newStore();
        Session other = newStore();
        List<String> expected = new ArrayList<>();
        inStore(owner, () -> {
            expected.add(variant(owner, "Sắp hết", 1, 5).variantId().toString());
            expected.add(variant(owner, "Đã hết", 0, 1).variantId().toString());
            variant(owner, "Bằng ngưỡng", 5, 5);
            variant(owner, "Tắt cảnh báo", 0, 0);
            Variant inactiveStock = variant(owner, "Tồn đã ngừng", 0, 5);
            jdbc.update("UPDATE app.stock SET is_active = false WHERE store_id = ? AND variant_id = ?",
                    owner.storeId(), inactiveStock.variantId());
            Variant inactiveVariant = variant(owner, "Biến thể đã ngừng", 0, 5);
            jdbc.update("UPDATE app.product_variant SET status = 'INACTIVE' WHERE store_id = ? AND id = ?",
                    owner.storeId(), inactiveVariant.variantId());
            Variant inactiveProduct = variant(owner, "Sản phẩm đã ngừng", 0, 5);
            jdbc.update("UPDATE app.product SET status = 'INACTIVE' WHERE store_id = ? AND id = ?",
                    owner.storeId(), inactiveProduct.productId());
            Variant deletedVariant = variant(owner, "Biến thể đã xóa", 0, 5);
            jdbc.update("UPDATE app.product_variant SET deleted_at = created_at WHERE store_id = ? AND id = ?",
                    owner.storeId(), deletedVariant.variantId());
            Variant deletedProduct = variant(owner, "Sản phẩm đã xóa", 0, 5);
            jdbc.update("UPDATE app.product SET deleted_at = created_at WHERE store_id = ? AND id = ?",
                    owner.storeId(), deletedProduct.productId());
        });
        inStore(other, () -> variant(other, "Hàng cửa hàng khác", 0, 99));
        expected.sort(String::compareTo);

        JsonNode first = page(owner, "size", "1");
        assertThat(first.get("total").asLong()).isEqualTo(2);
        assertThat(ids(first.get("items"))).containsExactly(expected.getFirst());
        JsonNode second = page(owner, "page", "1", "size", "1");
        assertThat(second.get("total").asLong()).isEqualTo(2);
        assertThat(ids(second.get("items"))).containsExactly(expected.getLast());
        assertThat(page(other).get("total").asLong()).isEqualTo(1);

        // API danh sách cũ giữ nguyên kiểu mảng và cùng quy tắc chọn hàng.
        JsonNode legacy = json.readTree(mvc.perform(get(LOW_STOCK).cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(legacy.isArray()).isTrue();
        assertThat(ids(legacy)).containsExactlyInAnyOrderElementsOf(expected);
        assertFields(legacy);
    }

    @Test
    void usesSharedPageDefaultsLimitsAndValidatesNumbers() throws Exception {
        Session owner = newStore();
        JsonNode empty = page(owner, "page", "9", "size", "0");
        assertThat(empty.get("total").asLong()).isZero();
        assertThat(empty.get("page").asInt()).isZero();
        assertThat(empty.get("size").asInt()).isEqualTo(20);
        assertThat(ids(empty.get("items"))).isEmpty();
        inStore(owner, () -> variant(owner, "Sắp hết", 0, 1));
        JsonNode defaults = page(owner, "page", "-1");
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(page(owner, "size", "-1").get("size").asInt()).isEqualTo(20);
        assertThat(page(owner, "size", "999").get("size").asInt()).isEqualTo(100);
        request(owner, "page", "invalid").andExpect(status().isBadRequest());
        request(owner, "size", "invalid").andExpect(status().isBadRequest());
    }

    @Test
    void staffCanReadAlertsWithoutCostAndAnonymousIsRejected() throws Exception {
        Cookie staff = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", "dev.nv.a", "password", "demo1234"))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        mvc.perform(get(PAGE)).andExpect(status().isUnauthorized());
        JsonNode response = json.readTree(mvc.perform(get(PAGE).cookie(staff)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(response.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("items", "total", "page", "size");
        assertFields(response.get("items"));
    }

    private void assertFields(JsonNode items) {
        items.forEach(item -> assertThat(item.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("variantId", "productName", "variantName", "quantity", "minQuantity"));
    }

    private List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("variantId").asString()));
        return ids;
    }

    private ResultActions request(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(PAGE).cookie(session.cookie());
        for (int index = 0; index < params.length; index += 2) request.param(params[index], params[index + 1]);
        return mvc.perform(request);
    }

    private JsonNode page(Session session, String... params) throws Exception {
        return json.readTree(request(session, params).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void inStore(Session session, Runnable action) {
        TenantScope.runAs(session.storeId(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> action.run()));
    }

    private Variant variant(Session session, String name, int quantity, int minQuantity) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO app.product (id, store_id, name) VALUES (?, ?, ?)",
                productId, session.storeId(), name);
        jdbc.update("""
                INSERT INTO app.product_variant (id, store_id, product_id, sku, sale_price)
                VALUES (?, ?, ?, ?, 10000)
                """, variantId, session.storeId(), productId, variantId.toString());
        jdbc.update("""
                INSERT INTO app.stock (store_id, variant_id, quantity, min_quantity, avg_unit_cost)
                VALUES (?, ?, ?, ?, 4500)
                """, session.storeId(), variantId, quantity, minQuantity);
        return new Variant(productId, variantId);
    }

    private Session newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeName", "Cửa hàng thử phân trang cảnh báo",
                                "displayName", "Chủ thử cảnh báo", "password", "matkhau123",
                                "loginKey", "low-stock." + UUID.randomUUID() + "@test.vn"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Session(response.getCookie("access_token"), UUID.fromString(body.get("storeId").asString()));
    }

    private record Session(Cookie cookie, UUID storeId) {}
    private record Variant(UUID productId, UUID variantId) {}
}
