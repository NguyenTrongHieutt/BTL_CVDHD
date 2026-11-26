package vn.nhom17.banhang.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@Import({TestcontainersConfiguration.class, StockReportIntegrationTest.FixedTime.class})
class StockReportIntegrationTest {
    private static final String REPORT = "/api/v1/reporting/stock";
    private static final String AS_OF = "2026-11-26T08:30:00Z";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JsonMapper json;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock stockReportTestClock() {
            return Clock.fixed(Instant.parse(AS_OF), ZoneOffset.UTC);
        }
    }

    @Test
    void pagesAtTheDatabaseAndKeepsWholeResultTotalsWhenClampingPastTheLastPage() throws Exception {
        Session owner = newStore();
        inStore(owner, () -> {
            UUID product = product(owner, "Hàng phân trang", null);
            for (int index = 0; index < 125; index++) {
                variant(owner, product, "PAGE-%03d".formatted(index), null, null, 2, 0, 3_000_000_000L);
            }
            variant(owner, product(owner, "Không khớp", null), "OTHER", null, null, 50, 0, 100);
        });

        JsonNode first = report(owner, "q", "hang phan trang", "size", "20");
        assertThat(first.get("total").asLong()).isEqualTo(125);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(20);
        assertThat(first.get("asOf").asString()).isEqualTo(AS_OF);
        assertThat(first.get("summary").get("stockQuantity").asLong()).isEqualTo(250);
        assertThat(first.get("summary").get("stockValue").asLong()).isEqualTo(750_000_000_000L);
        assertThat(first.get("summary").get("variantCount").asLong()).isEqualTo(125);
        assertThat(skus(first)).hasSize(20).startsWith("PAGE-000").endsWith("PAGE-019");

        JsonNode last = report(owner, "q", "hang phan trang", "size", "20", "page", "6");
        assertThat(skus(last)).hasSize(5).startsWith("PAGE-120").endsWith("PAGE-124");
        assertThat(last.get("summary")).isEqualTo(first.get("summary"));
        JsonNode pastEnd = report(owner, "q", "hang phan trang", "size", "20", "page", "7");
        assertThat(skus(pastEnd)).isEqualTo(skus(last));
        assertThat(pastEnd.get("page").asInt()).isEqualTo(6);
        assertThat(pastEnd.get("total").asLong()).isEqualTo(125);
        assertThat(pastEnd.get("summary")).isEqualTo(first.get("summary"));
        assertThat(skus(report(owner, "q", "hang phan trang", "size", "1000"))).hasSize(100);
    }

    @Test
    void searchesProductVariantSkuAndBarcodeWithoutAccentsOrWildcardExpansion() throws Exception {
        Session owner = newStore();
        inStore(owner, () -> {
            variant(owner, product(owner, "Cà phê sữa", null), "COFFEE-01", null, "8936001001", 3, 2, 1000);
            variant(owner, product(owner, "Áo thun", null), "SHIRT-ABC", "Đỏ / L", null, 3, 2, 1000);
            variant(owner, product(owner, "Giảm 10%_\\", null), "LITERAL", null, null, 3, 2, 1000);
        });

        assertThat(skus(report(owner, "q", "  CA\t PHE  "))).containsExactly("COFFEE-01");
        assertThat(skus(report(owner, "q", "do / l"))).containsExactly("SHIRT-ABC");
        assertThat(skus(report(owner, "q", "irt-ab"))).containsExactly("SHIRT-ABC");
        assertThat(skus(report(owner, "q", "600100"))).containsExactly("COFFEE-01");
        for (String literal : List.of("%", "_", "\\", "10%_\\")) {
            assertThat(skus(report(owner, "q", literal))).as(literal).containsExactly("LITERAL");
        }
        assertThat(skus(report(owner, "q", "' OR 1=1 --"))).isEmpty();
    }

    @Test
    void composesExactCategorySearchAndExclusiveStockStatusesBeforePaging() throws Exception {
        Session owner = newStore();
        UUID[] categories = new UUID[2];
        inStore(owner, () -> {
            categories[0] = category(owner, "Đồ uống");
            categories[1] = category(owner, "Đồ uống khác");
            UUID product = product(owner, "Sữa tươi", categories[0]);
            variant(owner, product, "ZERO", null, null, 0, 5, 1000);
            variant(owner, product, "ZERO-MIN", null, null, 0, 0, 1000);
            variant(owner, product, "LOW-A", null, null, 1, 5, 1000);
            variant(owner, product, "LOW-B", null, null, 2, 5, 1000);
            variant(owner, product, "ENOUGH", null, null, 5, 5, 1000);
            variant(owner, product, "NO-MIN", null, null, 1, 0, 1000);
            variant(owner, product(owner, "Sữa tươi", categories[1]), "FOREIGN-CATEGORY", null, null, 2, 5, 1000);
            variant(owner, product(owner, "Trà", categories[0]), "FOREIGN-NAME", null, null, 2, 5, 1000);
        });
        String category = categories[0].toString();

        JsonNode low = report(owner, "q", "sua tuoi", "categoryId", category, "stockStatus", "BELOW_MIN", "size", "1");
        assertThat(skus(low)).containsExactly("LOW-A");
        assertThat(low.get("total").asLong()).isEqualTo(2);
        assertThat(low.get("summary").get("stockQuantity").asLong()).isEqualTo(3);
        assertThat(skus(report(owner, "q", "sua tuoi", "categoryId", category,
                "stockStatus", "BELOW_MIN", "size", "1", "page", "1"))).containsExactly("LOW-B");
        assertThat(skus(report(owner, "q", "sua tuoi", "categoryId", category,
                "stockStatus", "OUT_OF_STOCK"))).containsExactly("ZERO", "ZERO-MIN");
        assertThat(skus(report(owner, "q", "sua tuoi", "categoryId", category,
                "stockStatus", "IN_STOCK"))).containsExactly("ENOUGH", "NO-MIN");
    }

    @Test
    void includesNondeletedInactiveBalancesOnlyWhenRequestedAndTreatsMissingStockAsZero() throws Exception {
        Session owner = newStore();
        UUID[] ids = new UUID[7];
        inStore(owner, () -> {
            UUID product = product(owner, "Hàng trạng thái", null);
            ids[0] = variant(owner, product, "ACTIVE", null, null, 10, 5, 2000);
            ids[1] = variant(owner, product, "INACTIVE-VARIANT", null, null, 10, 5, 2000);
            ids[2] = variant(owner, product, "INACTIVE-STOCK", null, null, 10, 5, 2000);
            ids[3] = product(owner, "Sản phẩm ngừng", null);
            variant(owner, ids[3], "INACTIVE-PRODUCT", null, null, 10, 5, 2000);
            ids[4] = variant(owner, product, "DELETED-VARIANT", null, null, 10, 5, 2000);
            ids[5] = product(owner, "Sản phẩm xóa", null);
            variant(owner, ids[5], "DELETED-PRODUCT", null, null, 10, 5, 2000);
            ids[6] = variant(owner, product, "MISSING-STOCK", null, null, null, 0, 0);
            jdbc.update("UPDATE app.product_variant SET status = 'INACTIVE' WHERE id = ?", ids[1]);
            jdbc.update("UPDATE app.stock SET is_active = false WHERE variant_id = ?", ids[2]);
            jdbc.update("UPDATE app.product SET status = 'INACTIVE' WHERE id = ?", ids[3]);
            jdbc.update("UPDATE app.product_variant SET deleted_at = now() WHERE id = ?", ids[4]);
            jdbc.update("UPDATE app.product SET deleted_at = now() WHERE id = ?", ids[5]);
        });

        JsonNode active = report(owner);
        assertThat(skus(active)).containsExactly("ACTIVE", "MISSING-STOCK");
        JsonNode missing = active.get("items").get(1);
        assertThat(missing.get("minQuantity").isNull()).isTrue();
        assertThat(missing.get("stockQuantity").asInt()).isZero();
        assertThat(missing.get("avgUnitCost").asLong()).isZero();
        assertThat(missing.get("stockValue").asLong()).isZero();
        assertThat(missing.get("isActive").asBoolean()).isTrue();
        assertThat(skus(report(owner, "stockStatus", "OUT_OF_STOCK"))).containsExactly("MISSING-STOCK");

        JsonNode all = report(owner, "includeInactive", "true");
        assertThat(skus(all)).containsExactlyInAnyOrder("ACTIVE", "MISSING-STOCK", "INACTIVE-VARIANT",
                "INACTIVE-STOCK", "INACTIVE-PRODUCT");
        assertThat(all.get("summary").get("stockQuantity").asLong()).isEqualTo(40);
        all.get("items").forEach(item -> assertThat(item.get("isActive").asBoolean())
                .isEqualTo(!item.get("sku").asString().startsWith("INACTIVE-")));
    }

    @Test
    void exportsEveryFilteredRowBeyondThePageLimitWithBomAndWholeResultSummary() throws Exception {
        Session owner = newStore();
        UUID[] category = new UUID[1];
        inStore(owner, () -> {
            category[0] = category(owner, "Danh mục xuất");
            UUID product = product(owner, "Dữ liệu xuất", category[0]);
            for (int index = 0; index < 125; index++) {
                variant(owner, product, "EXPORT-%03d".formatted(index), null, null, 2, 5, 1000);
            }
            variant(owner, product, "ZERO", null, null, 0, 5, 1000);
            variant(owner, product(owner, "Dữ liệu xuất", null), "OTHER-CATEGORY", null, null, 2, 5, 1000);
        });

        byte[] bytes = export(owner, "q", "du lieu xuat", "categoryId", category[0].toString(),
                "stockStatus", "BELOW_MIN", "page", "4", "size", "1");
        assertThat(bytes).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff\"Sản phẩm\",\"Danh mục\",\"Phân loại\",\"SKU\",\"Số lượng tồn\",\"Giá vốn bình quân\",\"Giá trị tồn\"\r\n");
        assertThat(csv.lines().toList()).hasSize(127);
        assertThat(csv).contains("\"EXPORT-000\",2,1000,2000", "\"EXPORT-124\",2,1000,2000")
                .doesNotContain("OTHER-CATEGORY", "\"ZERO\"")
                .endsWith("\"Tổng\",,,,250,,250000\r\n");
    }

    @Test
    void csvQuotesTextAndNeutralizesFormulaPrefixesIncludingWhitespaceAndControlCharacters() throws Exception {
        Session owner = newStore();
        inStore(owner, () -> {
            UUID category = category(owner, "\t+SUM(1,2)");
            UUID product = product(owner, " =HYPERLINK(\"x\",\"y\")\r\nHàng", category);
            variant(owner, product, "@SKU", "-1+2", null, 2, 0, 1000);
            for (String prefix : List.of("=", "+", "-", "@", " \ufeff=", "\t", "\r", "\n")) {
                variant(owner, product(owner, prefix + "danger", null), "SAFE-" + UUID.randomUUID(), null, null, 1, 0, 1000);
            }
        });

        String csv = new String(export(owner), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"' =HYPERLINK(\"\"x\"\",\"\"y\"\")\r\nHàng\"",
                "\"'\t+SUM(1,2)\"", "\"'-1+2\"", "\"'@SKU\"");
        for (String prefix : List.of("=", "+", "-", "@", " \ufeff=", "\t", "\r", "\n")) {
            assertThat(csv).contains("\"'" + prefix + "danger\"");
        }
    }

    @Test
    void tenantRlsSeparatesPagesSummariesSearchCategoriesAndExports() throws Exception {
        Session owner = newStore();
        Session other = newStore();
        UUID[] categories = new UUID[2];
        inStore(owner, () -> {
            categories[0] = category(owner, "Cùng danh mục");
            variant(owner, product(owner, "Cùng tên", categories[0]), "OWN", null, null, 2, 1, 1000);
        });
        inStore(other, () -> {
            categories[1] = category(other, "Cùng danh mục");
            variant(other, product(other, "Cùng tên", categories[1]), "FOREIGN", null, null, 99, 1, 99000);
        });

        JsonNode own = report(owner, "q", "cung ten");
        assertThat(skus(own)).containsExactly("OWN");
        assertThat(own.get("summary").get("stockQuantity").asLong()).isEqualTo(2);
        JsonNode foreign = report(owner, "categoryId", categories[1].toString());
        assertThat(skus(foreign)).isEmpty();
        assertThat(foreign.get("summary").get("stockValue").asLong()).isZero();
        assertThat(new String(export(owner), StandardCharsets.UTF_8)).contains("\"OWN\"").doesNotContain("FOREIGN");
        assertThat(new String(export(owner, "categoryId", categories[1].toString()), StandardCharsets.UTF_8))
                .endsWith("\"Tổng\",,,,0,,0\r\n");
        assertThat(skus(report(other))).containsExactly("FOREIGN");
        assertThat(own.get("items").get(0).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("productId", "productName", "categoryName", "variantId", "variantName",
                        "sku", "stockQuantity", "minQuantity", "avgUnitCost", "stockValue", "isActive");
    }

    @Test
    void ownerOnlySecurityProtectsBothDataAndCsv() throws Exception {
        Cookie staff = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", "dev.nv.a", "password", "demo1234"))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        for (String path : List.of(REPORT, REPORT + "/export")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).cookie(staff)).andExpect(status().isForbidden());
        }
    }

    @Test
    void validatesFiltersAndUsesSharedPageDefaultsForEmptyResults() throws Exception {
        Session owner = newStore();
        request(owner, "stockStatus", "LOW").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stockStatus"));
        mvc.perform(get(REPORT + "/export").cookie(owner.cookie()).param("stockStatus", "LOW"))
                .andExpect(status().isBadRequest());
        request(owner, "categoryId", "invalid").andExpect(status().isBadRequest());
        request(owner, "includeInactive", "invalid").andExpect(status().isBadRequest());
        JsonNode empty = report(owner, "page", "-1", "size", "0", "q", "   ");
        assertThat(skus(empty)).isEmpty();
        assertThat(empty.get("page").asInt()).isZero();
        assertThat(empty.get("size").asInt()).isEqualTo(20);
        assertThat(empty.get("total").asLong()).isZero();
        assertThat(empty.get("summary").get("stockQuantity").asLong()).isZero();
        assertThat(empty.get("summary").get("stockValue").asLong()).isZero();
        assertThat(empty.get("summary").get("variantCount").asLong()).isZero();
    }

    private ResultActions request(Session session, String... params) throws Exception {
        return mvc.perform(withParams(get(REPORT).cookie(session.cookie()), params));
    }

    private JsonNode report(Session session, String... params) throws Exception {
        return json.readTree(request(session, params).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private byte[] export(Session session, String... params) throws Exception {
        return mvc.perform(withParams(get(REPORT + "/export").cookie(session.cookie()), params))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("bao-cao-ton-kho-2026-11-26.csv")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, String... params) {
        for (int index = 0; index < params.length; index += 2) request.param(params[index], params[index + 1]);
        return request;
    }

    private List<String> skus(JsonNode page) {
        List<String> skus = new ArrayList<>();
        page.get("items").forEach(item -> skus.add(item.get("sku").asString()));
        return skus;
    }

    private void inStore(Session session, Runnable action) {
        TenantScope.runAs(session.storeId(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> action.run()));
    }

    private UUID category(Session session, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.category (id, store_id, name) VALUES (?, ?, ?)", id, session.storeId(), name);
        return id;
    }

    private UUID product(Session session, String name, UUID category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.product (id, store_id, name, category_id) VALUES (?, ?, ?, ?)",
                id, session.storeId(), name, category);
        return id;
    }

    private UUID variant(Session session, UUID product, String sku, String name, String barcode,
                         Integer quantity, int minQuantity, long unitCost) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.product_variant (id, store_id, product_id, sku, name, barcode, sale_price)
                VALUES (?, ?, ?, ?, ?, ?, 10000)
                """, id, session.storeId(), product, sku, name, barcode);
        if (quantity != null) {
            jdbc.update("""
                    INSERT INTO app.stock (store_id, variant_id, quantity, min_quantity, avg_unit_cost)
                    VALUES (?, ?, ?, ?, ?)
                    """, session.storeId(), id, quantity, minQuantity, unitCost);
        }
        return id;
    }

    private Session newStore() throws Exception {
        var response = mvc.perform(post("/api/v1/platform/stores")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeName", "Cửa hàng thử báo cáo tồn",
                                "displayName", "Chủ thử báo cáo", "password", "matkhau123",
                                "loginKey", "stock-report." + UUID.randomUUID() + "@test.vn"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Session(response.getCookie("access_token"), UUID.fromString(body.get("storeId").asString()));
    }

    private record Session(Cookie cookie, UUID storeId) {}
}
