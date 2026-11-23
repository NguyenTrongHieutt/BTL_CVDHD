package vn.nhom17.banhang.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import com.jayway.jsonpath.JsonPath;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderHistoryIntegrationTest {
    private static final String SALES = "/api/v1/sales";
    private static final String ORDERS = SALES + "/orders";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final String phone = "09" + ThreadLocalRandom.current().nextInt(10_000_000, 90_000_000);
    private final UUID customerA = UUID.randomUUID();
    private final UUID customerB = UUID.randomUUID();
    private Session staffA;
    private Session ownerB;

    @BeforeAll
    void seedOrders() throws Exception {
        staffA = login("dev.nv.a");
        ownerB = login("dev.chu.b");
        TenantScope.runAs(staffA.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            insertCustomer(staffA, customerA);
            insertOrder(staffA, customerA, "DH-832400", "COMPLETED", 100, "2026-11-19T16:59:59Z");
            insertOrder(staffA, customerA, "DH-832401", "COMPLETED", 200, "2026-11-19T17:00:00Z");
            insertOrder(staffA, customerA, "DH-832402", "CANCELLED", 900, "2026-11-19T20:00:00Z");
            insertOrder(staffA, customerA, "DH-832403", "COMPLETED", 300, "2026-11-20T17:00:00Z");
            return null;
        }));
        TenantScope.runAs(ownerB.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            insertCustomer(ownerB, customerB);
            insertOrder(ownerB, customerB, "DH-832401", "COMPLETED", 999, "2026-11-19T17:00:00Z");
            return null;
        }));
    }

    @Test
    void findsOrdersByVietnameseDayAndNormalizedPhoneWithoutCrossingStores() throws Exception {
        String internationalPhone = "+84 " + phone.substring(1);
        mvc.perform(get(ORDERS).cookie(staffA.cookie())
                        .param("orderCode", "dh-8324").param("customerPhone", internationalPhone)
                        .param("fromDate", "2026-11-20").param("toDate", "2026-11-20").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderCode").value("DH-832402"));

        mvc.perform(get(ORDERS).cookie(staffA.cookie())
                        .param("orderCode", "dh-8324").param("customerPhone", internationalPhone)
                        .param("fromDate", "2026-11-20").param("toDate", "2026-11-20")
                        .param("sellerId", staffA.userId().toString()).param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderCode").value("DH-832401"))
                .andExpect(jsonPath("$.items[0].total").value(200))
                .andExpect(jsonPath("$.items[0].sellerName").value(staffA.displayName()));

        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("customerId", customerA.toString())
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].orderCode").value("DH-832402"));

        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("customerId", customerB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get(ORDERS).cookie(staffA.cookie())
                        .param("fromDate", "2026-11-21").param("toDate", "2026-11-20"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsOnlySellersAndCompletedPurchaseTotalsForTheCurrentStore() throws Exception {
        mvc.perform(get(ORDERS + "/sellers").cookie(staffA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sellerId == '%s')]", staffA.userId()).isNotEmpty())
                .andExpect(jsonPath("$[?(@.sellerId == '%s')]", ownerB.userId()).isEmpty());

        String summary = SALES + "/customers/{id}/purchase-summary";
        mvc.perform(get(summary, customerA).cookie(staffA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(3))
                .andExpect(jsonPath("$.totalSpent").value(600))
                .andExpect(jsonPath("$.lastPurchaseAt").value("2026-11-20T17:00:00Z"));

        mvc.perform(get(summary, customerB).cookie(staffA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.totalSpent").value(0))
                .andExpect(jsonPath("$.lastPurchaseAt").doesNotExist());
    }

    @Test
    void aCustomerCreatedByStaffIsLinkedAtCheckoutAndAppearsInOrderSearch() throws Exception {
        String newPhone = "08" + ThreadLocalRandom.current().nextInt(10_000_000, 90_000_000);
        String customer = mvc.perform(post("/api/v1/catalog/customers").cookie(staffA.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"phone":"%s"}
                                """.formatted(newPhone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String selectedId = JsonPath.read(customer, "$.customerId");

        Session ownerA = login("dev.chu.a");
        String product = mvc.perform(post("/api/v1/catalog/products").cookie(ownerA.cookie())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Hàng test DH-06","salePrice":15000,"openingQuantity":2,"openingUnitCost":10000}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String variantId = JsonPath.read(product, "$.variants[0].id");

        String quote = """
                {"customerId":"%s","lines":[{"variantId":"%s","quantity":1}],
                 "paymentMethod":"CASH","cashReceived":15000}
                """;
        mvc.perform(post(ORDERS + "/quote").cookie(staffA.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(quote.formatted(selectedId, variantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(15000));

        String checkout = """
                {"customerId":"%s","lines":[{"variantId":"%s","quantity":1}],
                 "paymentMethod":"CASH","cashReceived":15000,"quotedTotal":15000}
                """;
        UUID key = UUID.randomUUID();
        mvc.perform(post(ORDERS).cookie(staffA.cookie()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(checkout.formatted(customerB, variantId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));

        String order = mvc.perform(post(ORDERS).cookie(staffA.cookie()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(checkout.formatted(selectedId, variantId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(order, "$.orderId");
        String orderCode = JsonPath.read(order, "$.orderCode");

        String receipt = ORDERS + "/{id}/receipt";
        mvc.perform(get(receipt, orderId).cookie(staffA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.name").isNotEmpty())
                .andExpect(jsonPath("$.store.bankAccountNo").doesNotExist())
                .andExpect(jsonPath("$.order.orderCode").value(orderCode))
                .andExpect(jsonPath("$.order.total").value(15000))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value("Hàng test DH-06"))
                .andExpect(jsonPath("$.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(15000))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist());
        mvc.perform(get(receipt, orderId).cookie(ownerA.cookie())).andExpect(status().isOk());
        mvc.perform(get(receipt, orderId).cookie(ownerB.cookie())).andExpect(status().isNotFound());

        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("customerPhone", newPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderId").value(orderId));
        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("customerId", selectedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(orderId));
    }

    @Test
    void cancellationRestoresStockOnceAndRecordsWhoCancelled() throws Exception {
        Session ownerA = login("dev.chu.a");
        var sale = checkoutForCancellation(ownerA);
        String endpoint = ORDERS + "/" + sale.orderId() + "/cancel";
        UUID key = UUID.randomUUID();
        String reason = "Khách đổi ý";
        String request = """
                {"reason":"Khách đổi ý"}
                """;

        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("orderCode", sale.orderCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].canCancel").value(true));
        cancel(endpoint, ownerB, UUID.randomUUID(), request).andExpect(status().isNotFound());
        cancel(endpoint, staffA, UUID.randomUUID(), "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        cancel(endpoint, staffA, key, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value(reason));
        cancel(endpoint, staffA, key, request)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        cancel(endpoint, staffA, UUID.randomUUID(), request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/sales/invalid-state"));

        mvc.perform(get(ORDERS).cookie(staffA.cookie()).param("orderCode", sale.orderCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].canCancel").value(false));
        TenantScope.runAs(staffA.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            int quantity = jdbc.queryForObject("SELECT quantity FROM app.stock WHERE variant_id = ?",
                    Integer.class, sale.variantId());
            int restored = jdbc.queryForObject("""
                    SELECT coalesce(sum(delta), 0) FROM app.stock_ledger
                    WHERE document_type = 'SALE_CANCEL' AND document_id = ?
                    """, Integer.class, sale.orderId());
            UUID actor = jdbc.queryForObject("SELECT cancelled_by FROM app.sales_order WHERE id = ?",
                    UUID.class, sale.orderId());
            assertEquals(5, quantity);
            assertEquals(1, restored);
            assertEquals(staffA.userId(), actor);
            int entries = jdbc.queryForObject("""
                    SELECT count(*) FROM app.audit_log
                    WHERE action = 'sales.order.cancel' AND entity_id = ?
                    """, Integer.class, sale.orderId().toString());
            assertEquals(1, entries);
            return null;
        }));
    }

    @Test
    void staffCannotCancelAfterTheVietnameseDayAndOwnerCannotCancelAnOrderWithAReturn() throws Exception {
        Session ownerA = login("dev.chu.a");
        var oldSale = checkoutForCancellation(ownerA);
        var returnedSale = checkoutForCancellation(ownerA);
        TenantScope.runAs(staffA.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            jdbc.update("UPDATE app.sales_order SET created_at = ? WHERE id = ?",
                    OffsetDateTime.parse("2020-01-01T00:00:00Z"), oldSale.orderId());
            jdbc.update("UPDATE app.sales_order SET created_by = ? WHERE id = ?",
                    ownerA.userId(), returnedSale.orderId());
            jdbc.update("""
                    INSERT INTO app.sales_return (store_id, order_id, refund_amount, refund_method, created_by)
                    VALUES (?, ?, 1000, 'CASH', ?)
                    """, staffA.storeId(), returnedSale.orderId(), staffA.userId());
            return null;
        }));

        String reason = "{\"reason\":\"Khách đổi ý\"}";
        String oldEndpoint = ORDERS + "/" + oldSale.orderId() + "/cancel";
        cancel(oldEndpoint, staffA, UUID.randomUUID(), reason).andExpect(status().isForbidden());
        cancel(oldEndpoint, ownerA, UUID.randomUUID(), reason).andExpect(status().isOk());

        String returnedEndpoint = ORDERS + "/" + returnedSale.orderId() + "/cancel";
        cancel(returnedEndpoint, staffA, UUID.randomUUID(), reason).andExpect(status().isForbidden());
        cancel(returnedEndpoint, ownerA, UUID.randomUUID(), reason)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/sales/invalid-state"));
        mvc.perform(get(ORDERS).cookie(ownerA.cookie()).param("orderCode", returnedSale.orderCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].canCancel").value(false));
    }

    private ResultActions cancel(String endpoint, Session session, UUID key, String request) throws Exception {
        return mvc.perform(post(endpoint).cookie(session.cookie()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }

    private Sale checkoutForCancellation(Session owner) throws Exception {
        String product = mvc.perform(post("/api/v1/catalog/products").cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Hàng test DH-07 %s","salePrice":15000,"openingQuantity":5,"openingUnitCost":10000}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String variantId = JsonPath.read(product, "$.variants[0].id");
        String order = mvc.perform(post(ORDERS).cookie(staffA.cookie()).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lines":[{"variantId":"%s","quantity":1}],
                                 "paymentMethod":"CASH","cashReceived":15000,"quotedTotal":15000}
                                """.formatted(variantId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Sale(UUID.fromString(JsonPath.read(order, "$.orderId")), JsonPath.read(order, "$.orderCode"),
                UUID.fromString(variantId));
    }

    private record Sale(UUID orderId, String orderCode, UUID variantId) {}

    private Session login(String key) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginKey":"%s","password":"demo1234"}
                                """.formatted(key)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Session(cookie, UUID.fromString(JsonPath.read(me, "$.storeId")),
                UUID.fromString(JsonPath.read(me, "$.userId")), JsonPath.read(me, "$.displayName"));
    }

    private void insertCustomer(Session session, UUID customer) {
        jdbc.update("INSERT INTO app.customer (id, store_id, phone) VALUES (?, ?, ?)",
                customer, session.storeId(), phone);
    }

    private void insertOrder(Session session, UUID customer, String code, String status, long total, String at) {
        jdbc.update("""
                INSERT INTO app.sales_order
                    (store_id, order_code, customer_id, status, subtotal, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, session.storeId(), code, customer, status, total, total, session.userId(), OffsetDateTime.parse(at));
    }

    private record Session(Cookie cookie, UUID storeId, UUID userId, String displayName) {}
}
