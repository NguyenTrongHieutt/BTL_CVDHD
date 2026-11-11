package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.internal.user.AppUserEntity;
import vn.nhom17.banhang.platform.internal.user.AppUserRepository;

/** Khung platform trên PostgreSQL 18 thật: Flyway, đăng nhập, phân quyền, RLS, @TenantId, mã đơn, chống trùng, nhật ký. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlatformIntegrationTest {

    static final UUID STORE_A = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("d0000000-0000-4000-8000-00000000000b");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired Flyway flyway;
    @Autowired OrderCodes orderCodes;
    @Autowired IdempotencyService idempotency;
    @Autowired AuditLog auditLog;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;

    <T> T inTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> work.get());
    }

    // --- Spike 2: Flyway 12.4 trên PostgreSQL 18 ---------------------------------------------------------------

    @Test
    void flywayAppliesEveryMigrationOnPostgres18() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(5);
        Integer version = jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class);
        assertThat(version).isGreaterThanOrEqualTo(180000);
    }

    // --- Đăng nhập và phiên (AD-9) ----------------------------------------------------------------------------

    @Test
    void loginSetsHttpOnlyCookieAndMeReturnsTheSession() throws Exception {
        Cookie cookie = login("dev.chu.a");
        mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.storeId").value(STORE_A.toString()))
                .andExpect(jsonPath("$.storeName").value("Cửa hàng thử A (dev)"))
                .andExpect(jsonPath("$.displayName").value("Chủ cửa hàng A"));
    }

    // --- Spike 3: springdoc 3.2.0 + Spring Modulith 2.1.2 trên Spring Boot 4.1.2 -------------------------------

    @Test
    void openApiIsGeneratedBySpringdoc() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/platform/auth/login']").exists());
    }

    @Test
    void wrongPasswordGivesOneGenericProblem() throws Exception {
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"dev.chu.a\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"khong.ton.tai\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"));
    }

    @Test
    void lockedAccountGetsTheSameGenericProblemAsAWrongPassword() throws Exception {
        String loginKey = "nv.khoa." + UUID.randomUUID();
        Integer inserted = TenantScope.runAs(STORE_A, () -> inTx(() -> jdbc.update("""
                insert into app.app_user (store_id, login_key, display_name, password_hash, role, status)
                values (?, ?, 'Nhân viên bị khóa', ?, 'STAFF', 'LOCKED')
                """, STORE_A, loginKey, passwords.encode("demo1234"))));
        assertThat(inserted).isEqualTo(1);

        MvcResult locked = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        MvcResult wrongPassword = mvc.perform(post("/api/v1/platform/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"dev.chu.a\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        // Cùng một Problem (trừ requestId): không lộ tài khoản có tồn tại, có bị khóa hay không (FR-44)
        for (String field : new String[] {"type", "title", "status", "detail"}) {
            assertThat(com.jayway.jsonpath.JsonPath.<Object>read(locked.getResponse().getContentAsString(), "$." + field))
                    .isEqualTo(com.jayway.jsonpath.JsonPath.<Object>read(
                            wrongPassword.getResponse().getContentAsString(), "$." + field));
        }
        assertThat(locked.getResponse().getContentType()).isEqualTo(wrongPassword.getResponse().getContentType());

        // Đối chứng: cùng thông tin, mở khóa thì đăng nhập được, nên 401 ở trên là do status = LOCKED
        TenantScope.runAs(STORE_A, () -> inTx(() -> jdbc.update(
                "update app.app_user set status = 'ACTIVE' where login_key = ?", loginKey)));
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void loginKeyIsTrimmedAndCaseInsensitive() throws Exception {
        Cookie cookie = login("  DEV.Chu.A ");
        mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(STORE_A.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.displayName").value("Chủ cửa hàng A"));
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        mvc.perform(post("/api/v1/platform/auth/logout").cookie(login("dev.chu.a")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("access_token="),
                                org.hamcrest.Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void missingFieldsGiveValidationProblemWithErrors() throws Exception {
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void anonymousRequestGets401Problem() throws Exception {
        mvc.perform(get("/api/v1/platform/me").header("X-Request-Id", "test-req-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "test-req-1"))
                .andExpect(jsonPath("$.type").value("/errors/common/unauthorized"))
                .andExpect(jsonPath("$.requestId").value("test-req-1"));
    }

    // --- Phân quyền mặc định cấm (AD-16) ----------------------------------------------------------------------

    @Test
    void staffCannotOpenOwnerRoutesAndUndeclaredRoutesAreDenied() throws Exception {
        Cookie staff = login("dev.nv.a");
        mvc.perform(get("/api/v1/reporting/q/Q1").cookie(staff)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(get("/api/v1/platform/users").cookie(staff)).andExpect(status().isForbidden());
        Cookie owner = login("dev.chu.a");
        mvc.perform(get("/api/v1/khong-khai-bao").cookie(owner)).andExpect(status().isForbidden());
    }

    @Test
    void internalRoutesNeedTheServiceKey() throws Exception {
        mvc.perform(get("/internal/catalog/product-changes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/catalog/product-changes")
                .header("X-Service-Key", TestcontainersConfiguration.SERVICE_KEY)).andExpect(status().isOk());
    }

    // --- Tách dữ liệu hai lớp (AD-5) --------------------------------------------------------------------------

    @Test
    void rlsShowsOnlyTheCurrentStore() {
        Long ownRows = TenantScope.runAs(STORE_A, () -> inTx(() ->
                jdbc.queryForObject("select count(*) from app.app_user", Long.class)));
        Long otherRows = TenantScope.runAs(STORE_A, () -> inTx(() ->
                jdbc.queryForObject("select count(*) from app.app_user where store_id = ?", Long.class, STORE_B)));
        Long noContext = inTx(() -> jdbc.queryForObject("select count(*) from app.store", Long.class));
        assertThat(ownRows).isGreaterThanOrEqualTo(2);
        assertThat(otherRows).isZero();
        assertThat(noContext).isZero();
    }

    @Test
    void writingARowOfAnotherStoreIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> TenantScope.runAs(STORE_A, () -> inTx(() -> jdbc.update(
                "insert into app.category (store_id, name) values (?, 'Lén ghi')", STORE_B))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void tenantIdFillsStoreIdAndFiltersReads() {
        UUID id = UUID.randomUUID();
        TenantScope.runAs(STORE_B, () -> inTx(() -> users.save(new AppUserEntity(id, "tenant.test." + id, "Thử",
                "{noop}x", "STAFF", java.time.Instant.parse("2026-11-06T00:00:00Z")))));
        UUID stored = TenantScope.runAs(STORE_B, () -> inTx(() ->
                jdbc.queryForObject("select store_id from app.app_user where id = ?", UUID.class, id)));
        assertThat(stored).isEqualTo(STORE_B);
        assertThat(TenantScope.runAs(STORE_A, () -> inTx(() -> users.findById(id)))).isEmpty();
    }

    // --- Mã Đơn hàng (AD-8) -----------------------------------------------------------------------------------

    @Test
    void orderCodesAreSequentialPerStore() {
        String first = TenantScope.runAs(STORE_A, () -> inTx(orderCodes::nextOrderCode));
        String second = TenantScope.runAs(STORE_A, () -> inTx(orderCodes::nextOrderCode));
        assertThat(first).matches("DH-\\d{6}");
        assertThat(Integer.parseInt(second.substring(3))).isEqualTo(Integer.parseInt(first.substring(3)) + 1);
    }

    // --- Chống tạo trùng (AD-7) -------------------------------------------------------------------------------

    @Test
    void sameKeySameContentReplaysAndDifferentContentConflicts() {
        UUID key = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var first = TenantScope.runAs(STORE_A, () -> idempotency.execute("TEST_DOC", key, "hash-1", () -> documentId, d -> d));
        var replay = TenantScope.runAs(STORE_A, () -> idempotency.execute("TEST_DOC", key, "hash-1", UUID::randomUUID, d -> d));
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(documentId);
        assertThatThrownBy(() -> TenantScope.runAs(STORE_A, () ->
                idempotency.execute("TEST_DOC", key, "hash-khac", UUID::randomUUID, d -> d)))
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).type()).isEqualTo("/errors/common/idempotency-key-reuse");
    }

    @Test
    void failedCreateReleasesTheKey() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> TenantScope.runAs(STORE_A, () -> idempotency.execute("TEST_DOC", key, "h",
                () -> { throw new IllegalStateException("tạo chứng từ lỗi"); }, d -> d)))
                .isInstanceOf(IllegalStateException.class);
        var retry = TenantScope.runAs(STORE_A, () -> idempotency.execute("TEST_DOC", key, "h", UUID::randomUUID, d -> d));
        assertThat(retry.replayed()).isFalse();
    }

    // --- Nhật ký thao tác ------------------------------------------------------------------------------------

    @Test
    void auditLogIsWrittenInsideTheTransactionAndCannotBeEdited() {
        TenantScope.runAs(STORE_A, () -> inTx(() -> {
            auditLog.record(AuditAction.PLATFORM_STORE_UPDATE, STORE_A.toString(), Map.of("name", "cũ"), Map.of("name", "mới"));
            return null;
        }));
        assertThatThrownBy(() -> TenantScope.runAs(STORE_A, () -> inTx(() ->
                jdbc.update("update app.audit_log set action = 'sua-trom'"))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantScope.runAs(STORE_A, () -> {
            auditLog.record(AuditAction.PLATFORM_STORE_UPDATE, "x", null, null);
            return null;
        })).isInstanceOf(IllegalTransactionStateException.class);
    }

    private Cookie login(String loginKey) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("access_token=").contains("HttpOnly").contains("SameSite=Strict");
        return result.getResponse().getCookie("access_token");
    }
}
