package vn.nhom17.banhang.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.json.JsonCompareMode;
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

    @Test
    void reportsRevenueOrdersRankingsAndStockForTheSelectedStoreAndDateRange() throws Exception {
        Session store = login("dev.chu.a");
        String productName = "Báo cáo thử " + UUID.randomUUID();
        UUID[] productIds = new UUID[2];
        TenantScope.runAs(store.storeId(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            UUID product = product(store, productName);
            UUID variant = variant(store, product, "BC-REPORT-" + UUID.randomUUID().toString().substring(0, 8));
            productIds[0] = product;
            productIds[1] = variant;
            UUID order = insertOrder(store, "DH-997107", "COMPLETED", 30_000, "2024-01-02T17:00:00Z");
            UUID line = insertLine(store, order, variant, 3, 30_000);
            insertReturn(store, order, line, variant, 20_000, "2024-01-03T17:00:00Z");
            jdbc.update("""
                    INSERT INTO app.stock (store_id, variant_id, quantity, avg_unit_cost, min_quantity)
                    VALUES (?, ?, 5, 4000, 0)
                    """, store.storeId(), variant);
        }));

        mvc.perform(get("/api/v1/reporting/q/Q1").cookie(store.cookie())
                        .param("fromDate", "2024-01-03").param("toDate", "2024-01-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.fromDate").value("2024-01-03"))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].grossSales").value(30_000))
                .andExpect(jsonPath("$.rows[0].revenue").value(30_000))
                .andExpect(jsonPath("$.rows[1].refundTotal").value(20_000))
                .andExpect(jsonPath("$.summary.revenue").value(10_000));

        mvc.perform(get("/api/v1/reporting/q/Q2").cookie(store.cookie())
                        .param("fromDate", "2024-01-03").param("toDate", "2024-01-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.orderCount").value(1))
                .andExpect(jsonPath("$.summary.returnCount").value(1));

        mvc.perform(get("/api/v1/reporting/q/Q4").cookie(store.cookie())
                        .param("fromDate", "2024-01-03").param("toDate", "2024-01-04")
                        .param("direction", "BEST_SELLING").param("rankBy", "QUANTITY").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].productId").value(productIds[0].toString()))
                .andExpect(jsonPath("$.rows[0].quantity").value(1))
                .andExpect(jsonPath("$.rows[0].revenue").value(10_000));

        mvc.perform(get("/api/v1/reporting/q/Q5").cookie(store.cookie()).param("productName", productName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.stockQuantity").value(5))
                .andExpect(jsonPath("$.summary.stockValue").value(20_000))
                .andExpect(jsonPath("$.rows[0].variantId").value(productIds[1].toString()));

        mvc.perform(get("/api/v1/reporting/q/Q1").cookie(store.cookie())
                        .param("fromDate", "2024-01-01").param("toDate", "2025-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
    }

    @Test
    void reportsGrossProfitFromSaleSnapshotsAndReturnsOnTheirVietnameseDates() throws Exception {
        Session storeA = login("dev.chu.a");
        Session storeB = login("dev.chu.b");
        TenantScope.runAs(storeA.storeId(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            UUID first = variant(storeA, product(storeA, "Giá vốn chụp lúc bán"), "BC-PROFIT-A");
            UUID second = variant(storeA, product(storeA, "Giá vốn khác"), "BC-PROFIT-B");

            UUID oldOrder = insertOrder(storeA, "DH-997201", "RETURNED", 9_000, "2023-02-09T16:59:59Z");
            UUID oldLine = insertLine(storeA, oldOrder, first, 1, 9_000, 1, 3_000);
            insertReturn(storeA, oldOrder, 9_000, "2023-02-10T17:00:00Z",
                    new ReturnedLine(oldLine, first, 1, 9_000));

            UUID order = insertOrder(storeA, "DH-997202", "COMPLETED", 72_000, "2023-02-09T17:00:00Z");
            jdbc.update("UPDATE app.sales_order SET subtotal = 80000, order_discount = 8000 WHERE id = ? AND store_id = ?",
                    order, storeA.storeId());
            UUID firstLine = insertLine(storeA, order, first, 3, 27_000, 1, 5_000);
            UUID secondLine = insertLine(storeA, order, second, 2, 45_000, 2, 12_000);
            insertReturn(storeA, order, 31_500, "2023-02-11T16:59:59Z",
                    new ReturnedLine(firstLine, first, 1, 9_000),
                    new ReturnedLine(secondLine, second, 1, 22_500));

            insertLine(storeA, insertOrder(storeA, "DH-997203", "COMPLETED", 15_000, "2023-02-13T16:59:59Z"),
                    first, 1, 15_000, 1, 17_000);
            insertLine(storeA, insertOrder(storeA, "DH-997204", "COMPLETED", 90_000, "2023-02-13T17:00:00Z"),
                    first, 9, 90_000);
            UUID cancelled = insertOrder(storeA, "DH-997205", "CANCELLED", 90_000, "2023-02-09T17:00:00Z");
            UUID cancelledLine = insertLine(storeA, cancelled, first, 9, 90_000);
            insertReturn(storeA, cancelled, 10_000, "2023-02-10T17:00:00Z",
                    new ReturnedLine(cancelledLine, first, 1, 10_000));

            jdbc.update("INSERT INTO app.stock (store_id, variant_id, quantity, avg_unit_cost) VALUES (?, ?, 5, 99000)",
                    storeA.storeId(), first);
            jdbc.update("UPDATE app.product_variant SET sale_price = 120000 WHERE id = ? AND store_id = ?",
                    first, storeA.storeId());
        }));
        TenantScope.runAs(storeB.storeId(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            UUID other = variant(storeB, product(storeB, "Lợi nhuận cửa hàng B"), "BC-PROFIT-OTHER");
            insertLine(storeB, insertOrder(storeB, "DH-997201", "COMPLETED", 900_000, "2023-02-09T17:00:00Z"),
                    other, 9, 900_000, 1, 50_000);
        }));

        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(storeA.cookie())
                        .param("fromDate", "2023-02-10").param("toDate", "2023-02-13"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "queryId": "Q3",
                          "range": {"fromDate": "2023-02-10", "toDate": "2023-02-13"},
                          "params": {"fromDate": "2023-02-10", "toDate": "2023-02-13"},
                          "columns": [
                            {"key": "date", "label": "Ngày", "type": "date"},
                            {"key": "revenue", "label": "Doanh thu thuần", "type": "money"},
                            {"key": "costOfGoodsSold", "label": "Giá vốn hàng bán", "type": "money"},
                            {"key": "grossProfit", "label": "Lợi nhuận gộp", "type": "money"}
                          ],
                          "rows": [
                            {"date": "2023-02-10", "revenue": 72000, "costOfGoodsSold": 39000, "grossProfit": 33000},
                            {"date": "2023-02-11", "revenue": -40500, "costOfGoodsSold": -20000, "grossProfit": -20500},
                            {"date": "2023-02-12", "revenue": 0, "costOfGoodsSold": 0, "grossProfit": 0},
                            {"date": "2023-02-13", "revenue": 15000, "costOfGoodsSold": 17000, "grossProfit": -2000}
                          ],
                          "summary": {"revenue": 46500, "costOfGoodsSold": 36000, "grossProfit": 10500}
                        }
                        """, JsonCompareMode.STRICT));

        mvc.perform(get("/api/v1/reporting/q/Q1").cookie(storeA.cookie())
                        .param("fromDate", "2023-02-10").param("toDate", "2023-02-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].revenue").value(72_000))
                .andExpect(jsonPath("$.rows[1].revenue").value(-40_500))
                .andExpect(jsonPath("$.rows[2].revenue").value(0))
                .andExpect(jsonPath("$.rows[3].revenue").value(15_000))
                .andExpect(jsonPath("$.summary.revenue").value(46_500));

        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(storeB.cookie())
                        .param("fromDate", "2023-02-10").param("toDate", "2023-02-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.revenue").value(900_000))
                .andExpect(jsonPath("$.summary.costOfGoodsSold").value(450_000))
                .andExpect(jsonPath("$.summary.grossProfit").value(450_000));
    }

    @Test
    void requiresAGrossProfitDateRangeWithin366Days() throws Exception {
        Session owner = login("dev.chu.a");
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromDate"));
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie()).param("fromDate", "2023-02-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromDate"));
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie()).param("toDate", "2023-02-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromDate"));
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie())
                        .param("fromDate", "2023-02-11").param("toDate", "2023-02-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fromDate"));
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie())
                        .param("fromDate", "2020-01-01").param("toDate", "2021-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
    }

    @Test
    void fillsEveryGrossProfitDayWithNumericZerosIncludingLeapDay() throws Exception {
        Session owner = login("dev.chu.a");
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie())
                        .param("fromDate", "2020-01-01").param("toDate", "2020-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(366))
                .andExpect(jsonPath("$.rows[59].date").value("2020-02-29"))
                .andExpect(jsonPath("$.rows[365].date").value("2020-12-31"))
                .andExpect(jsonPath("$.summary.revenue").value(0))
                .andExpect(jsonPath("$.summary.costOfGoodsSold").value(0))
                .andExpect(jsonPath("$.summary.grossProfit").value(0));
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(owner.cookie())
                        .param("fromDate", "2020-02-29").param("toDate", "2020-02-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].date").value("2020-02-29"))
                .andExpect(jsonPath("$.rows[0].revenue").value(0))
                .andExpect(jsonPath("$.rows[0].costOfGoodsSold").value(0))
                .andExpect(jsonPath("$.rows[0].grossProfit").value(0));
    }

    @Test
    void rejectsStaffAndAnonymousGrossProfitRequests() throws Exception {
        mvc.perform(get("/api/v1/reporting/q/Q3").param("fromDate", "2023-02-10").param("toDate", "2023-02-13"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reporting/q/Q3").cookie(login("dev.nv.a").cookie())
                        .param("fromDate", "2023-02-10").param("toDate", "2023-02-13"))
                .andExpect(status().isForbidden());
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
        return insertLine(session, order, variant, quantity, amount, 1, 5_000);
    }

    private UUID insertLine(Session session, UUID order, UUID variant, int quantity, long amount, int lineNo, long unitCost) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order_line (id, store_id, order_id, line_no, variant_id, product_name,
                    quantity, unit_price, net_line_amount, unit_cost)
                VALUES (?, ?, ?, ?, ?, 'Hàng thử', ?, 10000, ?, ?)
                """, id, session.storeId(), order, lineNo, variant, quantity, amount, unitCost);
        return id;
    }

    private void insertReturn(Session session, UUID order, UUID line, UUID variant) {
        insertReturn(session, order, line, variant, 20_000, TODAY_START);
    }

    private void insertReturn(Session session, UUID order, UUID line, UUID variant, long amount, String timestamp) {
        insertReturn(session, order, amount, timestamp, new ReturnedLine(line, variant, 2, amount));
    }

    private void insertReturn(Session session, UUID order, long amount, String timestamp, ReturnedLine... lines) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_return (id, store_id, order_id, refund_amount, refund_method, created_by, created_at)
                VALUES (?, ?, ?, ?, 'CASH', ?, ?)
                """, id, session.storeId(), order, amount, session.userId(), OffsetDateTime.parse(timestamp));
        for (int index = 0; index < lines.length; index++) {
            ReturnedLine line = lines[index];
            jdbc.update("""
                    INSERT INTO app.sales_return_line (store_id, return_id, line_no, order_line_id, variant_id, quantity, refund_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, session.storeId(), id, index + 1, line.orderLineId(), line.variantId(), line.quantity(), line.amount());
        }
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
    private record ReturnedLine(UUID orderLineId, UUID variantId, int quantity, long amount) {}
}
