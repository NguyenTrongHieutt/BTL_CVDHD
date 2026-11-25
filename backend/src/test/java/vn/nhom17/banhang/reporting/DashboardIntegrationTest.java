package vn.nhom17.banhang.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DashboardIntegrationTest.FixedTime.class})
class DashboardIntegrationTest {
    private static final String DASHBOARD = "/api/v1/reporting/dashboard";
    private static final String TODAY_START = "2026-11-19T17:00:00Z";
    private static final String YESTERDAY = "2026-11-19T16:59:59Z";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock reportingTestClock() {
            return Clock.fixed(Instant.parse("2026-11-19T21:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void reportsNetSalesByVietnameseDateAndRanksProductsAcrossVariantsForOneStore() throws Exception {
        Session storeA = login("dev.chu.a");
        Session storeB = login("dev.chu.b");
        TenantScope.runAs(storeA.storeId(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            UUID rice = product(storeA, "Bánh gạo");
            UUID first = variant(storeA, rice, "BC-RICE-1");
            UUID second = variant(storeA, rice, "BC-RICE-2");
            UUID milk = variant(storeA, product(storeA, "Sữa tươi"), "BC-MILK");
            insertLine(storeA, insertOrder(storeA, "DH-997101", "COMPLETED", 40_000, TODAY_START), first, 4, 40_000);
            UUID returnedOrder = insertOrder(storeA, "DH-997102", "RETURNED", 20_000, YESTERDAY);
            UUID refundedLine = insertLine(storeA, returnedOrder, second, 2, 20_000);
            insertReturn(storeA, returnedOrder, refundedLine, second);
            insertLine(storeA, insertOrder(storeA, "DH-997103", "COMPLETED", 30_000, TODAY_START), milk, 3, 30_000);
            insertLine(storeA, insertOrder(storeA, "DH-997104", "COMPLETED", 10_000, YESTERDAY), second, 1, 10_000);
            insertLine(storeA, insertOrder(storeA, "DH-997105", "CANCELLED", 90_000, TODAY_START), milk, 9, 90_000);
            insertLine(storeA, insertOrder(storeA, "DH-997106", "COMPLETED", 90_000, "2026-11-13T16:59:59Z"), milk, 9, 90_000);
        }));
        TenantScope.runAs(storeB.storeId(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            UUID other = variant(storeB, product(storeB, "Hàng cửa hàng B"), "BC-OTHER");
            insertLine(storeB, insertOrder(storeB, "DH-997101", "COMPLETED", 90_000, TODAY_START), other, 9, 90_000);
        }));

        mvc.perform(get(DASHBOARD).cookie(storeA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-20"))
                .andExpect(jsonPath("$.today.revenue").value(50_000))
                .andExpect(jsonPath("$.today.orderCount").value(2))
                .andExpect(jsonPath("$.top5Last7Days.length()").value(2))
                .andExpect(jsonPath("$.top5Last7Days[0].name").value("Bánh gạo"))
                .andExpect(jsonPath("$.top5Last7Days[0].quantity").value(5))
                .andExpect(jsonPath("$.top5Last7Days[0].revenue").value(50_000))
                .andExpect(jsonPath("$.top5Last7Days[1].name").value("Sữa tươi"));

        mvc.perform(get(DASHBOARD).cookie(storeB.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.revenue").value(90_000))
                .andExpect(jsonPath("$.top5Last7Days.length()").value(1))
                .andExpect(jsonPath("$.top5Last7Days[0].name").value("Hàng cửa hàng B"));
    }

    @Test
    void rejectsStaffAndAnonymousRequests() throws Exception {
        mvc.perform(get(DASHBOARD)).andExpect(status().isUnauthorized());
        mvc.perform(get(DASHBOARD).cookie(login("dev.nv.a").cookie())).andExpect(status().isForbidden());
    }

    private UUID product(Session session, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.product (id, store_id, name) VALUES (?, ?, ?)", id, session.storeId(), name);
        return id;
    }

    private UUID variant(Session session, UUID product, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.product_variant (id, store_id, product_id, sku, sale_price) VALUES (?, ?, ?, ?, 10000)",
                id, session.storeId(), product, sku);
        return id;
    }

    private UUID insertOrder(Session session, String code, String status, long amount, String timestamp) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order (id, store_id, order_code, status, subtotal, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, session.storeId(), code, status, amount, amount, session.userId(), OffsetDateTime.parse(timestamp));
        return id;
    }

    private UUID insertLine(Session session, UUID order, UUID variant, int quantity, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order_line (id, store_id, order_id, line_no, variant_id, product_name,
                    quantity, unit_price, net_line_amount, unit_cost)
                VALUES (?, ?, ?, 1, ?, 'Hàng thử', ?, 10000, ?, 5000)
                """, id, session.storeId(), order, variant, quantity, amount);
        return id;
    }

    private void insertReturn(Session session, UUID order, UUID line, UUID variant) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_return (id, store_id, order_id, refund_amount, refund_method, created_by, created_at)
                VALUES (?, ?, ?, 20000, 'CASH', ?, ?)
                """, id, session.storeId(), order, session.userId(), OffsetDateTime.parse(TODAY_START));
        jdbc.update("""
                INSERT INTO app.sales_return_line (store_id, return_id, line_no, order_line_id, variant_id, quantity, refund_amount)
                VALUES (?, ?, 1, ?, ?, 2, 20000)
                """, session.storeId(), id, line, variant);
    }

    private Session login(String key) throws Exception {
        var response = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + key + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString();
        return new Session(response.getCookie("access_token"), UUID.fromString(JsonPath.read(body, "$.storeId")),
                UUID.fromString(JsonPath.read(body, "$.userId")));
    }

    private record Session(Cookie cookie, UUID storeId, UUID userId) {}
}
