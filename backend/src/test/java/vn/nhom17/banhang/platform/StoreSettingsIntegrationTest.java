package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;

/**
 * Thông tin Cửa hàng (FR-48, TK-05) trên PostgreSQL thật: toàn bộ ma trận I/O của spec. Mỗi test dùng Cửa hàng mới
 * đăng ký riêng (không đụng Cửa hàng dev A mà test khác kiểm tên), trừ test Nhân viên dùng {@code dev.nv.a}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StoreSettingsIntegrationTest {

    static final String STORE = "/api/v1/platform/store";
    static final UUID STORE_A = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    static final String STORE_NAME = "Tạp hóa thử Cửa hàng";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired StoreSettings settings;

    record Owner(Cookie cookie, UUID storeId, UUID userId) {
    }

    // --- Xem ---------------------------------------------------------------------------------------------------

    @Test
    void ownerSeesTheirOwnStoreWithDefaults() throws Exception {
        Owner owner = newStore();
        mvc.perform(get(STORE).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(STORE_NAME))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.address").value(nullValue()))
                .andExpect(jsonPath("$.invoiceFooter").value(nullValue()))
                .andExpect(jsonPath("$.bankBin").value(nullValue()))
                .andExpect(jsonPath("$.bankAccountNo").value(nullValue()))
                .andExpect(jsonPath("$.bankAccountName").value(nullValue()))
                .andExpect(jsonPath("$.loyaltyEarnAmount").value(10000))
                .andExpect(jsonPath("$.loyaltyPointValue").value(1000))
                .andExpect(jsonPath("$.expiryWarningDays").value(30));

        // Cửa hàng khác thấy thông tin của chính nó
        Owner other = newStore("Cửa hàng khác");
        mvc.perform(get(STORE).cookie(other.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cửa hàng khác"));
    }

    // --- Sửa ---------------------------------------------------------------------------------------------------

    @Test
    void fullUpdateSavesEverythingAndWritesOneAuditRowWithOnlyTheChangedFields() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = fullBody();
        String response = save(owner, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tạp hóa Hoa"))
                .andExpect(jsonPath("$.phone").value("0912 345 678"))
                .andExpect(jsonPath("$.address").value("12 Lê Lợi, Quận 1"))
                .andExpect(jsonPath("$.invoiceFooter").value("Cảm ơn quý khách!"))
                .andExpect(jsonPath("$.bankBin").value("970436"))
                .andExpect(jsonPath("$.bankAccountNo").value("0011001234567"))
                .andExpect(jsonPath("$.bankAccountName").value("NGUYEN VAN A"))
                .andExpect(jsonPath("$.loyaltyEarnAmount").value(20000))
                .andExpect(jsonPath("$.loyaltyPointValue").value(500))
                .andExpect(jsonPath("$.expiryWarningDays").value(14))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // GET trả cùng dạng với PUT
        String read = mvc.perform(get(STORE).cookie(owner.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(read)).isEqualTo(json.readTree(response));

        // StoreSettings của Anh (VietQR, hóa đơn) thấy giá trị mới ngay
        StoreSettings.View current = TenantScope.runAs(owner.storeId(), () -> settings.current());
        assertThat(current.hasBankAccount()).isTrue();
        assertThat(current.name()).isEqualTo("Tạp hóa Hoa");
        assertThat(current.bankBin()).isEqualTo("970436");
        assertThat(current.bankAccountNo()).isEqualTo("0011001234567");
        assertThat(current.bankAccountName()).isEqualTo("NGUYEN VAN A");
        assertThat(current.loyaltyEarnAmount()).isEqualTo(20000);
        assertThat(current.loyaltyPointValue()).isEqualTo(500);
        assertThat(current.expiryWarningDays()).isEqualTo(14);
        assertThat(current.invoiceFooter()).isEqualTo("Cảm ơn quý khách!");

        // Tên ở thanh trên cùng (/me) đổi theo, không cần đăng nhập lại
        mvc.perform(get("/api/v1/platform/me").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Tạp hóa Hoa"));

        List<Map<String, Object>> audit = auditRows(owner.storeId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst())
                .containsEntry("action", "platform.store.update")
                .containsEntry("entity", "store")
                .containsEntry("entity_id", owner.storeId().toString())
                .containsEntry("actor_id", owner.userId());
        assertThat(audit.getFirst().get("request_id")).isNotNull();
        JsonNode before = json.readTree((String) audit.getFirst().get("before"));
        JsonNode after = json.readTree((String) audit.getFirst().get("after"));
        assertThat(after).isEqualTo(json.readTree("""
                {"name": "Tạp hóa Hoa", "phone": "0912 345 678", "address": "12 Lê Lợi, Quận 1",
                 "invoiceFooter": "Cảm ơn quý khách!", "bankBin": "970436", "bankAccountNo": "****4567",
                 "bankAccountName": "NGUYEN VAN A", "loyaltyEarnAmount": 20000, "loyaltyPointValue": 500,
                 "expiryWarningDays": 14}
                """));
        // Nhật ký chỉ giữ 4 số cuối của số tài khoản
        assertThat((String) audit.getFirst().get("after")).doesNotContain("0011001234567");
        assertThat(before).isEqualTo(json.readTree("""
                {"name": "%s", "phone": null, "address": null, "invoiceFooter": null, "bankBin": null,
                 "bankAccountNo": null, "bankAccountName": null, "loyaltyEarnAmount": 10000,
                 "loyaltyPointValue": 1000, "expiryWarningDays": 30}
                """.formatted(STORE_NAME)));

        // Lần sau chỉ đổi tên: Nhật ký chỉ có trường tên
        body.put("name", "Tạp hóa Hoa 2");
        save(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tạp hóa Hoa 2"));
        audit = auditRows(owner.storeId());
        assertThat(audit).hasSize(2);
        assertThat(json.readTree((String) audit.getLast().get("before")))
                .isEqualTo(json.readTree("{\"name\": \"Tạp hóa Hoa\"}"));
        assertThat(json.readTree((String) audit.getLast().get("after")))
                .isEqualTo(json.readTree("{\"name\": \"Tạp hóa Hoa 2\"}"));
    }

    @Test
    void emptyBankFieldsTurnBankTransferOff() throws Exception {
        Owner owner = newStore();
        save(owner, fullBody()).andExpect(status().isOk());

        Map<String, Object> body = fullBody();
        body.put("bankBin", "");
        body.put("bankAccountNo", "  ");
        body.put("bankAccountName", null);
        save(owner, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankBin").value(nullValue()))
                .andExpect(jsonPath("$.bankAccountNo").value(nullValue()))
                .andExpect(jsonPath("$.bankAccountName").value(nullValue()));

        StoreSettings.View current = TenantScope.runAs(owner.storeId(), () -> settings.current());
        assertThat(current.hasBankAccount()).isFalse();
        assertThat(storeRow(owner.storeId()))
                .containsEntry("bank_bin", null)
                .containsEntry("bank_account_no", null)
                .containsEntry("bank_account_name", null);

        List<Map<String, Object>> audit = auditRows(owner.storeId());
        assertThat(audit).hasSize(2);
        assertThat(json.readTree((String) audit.getLast().get("before"))).isEqualTo(json.readTree("""
                {"bankBin": "970436", "bankAccountNo": "****4567", "bankAccountName": "NGUYEN VAN A"}
                """));
        assertThat(json.readTree((String) audit.getLast().get("after"))).isEqualTo(json.readTree("""
                {"bankBin": null, "bankAccountNo": null, "bankAccountName": null}
                """));

        // Thiếu hẳn ba trường cũng là tắt chuyển khoản; trường văn bản không bắt buộc bỏ trống thì xóa giá trị cũ
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("name", "Tạp hóa Hoa");
        minimal.put("loyaltyEarnAmount", 20000);
        minimal.put("loyaltyPointValue", 500);
        minimal.put("expiryWarningDays", 14);
        save(owner, minimal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.address").value(nullValue()))
                .andExpect(jsonPath("$.invoiceFooter").value(nullValue()))
                .andExpect(jsonPath("$.bankBin").value(nullValue()));
        assertThat(storeRow(owner.storeId())).containsEntry("phone", null).containsEntry("address", null)
                .containsEntry("invoice_footer", null);
    }

    @Test
    void unchangedPutReturnsOkAndWritesNoAuditRow() throws Exception {
        Owner owner = newStore();
        save(owner, fullBody()).andExpect(status().isOk());
        Object updatedAt = storeRow(owner.storeId()).get("updated_at");

        // Cùng nội dung, kể cả gõ khác mà chuẩn hóa ra như cũ (NFD, khoảng trắng hai đầu, ký tự vô hình)
        Map<String, Object> same = fullBody();
        same.put("name", " " + Normalizer.normalize("Tạp hóa Hoa", Normalizer.Form.NFD) + "​ ");
        same.put("bankAccountName", " NGUYEN  VAN A ");
        same.put("loyaltyEarnAmount", 20000.0);
        save(owner, same).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tạp hóa Hoa"));
        save(owner, fullBody()).andExpect(status().isOk());

        assertThat(auditRows(owner.storeId())).hasSize(1);
        assertThat(storeRow(owner.storeId()).get("updated_at")).isEqualTo(updatedAt);
    }

    /** Khóa dòng {@code app.store}: N lần lưu cùng nội dung cùng lúc chỉ một lần đổi được, một dòng Nhật ký. */
    @Test
    void concurrentIdenticalSavesWriteOneAuditRow() throws Exception {
        Owner owner = newStore();
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return save(owner, fullBody()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> audit = auditRows(owner.storeId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("action", "platform.store.update");
        assertThat(storeRow(owner.storeId())).containsEntry("name", "Tạp hóa Hoa").containsEntry("bank_bin", "970436");
    }

    // --- Lỗi nhập liệu ------------------------------------------------------------------------------------------

    @Test
    void partialBankAccountIsRejectedOnTheMissingFieldAndChangesNothing() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = fullBody();
        body.put("bankAccountNo", "");
        rejected(owner, body, "bankAccountNo");

        body = fullBody();
        body.put("bankBin", null);
        body.put("bankAccountName", "");
        rejected(owner, body, "bankBin", "bankAccountName");

        assertUnchanged(owner);
    }

    @Test
    void bankFieldsMustHaveTheRightFormat() throws Exception {
        Owner owner = newStore();
        for (String bin : List.of("97043", "9704361", "97043a", "970 436")) {
            rejectedWith(owner, "bankBin", bin, "bankBin");
        }
        for (String accountNo : List.of("12345", "1".repeat(20), "0011-001", "0011 0012")) {
            rejectedWith(owner, "bankAccountNo", accountNo, "bankAccountNo");
        }
        for (String accountName : List.of("Nguyễn Văn A", "nguyen van a", "NGUYỄN VĂN A", "NGUYEN VAN A1",
                "ĐINH VAN B", "A".repeat(51))) {
            rejectedWith(owner, "bankAccountName", accountName, "bankAccountName");
        }
        assertUnchanged(owner);

        // Đúng biên: 6 và 19 chữ số, tên 50 ký tự
        Map<String, Object> body = fullBody();
        body.put("bankAccountNo", "123456");
        body.put("bankAccountName", "A".repeat(50));
        save(owner, body).andExpect(status().isOk());
        body.put("bankAccountNo", "1".repeat(19));
        save(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.bankAccountNo").value("1".repeat(19)));
    }

    @Test
    void nameIsRequiredNormalizedAndAtMost200Characters() throws Exception {
        Owner owner = newStore();
        for (Object name : new Object[] {null, "", "   ", " 　", "​﻿", "a".repeat(201),
                "ệ".repeat(201), "Tạp\u0000hóa", "Tạp\thóa", "Tạp\nhóa", "Tạp hóa"}) {
            rejectedWith(owner, "name", name, "name");
        }
        assertUnchanged(owner);

        String nfd = Normalizer.normalize("ệ", Normalizer.Form.NFD);
        Map<String, Object> body = fullBody();
        body.put("name", " " + nfd.repeat(200) + " ");
        save(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("ệ".repeat(200)));
        body.put("name", "﻿Tạp​ hóa　Hoa ");
        save(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tạp hóa Hoa"));
    }

    @Test
    void phoneAddressAndInvoiceFooterAreChecked() throws Exception {
        Owner owner = newStore();
        for (String phone : List.of("0912abc", "0912/345", "---", "1".repeat(21), "+84 (912) 345")) {
            rejectedWith(owner, "phone", phone, "phone");
        }
        rejectedWith(owner, "address", "a".repeat(301), "address");
        rejectedWith(owner, "address", "12\nLê Lợi", "address");
        rejectedWith(owner, "invoiceFooter", "ê".repeat(301), "invoiceFooter");
        rejectedWith(owner, "invoiceFooter", "Cảm ơn\u0007", "invoiceFooter");
        assertUnchanged(owner);

        Map<String, Object> body = fullBody();
        body.put("phone", "+84 912.345-678");
        body.put("address", "a".repeat(300));
        body.put("invoiceFooter", "ê".repeat(300));
        save(owner, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+84 912.345-678"))
                .andExpect(jsonPath("$.address").value("a".repeat(300)));
        body.put("phone", "1".repeat(20));
        save(owner, body).andExpect(status().isOk());
    }

    @Test
    void numbersAreRequiredWholeAndWithinRange() throws Exception {
        Owner owner = newStore();
        for (Object value : new Object[] {null, 0, -1, 1.5, 9_007_199_254_740_992L}) {
            rejectedWith(owner, "loyaltyEarnAmount", value, "loyaltyEarnAmount");
        }
        for (Object value : new Object[] {null, -1, 0.5}) {
            rejectedWith(owner, "loyaltyPointValue", value, "loyaltyPointValue");
        }
        for (Object value : new Object[] {null, -1, 366, 7.5}) {
            rejectedWith(owner, "expiryWarningDays", value, "expiryWarningDays");
        }
        assertUnchanged(owner);

        Map<String, Object> body = fullBody();
        body.put("loyaltyEarnAmount", 1);
        body.put("loyaltyPointValue", 0);
        body.put("expiryWarningDays", 365);
        save(owner, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loyaltyEarnAmount").value(1))
                .andExpect(jsonPath("$.loyaltyPointValue").value(0))
                .andExpect(jsonPath("$.expiryWarningDays").value(365));
        body.put("expiryWarningDays", 0);
        body.put("loyaltyEarnAmount", 9_007_199_254_740_991L);
        save(owner, body).andExpect(status().isOk()).andExpect(jsonPath("$.expiryWarningDays").value(0));
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() throws Exception {
        Owner owner = newStore();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", " ");
        body.put("phone", "abc");
        body.put("bankBin", "97043");
        body.put("loyaltyEarnAmount", 0);
        body.put("loyaltyPointValue", -5);
        body.put("expiryWarningDays", 400);
        String response = save(owner, body).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> fields = new ArrayList<>();
        json.readTree(response).get("errors").forEach(e -> fields.add(e.get("field").asString()));
        assertThat(fields).containsExactlyInAnyOrder("name", "phone", "bankBin", "bankAccountNo", "bankAccountName",
                "loyaltyEarnAmount", "loyaltyPointValue", "expiryWarningDays");
        assertUnchanged(owner);
    }

    // --- Phân quyền và tách Cửa hàng ------------------------------------------------------------------------------

    @Test
    void staffCanNeitherReadNorUpdate() throws Exception {
        Cookie staff = login("dev.nv.a");
        String before = storeRow(STORE_A).toString();
        long auditBefore = auditRows(STORE_A).size();

        mvc.perform(get(STORE).cookie(staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(put(STORE).cookie(staff).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(fullBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));

        assertThat(storeRow(STORE_A).toString()).isEqualTo(before);
        assertThat(auditRows(STORE_A)).hasSize((int) auditBefore);
    }

    @Test
    void anOwnerOnlyChangesTheirOwnStore() throws Exception {
        Owner a = newStore("Cửa hàng A riêng");
        Owner b = newStore("Cửa hàng B riêng");
        save(a, fullBody()).andExpect(status().isOk());

        mvc.perform(get(STORE).cookie(b.cookie()))
                .andExpect(jsonPath("$.name").value("Cửa hàng B riêng"))
                .andExpect(jsonPath("$.bankBin").value(nullValue()));
        assertThat(auditRows(b.storeId())).isEmpty();
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    static Map<String, Object> fullBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Tạp hóa Hoa");
        body.put("phone", "0912 345 678");
        body.put("address", "12 Lê Lợi, Quận 1");
        body.put("invoiceFooter", "Cảm ơn quý khách!");
        body.put("bankBin", "970436");
        body.put("bankAccountNo", "0011001234567");
        body.put("bankAccountName", "NGUYEN VAN A");
        body.put("loyaltyEarnAmount", 20000);
        body.put("loyaltyPointValue", 500);
        body.put("expiryWarningDays", 14);
        return body;
    }

    ResultActions save(Owner owner, Map<String, Object> body) throws Exception {
        return mvc.perform(put(STORE).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    /** {@code 400 /errors/common/validation} với lỗi trên đúng các trường {@code fields}. */
    void rejected(Owner owner, Map<String, Object> body, String... fields) throws Exception {
        String response = save(owner, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> reported = new ArrayList<>();
        json.readTree(response).get("errors").forEach(e -> {
            reported.add(e.get("field").asString());
            assertThat(e.get("message").asString()).isNotBlank();
        });
        assertThat(reported).as("body %s", body).containsExactlyInAnyOrder(fields);
    }

    /** Body đầy đủ, hợp lệ, chỉ đổi {@code field} thành {@code value}: phải lỗi trên đúng {@code expectedField}. */
    void rejectedWith(Owner owner, String field, Object value, String expectedField) throws Exception {
        Map<String, Object> body = fullBody();
        body.put(field, value);
        rejected(owner, body, expectedField);
    }

    /** Cửa hàng vẫn như lúc đăng ký và chưa có dòng Nhật ký nào. */
    void assertUnchanged(Owner owner) {
        assertThat(storeRow(owner.storeId()))
                .containsEntry("name", STORE_NAME)
                .containsEntry("phone", null)
                .containsEntry("bank_bin", null)
                .containsEntry("loyalty_earn_amount", 10000L)
                .containsEntry("expiry_warning_days", 30);
        assertThat(auditRows(owner.storeId())).isEmpty();
    }

    Map<String, Object> storeRow(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForMap("select * from app.store where id = ?", storeId));
    }

    List<Map<String, Object>> auditRows(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForList("""
                select action, entity, entity_id, actor_id, before::text as before, after::text as after, request_id
                from app.audit_log where entity = 'store' and entity_id = ?
                order by created_at, id
                """, storeId.toString()));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }

    Owner newStore() throws Exception {
        return newStore(STORE_NAME);
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore(String storeName) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", storeName);
        body.put("loginKey", "cuahang." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()),
                UUID.fromString(me.get("userId").asString()));
    }

    Cookie login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }
}
