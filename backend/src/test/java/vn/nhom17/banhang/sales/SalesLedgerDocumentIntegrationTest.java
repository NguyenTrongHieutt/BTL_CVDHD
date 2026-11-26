package vn.nhom17.banhang.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
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

/** Chứng từ bán, hủy và trả trong KHO-07, kiểm với PostgreSQL và phân quyền HTTP thật. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesLedgerDocumentIntegrationTest {
    private static final String DOCUMENTS = "/api/v1/sales/ledger-documents/{type}/{id}";
    private static final OffsetDateTime SOLD_AT = OffsetDateTime.parse("2026-11-23T08:30:00Z");
    private static final OffsetDateTime CANCELLED_AT = OffsetDateTime.parse("2026-11-24T09:45:00Z");
    private static final OffsetDateTime RETURNED_AT = OffsetDateTime.parse("2026-11-25T10:15:00Z");
    private static final String ORIGINAL_PRODUCT = "Tên hàng chụp lúc bán";
    private static final String ORIGINAL_VARIANT = "Quy cách chụp lúc bán";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private Session ownerA;
    private Session ownerB;
    private Session staffA;
    private final UUID saleId = UUID.randomUUID();
    private final UUID cancelledId = UUID.randomUUID();
    private final UUID unrecordedCancellationId = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    @BeforeAll
    void seedRecordedDocuments() throws Exception {
        ownerA = newStore();
        ownerB = newStore();
        staffA = newStaff(ownerA);
        String customer = mvc.perform(post("/api/v1/catalog/customers").cookie(ownerA.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"0912345678\",\"name\":\"Khách chứng từ\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(customer, "$.customerId"));
        String product = mvc.perform(post("/api/v1/catalog/products").cookie(ownerA.cookie())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tên hiện tại trong danh mục\",\"salePrice\":99000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID variantId = UUID.fromString(JsonPath.read(product, "$.variants[0].id"));

        TenantScope.runAs(ownerA.storeId(), () -> new TransactionTemplate(transactions).execute(tx -> {
            insertOrder(saleId, "DH-869701", customerId, "COMPLETED", 60000, 5000, 10000, 1, 44999);
            UUID firstLine = insertLine(saleId, variantId, 1, ORIGINAL_PRODUCT, 3, 5000, 8001, 31999);
            insertLine(saleId, variantId, 2, "Dòng chưa trả", 1, 0, 2000, 13000);
            jdbc.update("""
                    INSERT INTO app.payment (store_id, order_id, method, amount, cash_received, change_amount)
                    VALUES (?, ?, 'CASH', 44999, 50000, 5001)
                    """, ownerA.storeId(), saleId);

            insertOrder(cancelledId, "DH-869702", null, "CANCELLED", 15000, 0, 1000, 0, 14000);
            insertLine(cancelledId, variantId, 1, ORIGINAL_PRODUCT, 1, 0, 1000, 14000);
            jdbc.update("""
                    UPDATE app.sales_order SET cancelled_at = ?, cancelled_by = ?, cancel_reason = 'Khách đổi ý'
                    WHERE store_id = ? AND id = ?
                    """, CANCELLED_AT, ownerA.userId(), ownerA.storeId(), cancelledId);
            insertOrder(unrecordedCancellationId, "DH-869703", customerId, "CANCELLED", 0, 0, 0, 0, 0);

            // Lần trả thứ hai giữ phần tiền làm tròn còn lại; không được tính lại theo giá hiện tại.
            insertReturn(UUID.randomUUID(), firstLine, variantId, 1, 10666, SOLD_AT.plusHours(1), "CASH", "Trả lần đầu");
            insertReturn(returnId, firstLine, variantId, 2, 21333, RETURNED_AT, "BANK_TRANSFER", "Trả lần hai");
            return null;
        }));
    }

    @Test
    void saleUsesTheOriginalSnapshotAndPaidTotalIncludingAllDiscounts() throws Exception {
        mvc.perform(get(DOCUMENTS, "SALE", saleId).cookie(ownerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("SALE"))
                .andExpect(jsonPath("$.documentId").value(saleId.toString()))
                .andExpect(jsonPath("$.code").value("DH-869701"))
                .andExpect(jsonPath("$.recordedAt").value(SOLD_AT.toInstant().toString()))
                .andExpect(jsonPath("$.actorName").value(staffA.name()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.customerName").value("Khách chứng từ"))
                .andExpect(jsonPath("$.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.subtotal").value(60000))
                .andExpect(jsonPath("$.discount").value(15001))
                .andExpect(jsonPath("$.totalAmount").value(44999))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value(ORIGINAL_PRODUCT))
                .andExpect(jsonPath("$.lines[0].variantName").value(ORIGINAL_VARIANT))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].unitAmount").value(15000))
                .andExpect(jsonPath("$.lines[0].amount").value(31999))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.lines[1].lineNo").value(2));
    }

    @Test
    void cancellationShowsWhoCancelledAndWhenWhileTheSaleKeepsItsOriginalEvent() throws Exception {
        mvc.perform(get(DOCUMENTS, "SALE_CANCEL", cancelledId).cookie(ownerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("SALE_CANCEL"))
                .andExpect(jsonPath("$.documentId").value(cancelledId.toString()))
                .andExpect(jsonPath("$.code").value("DH-869702"))
                .andExpect(jsonPath("$.recordedAt").value(CANCELLED_AT.toInstant().toString()))
                .andExpect(jsonPath("$.actorName").value(ownerA.name()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.note").value("Khách đổi ý"))
                .andExpect(jsonPath("$.totalAmount").value(14000))
                .andExpect(jsonPath("$.lines[0].productName").value(ORIGINAL_PRODUCT))
                .andExpect(jsonPath("$.lines[0].quantity").value(1));
        mvc.perform(get(DOCUMENTS, "SALE", cancelledId).cookie(ownerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedAt").value(SOLD_AT.toInstant().toString()))
                .andExpect(jsonPath("$.actorName").value(staffA.name()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.totalAmount").value(14000));
    }

    @Test
    void returnShowsOnlyItsOwnLinesAndSavedRefundWithTheOriginalOrderReference() throws Exception {
        mvc.perform(get(DOCUMENTS, "SALE_RETURN", returnId).cookie(ownerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("SALE_RETURN"))
                .andExpect(jsonPath("$.documentId").value(returnId.toString()))
                .andExpect(jsonPath("$.recordedAt").value(RETURNED_AT.toInstant().toString()))
                .andExpect(jsonPath("$.actorName").value(ownerA.name()))
                .andExpect(jsonPath("$.relatedOrderCode").value("DH-869701"))
                .andExpect(jsonPath("$.orderId").value(saleId.toString()))
                .andExpect(jsonPath("$.customerName").value("Khách chứng từ"))
                .andExpect(jsonPath("$.paymentMethod").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.note").value("Trả lần hai"))
                .andExpect(jsonPath("$.totalAmount").value(21333))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productName").value(ORIGINAL_PRODUCT))
                .andExpect(jsonPath("$.lines[0].variantName").value(ORIGINAL_VARIANT))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].amount").value(21333))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist());
    }

    @Test
    void documentsRequireTheOwnerRoleAndCannotBeReadAcrossStores() throws Exception {
        for (var document : documents()) {
            mvc.perform(get(DOCUMENTS, document.type(), document.id()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(DOCUMENTS, document.type(), document.id()).cookie(staffA.cookie()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
            mvc.perform(get(DOCUMENTS, document.type(), document.id()).cookie(ownerB.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
            mvc.perform(get(DOCUMENTS, document.type(), UUID.randomUUID()).cookie(ownerA.cookie()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void aCancellationMustHaveBeenRecordedAndReturnIdsCannotBeReplacedWithOrderIds() throws Exception {
        mvc.perform(get(DOCUMENTS, "SALE_CANCEL", saleId).cookie(ownerA.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS, "SALE_CANCEL", unrecordedCancellationId).cookie(ownerA.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS, "SALE_RETURN", saleId).cookie(ownerA.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS, "SALE", returnId).cookie(ownerA.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(get(DOCUMENTS, "PURCHASE", saleId).cookie(ownerA.cookie()))
                .andExpect(status().isBadRequest());
    }

    private List<SourceDocument> documents() {
        return List.of(new SourceDocument("SALE", saleId), new SourceDocument("SALE_CANCEL", cancelledId),
                new SourceDocument("SALE_RETURN", returnId));
    }

    private void insertOrder(UUID id, String code, UUID customerId, String state, long subtotal, long lineDiscount,
                             long orderDiscount, long pointsDiscount, long total) {
        jdbc.update("""
                INSERT INTO app.sales_order
                    (id, store_id, order_code, customer_id, status, subtotal, line_discount_total, order_discount,
                     points_discount, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerA.storeId(), code, customerId, state, subtotal, lineDiscount, orderDiscount,
                pointsDiscount, total, staffA.userId(), SOLD_AT);
    }

    private UUID insertLine(UUID orderId, UUID variantId, int number, String name, int quantity, long lineDiscount,
                            long orderShare, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.sales_order_line
                    (id, store_id, order_id, line_no, variant_id, product_name, variant_name, quantity,
                     unit_price, line_discount, order_discount_share, net_line_amount, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 15000, ?, ?, ?, 10000)
                """, id, ownerA.storeId(), orderId, number, variantId, name, ORIGINAL_VARIANT, quantity,
                lineDiscount, orderShare, amount);
        return id;
    }

    private void insertReturn(UUID id, UUID lineId, UUID variantId, int quantity, long refund, OffsetDateTime at,
                              String method, String reason) {
        jdbc.update("""
                INSERT INTO app.sales_return
                    (id, store_id, order_id, refund_amount, refund_method, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerA.storeId(), saleId, refund, method, reason, ownerA.userId(), at);
        jdbc.update("""
                INSERT INTO app.sales_return_line
                    (store_id, return_id, line_no, order_line_id, variant_id, quantity, refund_amount)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """, ownerA.storeId(), id, lineId, variantId, quantity, refund);
    }

    private Session newStore() throws Exception {
        String name = "Chủ thử KHO-07";
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeName":"Cửa hàng chứng từ KHO-07","displayName":"%s",
                                 "loginKey":"kho07.%s@test.vn","password":"matkhau123"}
                                """.formatted(name, UUID.randomUUID().toString().replace("-", ""))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return new Session(response.getCookie("access_token"),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.storeId")),
                UUID.fromString(JsonPath.read(response.getContentAsString(), "$.userId")), name);
    }

    private Session newStaff(Session owner) throws Exception {
        String loginKey = "kho07." + UUID.randomUUID().toString().replace("-", "");
        String name = "Nhân viên bán KHO-07";
        String staff = mvc.perform(post("/api/v1/platform/users").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"loginKey":"%s","displayName":"%s","password":"matkhau123"}
                                """.formatted(loginKey, name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"%s\",\"password\":\"matkhau123\"}".formatted(loginKey)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        return new Session(cookie, owner.storeId(), UUID.fromString(JsonPath.read(staff, "$.userId")), name);
    }

    private record Session(Cookie cookie, UUID storeId, UUID userId, String name) {}
    private record SourceDocument(String type, UUID id) {}
}
