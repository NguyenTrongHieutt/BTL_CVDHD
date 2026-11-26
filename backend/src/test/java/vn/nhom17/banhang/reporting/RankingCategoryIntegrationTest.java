package vn.nhom17.banhang.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RankingCategoryIntegrationTest {
    private static final String RANKING = "/api/v1/reporting/q/Q4";
    private static final AtomicInteger ORDER_CODE = new AtomicInteger(964000);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void filtersExactCategoryBeforeRankingByEitherMetricAndLimiting() throws Exception {
        Session store = login("dev.chu.a");
        UUID categoryId = UUID.randomUUID();
        UUID[] products = new UUID[2];
        inStore(store, () -> {
            String categoryName = "Đồ uống " + categoryId;
            category(store, categoryId, categoryName);
            UUID similarCategory = category(store, categoryName + " cao cấp");
            products[0] = product(store, categoryId, "Bán nhiều đơn vị");
            UUID first = variant(store, products[0]);
            UUID second = variant(store, products[0]);
            sale(store, first, 10, 10_000, "COMPLETED", "2001-04-03T17:00:00Z");
            Sale returned = sale(store, second, 4, 4_000, "COMPLETED", "2001-04-04T10:00:00Z");
            returned(store, returned, 2, 2_000, "2001-04-05T16:59:59Z");
            sale(store, first, 900, 900_000, "COMPLETED", "2001-04-05T17:00:00Z");
            sale(store, first, 1_000, 1_000_000, "CANCELLED", "2001-04-04T10:00:00Z");
            products[1] = product(store, categoryId, "Doanh thu cao");
            sale(store, variant(store, products[1]), 2, 20_000, "COMPLETED", "2001-04-04T10:00:00Z");
            sale(store, variant(store, product(store, similarCategory, "Khác danh mục")),
                    5_000, 5_000_000, "COMPLETED", "2001-04-04T10:00:00Z");
        });

        for (String direction : new String[]{"BEST_SELLING", "SLOW_SELLING"}) {
            for (String rankBy : new String[]{"QUANTITY", "REVENUE"}) {
                boolean quantityWinner = direction.equals("BEST_SELLING") == rankBy.equals("QUANTITY");
                mvc.perform(ranking(store, categoryId, "2001-04-04", "2001-04-05")
                                .param("direction", direction).param("rankBy", rankBy).param("limit", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.params.categoryId").value(categoryId.toString()))
                        .andExpect(jsonPath("$.params.direction").value(direction))
                        .andExpect(jsonPath("$.params.rankBy").value(rankBy))
                        .andExpect(jsonPath("$.params.limit").value(1))
                        .andExpect(jsonPath("$.rows.length()").value(1))
                        .andExpect(jsonPath("$.rows[0].rank").value(1))
                        .andExpect(jsonPath("$.rows[0].productId").value(products[quantityWinner ? 0 : 1].toString()))
                        .andExpect(jsonPath("$.rows[0].quantity").value(quantityWinner ? 12 : 2))
                        .andExpect(jsonPath("$.rows[0].revenue").value(quantityWinner ? 12_000 : 20_000))
                        .andExpect(jsonPath("$.rows[0].soldQuantity").value(quantityWinner ? 14 : 2))
                        .andExpect(jsonPath("$.rows[0].returnedQuantity").value(quantityWinner ? 2 : 0))
                        .andExpect(jsonPath("$.summary.productCount").value(1));
            }
        }
    }

    @Test
    void slowRankingKeepsUnsoldActiveProductsInTheSelectedCategoryAndDateRange() throws Exception {
        Session store = login("dev.chu.a");
        UUID categoryId = UUID.randomUUID();
        UUID[] products = new UUID[3];
        inStore(store, () -> {
            category(store, categoryId, "Bán chậm " + categoryId);
            products[0] = product(store, categoryId, "A Chưa bán");
            variant(store, products[0]);
            products[1] = product(store, categoryId, "B Bán ngoài kỳ");
            sale(store, variant(store, products[1]), 30, 30_000, "COMPLETED", "2002-04-03T16:59:59Z");
            products[2] = product(store, categoryId, "C Bán trong kỳ");
            sale(store, variant(store, products[2]), 3, 30_000, "COMPLETED", "2002-04-04T10:00:00Z");
            UUID inactiveProduct = product(store, categoryId, "Không còn bán");
            variant(store, inactiveProduct);
            jdbc.update("UPDATE app.product SET status = 'INACTIVE' WHERE id = ?", inactiveProduct);
            UUID deletedProduct = product(store, categoryId, "Đã xóa");
            variant(store, deletedProduct);
            jdbc.update("UPDATE app.product SET deleted_at = now() WHERE id = ?", deletedProduct);
            product(store, categoryId, "Không có biến thể");
            UUID inactiveVariant = variant(store, product(store, categoryId, "Biến thể ngừng bán"));
            jdbc.update("UPDATE app.product_variant SET status = 'INACTIVE' WHERE id = ?", inactiveVariant);
            UUID deletedVariant = variant(store, product(store, categoryId, "Biến thể đã xóa"));
            jdbc.update("UPDATE app.product_variant SET deleted_at = now() WHERE id = ?", deletedVariant);
            variant(store, product(store, category(store, "Danh mục khác " + categoryId), "0 Khác danh mục"));
        });

        for (String rankBy : new String[]{"QUANTITY", "REVENUE"}) {
            mvc.perform(ranking(store, categoryId, "2002-04-04", "2002-04-04")
                            .param("direction", "SLOW_SELLING").param("rankBy", rankBy).param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(3))
                    .andExpect(jsonPath("$.rows[0].productId").value(products[0].toString()))
                    .andExpect(jsonPath("$.rows[0].quantity").value(0))
                    .andExpect(jsonPath("$.rows[0].revenue").value(0))
                    .andExpect(jsonPath("$.rows[1].productId").value(products[1].toString()))
                    .andExpect(jsonPath("$.rows[1].quantity").value(0))
                    .andExpect(jsonPath("$.rows[2].productId").value(products[2].toString()))
                    .andExpect(jsonPath("$.rows[2].rank").value(3))
                    .andExpect(jsonPath("$.rows[2].quantity").value(3));
        }
    }

    @Test
    void combinesCategoryWithTenTwentyAndFiftyResultLimits() throws Exception {
        Session store = login("dev.chu.a");
        UUID categoryId = UUID.randomUUID();
        UUID[] products = new UUID[55];
        inStore(store, () -> {
            category(store, categoryId, "Giới hạn " + categoryId);
            for (int index = 0; index < products.length; index++) {
                products[index] = product(store, categoryId, "Sản phẩm " + index);
                sale(store, variant(store, products[index]), index + 1, (index + 1) * 100L,
                        "COMPLETED", "2003-04-04T10:00:00Z");
            }
            UUID otherCategory = category(store, "Ngoài giới hạn " + categoryId);
            sale(store, variant(store, product(store, otherCategory, "Hạng đầu toàn cửa hàng")),
                    9_999, 9_999_999, "COMPLETED", "2003-04-04T10:00:00Z");
        });

        for (String direction : new String[]{"BEST_SELLING", "SLOW_SELLING"}) {
            for (String rankBy : new String[]{"QUANTITY", "REVENUE"}) {
                for (int limit : new int[]{10, 20, 50}) {
                    int first = direction.equals("BEST_SELLING") ? 54 : 0;
                    int last = direction.equals("BEST_SELLING") ? 55 - limit : limit - 1;
                    mvc.perform(ranking(store, categoryId, "2003-04-04", "2003-04-04")
                                    .param("direction", direction).param("rankBy", rankBy)
                                    .param("limit", Integer.toString(limit)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.rows.length()").value(limit))
                            .andExpect(jsonPath("$.rows[0].productId").value(products[first].toString()))
                            .andExpect(jsonPath("$.rows[0].rank").value(1))
                            .andExpect(jsonPath("$.rows[" + (limit - 1) + "].productId").value(products[last].toString()))
                            .andExpect(jsonPath("$.rows[" + (limit - 1) + "].rank").value(limit))
                            .andExpect(jsonPath("$.summary.productCount").value(limit));
                }
            }
        }
    }

    @Test
    void categoryFromAnotherStoreOrUnknownCategoryReturnsNoProducts() throws Exception {
        Session storeA = login("dev.chu.a");
        Session storeB = login("dev.chu.b");
        UUID foreignCategory = UUID.randomUUID();
        String categoryName = "Cùng tên " + foreignCategory;
        inStore(storeB, () -> {
            category(storeB, foreignCategory, categoryName);
            sale(storeB, variant(storeB, product(storeB, foreignCategory, "Hàng cửa hàng B")),
                    100, 100_000, "COMPLETED", "2004-04-04T10:00:00Z");
        });
        inStore(storeA, () -> {
            UUID localCategory = category(storeA, categoryName);
            sale(storeA, variant(storeA, product(storeA, localCategory, "Hàng cửa hàng A")),
                    10, 10_000, "COMPLETED", "2004-04-04T10:00:00Z");
            // Foreign keys allow a mismatched category; the report must still enforce category ownership.
            sale(storeA, variant(storeA, product(storeA, foreignCategory, "Danh mục sai cửa hàng")),
                    20, 20_000, "COMPLETED", "2004-04-04T10:00:00Z");
        });

        for (UUID categoryId : new UUID[]{foreignCategory, UUID.randomUUID()}) {
            for (String direction : new String[]{"BEST_SELLING", "SLOW_SELLING"}) {
                mvc.perform(ranking(storeA, categoryId, "2004-04-04", "2004-04-04")
                                .param("direction", direction))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.params.categoryId").value(categoryId.toString()))
                        .andExpect(jsonPath("$.rows.length()").value(0))
                        .andExpect(jsonPath("$.summary.productCount").value(0));
            }
        }
        mvc.perform(ranking(storeB, foreignCategory, "2004-04-04", "2004-04-04")
                        .param("direction", "BEST_SELLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].productName").value("Hàng cửa hàng B"));
    }

    @Test
    void requestsWithoutCategoryKeepTheirExistingParametersAndIncludeUncategorizedProducts() throws Exception {
        Session store = login("dev.chu.a");
        UUID[] products = new UUID[2];
        inStore(store, () -> {
            products[0] = product(store, category(store, "Không lọc " + UUID.randomUUID()), "Có danh mục");
            products[1] = product(store, null, "Chưa có danh mục");
            sale(store, variant(store, products[0]), 20, 20_000, "COMPLETED", "2005-04-04T10:00:00Z");
            sale(store, variant(store, products[1]), 10, 10_000, "COMPLETED", "2005-04-04T10:00:00Z");
        });

        mvc.perform(get(RANKING).cookie(store.cookie()).param("fromDate", "2005-04-04")
                        .param("toDate", "2005-04-04").param("direction", "BEST_SELLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryId").value("Q4"))
                .andExpect(jsonPath("$.params.categoryId").doesNotExist())
                .andExpect(jsonPath("$.params.rankBy").value("QUANTITY"))
                .andExpect(jsonPath("$.params.limit").value(10))
                .andExpect(jsonPath("$.columns.length()").value(7))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].productId").value(products[0].toString()))
                .andExpect(jsonPath("$.rows[1].productId").value(products[1].toString()));
    }

    @Test
    void rejectsMalformedCategoryId() throws Exception {
        mvc.perform(get(RANKING).cookie(login("dev.chu.a").cookie())
                        .param("direction", "BEST_SELLING").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder ranking(Session store, UUID categoryId, String from, String to) {
        return get(RANKING).cookie(store.cookie()).param("categoryId", categoryId.toString())
                .param("fromDate", from).param("toDate", to);
    }

    private void inStore(Session store, Runnable work) {
        TenantScope.runAs(store.storeId(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> work.run()));
    }

    private UUID category(Session store, String name) {
        UUID id = UUID.randomUUID();
        category(store, id, name);
        return id;
    }

    private void category(Session store, UUID id, String name) {
        jdbc.update("INSERT INTO app.category (id, store_id, name) VALUES (?, ?, ?)", id, store.storeId(), name);
    }

    private UUID product(Session store, UUID categoryId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.product (id, store_id, category_id, name) VALUES (?, ?, ?, ?)",
                id, store.storeId(), categoryId, name);
        return id;
    }

    private UUID variant(Session store, UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.product_variant (id, store_id, product_id, sku, sale_price)
                VALUES (?, ?, ?, ?, 10000)
                """, id, store.storeId(), productId, "BC-CATEGORY-" + id);
        return id;
    }

    private Sale sale(Session store, UUID variant, int quantity, long amount, String status, String timestamp) {
        UUID order = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order (id, store_id, order_code, status, subtotal, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, order, store.storeId(), "DH-" + ORDER_CODE.incrementAndGet(), status, amount, amount,
                store.userId(), OffsetDateTime.parse(timestamp));
        UUID line = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order_line (id, store_id, order_id, line_no, variant_id, product_name,
                    quantity, unit_price, net_line_amount, unit_cost)
                VALUES (?, ?, ?, 1, ?, 'Hàng thử', ?, 10000, ?, 1000)
                """, line, store.storeId(), order, variant, quantity, amount);
        return new Sale(order, line, variant);
    }

    private void returned(Session store, Sale sale, int quantity, long amount, String timestamp) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_return (id, store_id, order_id, refund_amount, refund_method, created_by, created_at)
                VALUES (?, ?, ?, ?, 'CASH', ?, ?)
                """, id, store.storeId(), sale.order(), amount, store.userId(), OffsetDateTime.parse(timestamp));
        jdbc.update("""
                INSERT INTO app.sales_return_line (store_id, return_id, line_no, order_line_id, variant_id, quantity, refund_amount)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """, store.storeId(), id, sale.line(), sale.variant(), quantity, amount);
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
    private record Sale(UUID order, UUID line, UUID variant) {}
}
