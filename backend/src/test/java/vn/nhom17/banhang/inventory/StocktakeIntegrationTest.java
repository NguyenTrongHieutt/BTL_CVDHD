package vn.nhom17.banhang.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StocktakeIntegrationTest {
    private static final String STOCKTAKES = "/api/v1/inventory/stocktakes";
    private static final String PRODUCTS = "/api/v1/catalog/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private Cookie owner;
    private Cookie staff;
    private UUID storeId;

    @BeforeAll
    void sessions() throws Exception {
        owner = login("dev.chu.a");
        staff = login("dev.nv.a");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        storeId = UUID.fromString(JsonPath.read(me, "$.storeId"));
    }

    @Test
    void ownerCanSetActualCountsIncludingZeroWithoutChangingAverageCost() throws Exception {
        var stopped = createProduct(owner);
        UUID shortage = stopped.variantId();
        UUID surplus = createProduct(owner).variantId();
        UUID unchanged = createProduct(owner).variantId();
        mvc.perform(post(PRODUCTS + "/{id}/deactivate", stopped.id()).cookie(owner)
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/inventory/stock").cookie(owner)
                        .param("q", stopped.name()).param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].variantId").value(shortage.toString()))
                .andExpect(jsonPath("$.items[0].isActive").value(false));
        String body = """
                {"note":"Kiểm cuối ngày","lines":[
                  {"variantId":"%s","systemQuantity":5,"countedQuantity":0,"reason":"Hàng hỏng"},
                  {"variantId":"%s","countedQuantity":8,"reason":"Đếm thừa"},
                  {"variantId":"%s","countedQuantity":5}
                ]}
                """.formatted(shortage, surplus, unchanged);
        submit(staff, UUID.randomUUID(), body).andExpect(status().isForbidden());
        UUID key = UUID.randomUUID();
        String response = submit(owner, key, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].systemQuantity").value(5))
                .andExpect(jsonPath("$.lines[0].delta").value(-5))
                .andExpect(jsonPath("$.lines[0].reason").value("Hàng hỏng"))
                .andExpect(jsonPath("$.lines[1].delta").value(3))
                .andExpect(jsonPath("$.lines[2].delta").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.stocktakeId");
        submit(owner, key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.stocktakeId").value(id));

        TenantScope.runAs(storeId, () -> new TransactionTemplate(transactions).execute(tx -> {
            assertEquals(0, quantity(shortage));
            assertEquals(8, quantity(surplus));
            assertEquals(5, quantity(unchanged));
            for (UUID variant : List.of(shortage, surplus, unchanged)) {
                assertEquals(10000L, jdbc.queryForObject("SELECT avg_unit_cost FROM app.stock WHERE variant_id = ?",
                        Long.class, variant));
            }
            assertEquals(List.of(-5, 3, 0), jdbc.queryForList("""
                    SELECT delta FROM app.stock_ledger WHERE document_type = 'STOCKTAKE' AND document_id = ?
                    ORDER BY line_no
                    """, Integer.class, UUID.fromString(id)));
            assertEquals("2", jdbc.queryForObject("""
                    SELECT after->>'adjustedCount' FROM app.audit_log
                    WHERE action = 'inventory.stocktake.complete' AND entity_id = ?
                    """, String.class, id));
            return null;
        }));
    }

    @Test
    void rejectsStaleSnapshotAndMissingReasonWithoutUpdatingAnyLine() throws Exception {
        UUID first = createProduct(owner).variantId();
        UUID second = createProduct(owner).variantId();
        String stale = """
                {"lines":[{"variantId":"%s","systemQuantity":5,"countedQuantity":2,"reason":"Thiếu"},
                          {"variantId":"%s","systemQuantity":4,"countedQuantity":3,"reason":"Thiếu"}]}
                """.formatted(first, second);
        submit(owner, UUID.randomUUID(), stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/inventory/stocktake-stale"))
                .andExpect(jsonPath("$.currentQuantities['" + second + "']").value(5));
        String withoutReason = """
                {"lines":[{"variantId":"%s","countedQuantity":2}]}
                """.formatted(first);
        submit(owner, UUID.randomUUID(), withoutReason).andExpect(status().isBadRequest());
        UUID otherStore = createProduct(login("dev.chu.b")).variantId();
        String foreignVariant = """
                {"lines":[{"variantId":"%s","countedQuantity":0,"reason":"Kiểm lại"}]}
                """.formatted(otherStore);
        submit(owner, UUID.randomUUID(), foreignVariant).andExpect(status().isNotFound());
        TenantScope.runAs(storeId, () -> new TransactionTemplate(transactions).execute(tx -> {
            assertEquals(5, quantity(first));
            assertEquals(5, quantity(second));
            return null;
        }));
    }

    private int quantity(UUID variant) {
        return jdbc.queryForObject("SELECT quantity FROM app.stock WHERE variant_id = ?", Integer.class, variant);
    }

    private ResultActions submit(Cookie session, UUID key, String body) throws Exception {
        return mvc.perform(post(STOCKTAKES).cookie(session).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Product createProduct(Cookie session) throws Exception {
        String name = "Hàng kiểm kho " + UUID.randomUUID();
        String response = mvc.perform(post(PRODUCTS).cookie(session).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"%s","salePrice":15000,"openingQuantity":5,"openingUnitCost":10000}
                                """.formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Product(JsonPath.read(response, "$.id"), UUID.fromString(JsonPath.read(response, "$.variants[0].id")), name);
    }

    private record Product(String id, UUID variantId, String name) {}

    private Cookie login(String key) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"loginKey":"%s","password":"demo1234"}
                """.formatted(key))).andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
    }
}
