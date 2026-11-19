package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;

/**
 * Kiểm thử tích hợp cho Nhật ký thao tác (FR-49, TK-06).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditLogIntegrationTest {

    static final String AUDIT_LOG = "/api/v1/platform/audit-log";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate transactions;

    record Owner(Cookie cookie, UUID storeId, UUID userId) {}

    @Test
    void auditLogReturnsPagedEntriesWithActorNameAndFilter() throws Exception {
        Owner owner = newStore();

        // 1. Tạo 1 nhân viên để sinh audit log (platform.user.create)
        mvc.perform(post("/api/v1/platform/users")
                .cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "loginKey", "nv.kiemthu." + UUID.randomUUID().toString().substring(0, 8),
                        "displayName", "Nhân viên kiểm thử",
                        "password", "matkhau123"
                ))))
                .andExpect(status().isCreated());

        // 2. Gọi GET /api/v1/platform/audit-log
        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].action").value("platform.user.create"))
                .andExpect(jsonPath("$.items[0].actorId").value(owner.userId().toString()))
                .andExpect(jsonPath("$.items[0].actorName").value("Chủ cửa hàng"))
                .andExpect(jsonPath("$.items[0].entity").value("app_user"))
                .andExpect(jsonPath("$.items[0].createdAt").exists());

        // 3. Lọc theo action
        mvc.perform(get(AUDIT_LOG)
                .param("action", "platform.user.create")
                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].action").value("platform.user.create"));

        // Lọc theo action không có
        mvc.perform(get(AUDIT_LOG)
                .param("action", "sales.order.cancel")
                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        // 4. Lọc theo actorId
        mvc.perform(get(AUDIT_LOG)
                .param("actorId", owner.userId().toString())
                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty());

        // Lọc theo actorId khác
        mvc.perform(get(AUDIT_LOG)
                .param("actorId", UUID.randomUUID().toString())
                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        // 5. Lọc theo khoảng ngày hợp lệ
        LocalDate today = LocalDate.now(TimeZones.VIETNAM);
        mvc.perform(get(AUDIT_LOG)
                .param("fromDate", today.minusDays(1).toString())
                .param("toDate", today.plusDays(1).toString())
                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty());

        // 6. Khoảng ngày ngược nhau trả 400
        mvc.perform(get(AUDIT_LOG)
                .param("fromDate", today.plusDays(5).toString())
                .param("toDate", today.toString())
                .cookie(owner.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
    }

    @Test
    void staffCannotAccessAuditLog() throws Exception {
        Owner owner = newStore();
        String nvLogin = "nv.test." + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/platform/users")
                .cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "loginKey", nvLogin,
                        "displayName", "Nhân viên",
                        "password", "matkhau123"
                ))))
                .andExpect(status().isCreated());

        // Đăng nhập nhân viên
        var loginRes = mvc.perform(post("/api/v1/platform/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginKey", nvLogin, "password", "matkhau123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        Cookie staffCookie = loginRes.getCookie("access_token");
        assertThat(staffCookie).isNotNull();

        // Nhân viên gọi audit log bị 403
        mvc.perform(get(AUDIT_LOG).cookie(staffCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsEveryKnownActionAndRejectsInvalidFilters() throws Exception {
        Owner owner = newStore();
        var response = mvc.perform(get(AUDIT_LOG + "/actions").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(AuditAction.values().length)))
                .andReturn().getResponse();
        var labels = new java.util.HashMap<String, String>();
        json.readTree(response.getContentAsByteArray()).forEach(option ->
                labels.put(option.get("action").asString(), option.get("label").asString()));
        for (AuditAction action : AuditAction.values()) {
            assertThat(labels.get(action.code())).isEqualTo(action.label()).isNotBlank();
        }
        assertThat(labels.get("platform.user.lock")).isEqualTo("Khóa tài khoản Nhân viên");

        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("action", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("action"));
        LocalDate start = LocalDate.of(2024, 1, 1);
        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("fromDate", start.toString())
                        .param("toDate", start.plusDays(365).toString()))
                .andExpect(status().isOk());
        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("fromDate", start.toString())
                        .param("toDate", start.plusDays(366).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toDate"));
    }

    @Test
    void pagesOnlyThisStoresEntriesWithinVietnamCalendarDates() throws Exception {
        Owner owner = newStore();
        Owner other = newStore();
        String price = "catalog.variant.price_change";
        seed(owner, owner.userId(), price, "2026-11-15T23:30:00+07:00");
        seed(owner, null, "legacy.action", "2026-11-01T00:00:00+07:00");
        seed(owner, UUID.randomUUID(), price, "2026-11-16T00:10:00+07:00");
        seed(other, other.userId(), price, "2026-11-15T23:45:00+07:00");

        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("fromDate", "2026-11-01")
                        .param("toDate", "2026-11-15").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].actionLabel").value("Sửa giá bán"))
                .andExpect(jsonPath("$.items[0].actorName").value("Chủ cửa hàng"))
                .andExpect(jsonPath("$.items[0].before.salePrice").value(15000))
                .andExpect(jsonPath("$.items[0].after.salePrice").value(16000))
                .andExpect(jsonPath("$.items[0].requestId").value("audit-test"));
        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("fromDate", "2026-11-01")
                        .param("toDate", "2026-11-15").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actionLabel").value("legacy.action"))
                .andExpect(jsonPath("$.items[0].actorName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(AUDIT_LOG).cookie(owner.cookie()).param("action", price))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].actorId").isString())
                .andExpect(jsonPath("$.items[0].actorName").value(org.hamcrest.Matchers.nullValue()));
    }

    private void seed(Owner owner, UUID actor, String action, String createdAt) {
        TenantScope.runAs(owner.storeId(), () -> transactions.executeWithoutResult(tx -> jdbc.update("""
                INSERT INTO app.audit_log (store_id, actor_id, action, entity, entity_id,
                                           before, after, request_id, created_at)
                VALUES (?, ?, ?, 'product_variant', 'variant-1', '{"salePrice":15000}',
                        '{"salePrice":16000}', 'audit-test', ?::timestamptz)
                """, owner.storeId(), actor, action, createdAt)));
    }

    private Owner newStore() throws Exception {
        String phone = "09" + (long) (Math.random() * 90000000L + 10000000L);
        var res = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "storeName", "Cửa hàng Audit " + phone,
                        "displayName", "Chủ cửa hàng",
                        "loginKey", phone,
                        "password", "matkhau123"
                ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        Cookie cookie = res.getCookie("access_token");
        var node = json.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(cookie, UUID.fromString(node.get("storeId").asString()),
                UUID.fromString(node.get("userId").asString()));
    }
}
