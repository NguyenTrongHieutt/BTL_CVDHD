package vn.nhom17.banhang.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SellableProductIdsIntegrationTest {
    private static final String SELLABLE = "/api/v1/sales/sellable";
    private static final String PRODUCTS = "/api/v1/catalog/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;

    private Session ownerA;
    private Session staffA;
    private Session ownerB;

    @BeforeAll
    void login() throws Exception {
        ownerA = login("dev.chu.a");
        staffA = login("dev.nv.a");
        ownerB = login("dev.chu.b");
    }

    @Test
    void hydratesProductRankBeforePaginatingVariantsAndEnrichesTheirCurrentStock() throws Exception {
        Created first = create(ownerA, "Zulu " + UUID.randomUUID(), """
                [{"name":"Đỏ","salePrice":15000,"openingQuantity":5,"openingUnitCost":10000},
                 {"name":"Xanh","salePrice":17000}]
                """);
        Created second = create(ownerA, "Alpha " + UUID.randomUUID(), """
                [{"name":"Nhỏ","salePrice":20000},{"name":"Lớn","salePrice":22000}]
                """);
        mvc.perform(put("/api/v1/inventory/stock/{id}/min-quantity", first.variants().getFirst())
                        .cookie(ownerA.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minQuantity\":6}"))
                .andExpect(status().isNoContent());

        // Repeated product IDs retain the first occurrence; variant IDs are not product IDs.
        String[] ids = {first.id().toString(), second.id().toString(), first.id().toString(),
                first.variants().getFirst().toString(), UUID.randomUUID().toString()};
        mvc.perform(get(SELLABLE).cookie(staffA.cookie()).param("ids", ids).param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].productId").value(first.id().toString()))
                .andExpect(jsonPath("$.items[0].variantId").value(first.variants().getFirst().toString()))
                .andExpect(jsonPath("$.items[0].variantName").value("Đỏ"))
                .andExpect(jsonPath("$.items[0].salePrice").value(15000))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[0].belowMin").value(true))
                .andExpect(jsonPath("$.items[0].unit").value("chiếc"))
                .andExpect(jsonPath("$.items[0].sku").isNotEmpty())
                .andExpect(jsonPath("$.items[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.items[0].avgUnitCost").doesNotExist())
                .andExpect(jsonPath("$.items[1].variantId").value(first.variants().get(1).toString()))
                .andExpect(jsonPath("$.items[1].quantity").value(0))
                .andExpect(jsonPath("$.items[1].belowMin").value(false))
                .andExpect(jsonPath("$.items[2].productId").value(second.id().toString()));

        mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", ids).param("size", "3").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(second.variants().get(1).toString()));

        mvc.perform(get(SELLABLE).cookie(ownerA.cookie())
                        .param("ids", second.id() + "," + first.id()).param("page", "-1").param("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items[0].productId").value(second.id().toString()));

        mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", ids)
                        .param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void excludesForeignStoppedAndSoftDeletedProductsAndVariants() throws Exception {
        Created active = create(ownerA, "Active " + UUID.randomUUID(), """
                [{"name":"Đang bán","salePrice":1000},
                 {"name":"Đã ngừng","salePrice":2000},
                 {"name":"Đã xóa","salePrice":3000}]
                """);
        Created stopped = create(ownerA, "Stopped " + UUID.randomUUID(), null);
        Created deleted = create(ownerA, "Deleted " + UUID.randomUUID(), null);
        Created foreign = create(ownerB, "Foreign " + UUID.randomUUID(), null);
        mvc.perform(post(PRODUCTS + "/{id}/variants/{variant}/deactivate", active.id(), active.variants().get(1))
                        .cookie(ownerA.cookie()))
                .andExpect(status().isOk());
        mvc.perform(post(PRODUCTS + "/{id}/deactivate", stopped.id()).cookie(ownerA.cookie()))
                .andExpect(status().isOk());
        // Soft deletion is a persisted catalog state without a public delete endpoint.
        TenantScope.runAs(ownerA.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            jdbc.update("UPDATE app.product_variant SET deleted_at = ? WHERE id = ?", now, active.variants().get(2));
            jdbc.update("UPDATE app.product SET deleted_at = ? WHERE id = ?", now, deleted.id());
            return null;
        }));

        mvc.perform(get(SELLABLE).cookie(staffA.cookie()).param("ids", foreign.id().toString(), stopped.id().toString(),
                        deleted.id().toString(), active.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(active.variants().getFirst().toString()));
        mvc.perform(get(SELLABLE).cookie(ownerB.cookie()).param("ids", active.id().toString(), foreign.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(foreign.id().toString()));
    }

    @Test
    void alternativesReturnAtMostOneAvailableVariantPerProductInRankOrder() throws Exception {
        Created empty = create(ownerA, "Hết hàng " + UUID.randomUUID(), null);
        Created first = create(ownerA, "Còn hàng 1 " + UUID.randomUUID(), """
                [{"name":"Hết","salePrice":1000},
                 {"name":"Còn","salePrice":1000,"openingQuantity":5,"openingUnitCost":500},
                 {"name":"Cũng còn","salePrice":1000,"openingQuantity":8,"openingUnitCost":500}]
                """);
        Created second = create(ownerA, "Còn hàng 2 " + UUID.randomUUID(), """
                [{"name":"Đỏ","salePrice":1000,"openingQuantity":2,"openingUnitCost":500},
                 {"name":"Xanh","salePrice":1000}]
                """);
        mvc.perform(get(SELLABLE).cookie(staffA.cookie()).param("inStock", "true").param("size", "3")
                        .param("ids", empty.id().toString(), first.id().toString(), second.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].variantId").value(first.variants().get(1).toString()))
                .andExpect(jsonPath("$.items[1].variantId").value(second.variants().getFirst().toString()));
    }

    @Test
    void explicitEmptyIdsReturnNoRowsWhileOmittingIdsKeepsNormalSearch() throws Exception {
        String name = "Hydration " + UUID.randomUUID();
        Created product = create(ownerA, name, null);
        mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", "").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("q", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(product.id().toString()));
        mvc.perform(get(SELLABLE).param("ids", product.id().toString())).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedIdsAndMixingProductHydrationWithSearchFilters() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String invalid : List.of("not-a-uuid", "1-1-1-1-1", id + ",," + id)) {
            mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("ids"));
        }
        for (String filter : List.of("q", "categoryId", "minPrice", "maxPrice")) {
            String value = switch (filter) {
                case "q" -> "kem dưỡng";
                case "categoryId" -> UUID.randomUUID().toString();
                default -> "1000";
            };
            mvc.perform(get(SELLABLE).cookie(ownerA.cookie()).param("ids", id).param(filter, value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("ids"));
        }
    }

    private Created create(Session owner, String name, String variants) throws Exception {
        String details = variants == null ? "\"salePrice\":1000" : "\"variants\":" + variants;
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"unit\":\"chiếc\",%s}".formatted(name, details)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(response, "$.variants[*].id");
        return new Created(UUID.fromString(JsonPath.read(response, "$.id")), ids.stream().map(UUID::fromString).toList());
    }

    private Session login(String loginKey) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"%s\",\"password\":\"demo1234\"}".formatted(loginKey)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Session(cookie, UUID.fromString(JsonPath.read(me, "$.storeId")));
    }

    private record Session(Cookie cookie, UUID storeId) {}
    private record Created(UUID id, List<UUID> variants) {}
}
