package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;

/** Đăng ký Cửa hàng (FR-43, TK-00) trên PostgreSQL thật: ma trận I/O của spec và tiêu chí nghiệm thu. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StoreRegistrationIntegrationTest {

    static final String STORES = "/api/v1/platform/stores";
    static final String PASSWORD = "matkhau123";
    /** Số điện thoại không trùng giữa các test (login_key unique toàn hệ thống). */
    static final AtomicLong PHONE_SEQ = new AtomicLong(ThreadLocalRandom.current().nextLong(10_000_000L, 80_000_000L));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;

    /** userId của Chủ cửa hàng → storeId, ghi lại khi đăng ký trong test. */
    final Map<UUID, UUID> storesByUser = new ConcurrentHashMap<>();

    // --- Ma trận I/O ------------------------------------------------------------------------------------------

    @Test
    void registeringByPhoneCreatesTheStoreAndStartsASession() throws Exception {
        String key = newKey();
        String phone = uniquePhone();
        MvcResult result = register(key, body("Tạp hóa Hoa", phone, PASSWORD, "Hoa"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.storeName").value("Tạp hóa Hoa"))
                .andExpect(jsonPath("$.displayName").value("Hoa"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("access_token=").contains("HttpOnly").contains("SameSite=Strict");
        JsonNode me = json.readTree(result.getResponse().getContentAsString());
        UUID storeId = UUID.fromString(me.get("storeId").asString());
        UUID userId = UUID.fromString(me.get("userId").asString());
        assertThat(storeId).isEqualTo(storeIdFor(key));

        // Tiêu chí 1: /me bằng cookie vừa nhận trả đúng Me của Chủ cửa hàng mới
        Cookie cookie = result.getResponse().getCookie("access_token");
        String meBody = mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(meBody)).isEqualTo(me);

        Map<String, Object> user = asStore(storeId, () -> jdbc.queryForMap(
                "select login_key, phone, email, role, status, password_hash from app.app_user where id = ?", userId));
        assertThat(user).containsEntry("login_key", phone).containsEntry("phone", phone).containsEntry("email", null)
                .containsEntry("role", "OWNER").containsEntry("status", "ACTIVE");
        assertThat((String) user.get("password_hash")).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select name from app.store", String.class)))
                .isEqualTo("Tạp hóa Hoa");
        assertThat(asStore(storeId, () -> jdbc.queryForObject(
                "select next_value from app.order_counter where store_id = ?", Long.class, storeId))).isEqualTo(1L);

        // idempotency_record trỏ tới Chủ cửa hàng, không giữ mật khẩu
        Map<String, Object> record = asStore(storeId, () -> jdbc.queryForMap(
                "select request_hash, document_id from app.idempotency_record where idempotency_key = ?",
                UUID.fromString(key)));
        assertThat(record.get("document_id")).isEqualTo(userId);
        assertThat((String) record.get("request_hash")).matches("[0-9a-f]{64}").doesNotContain(PASSWORD);
    }

    @Test
    void emailIsTrimmedLowercasedAndStoredAsEmail() throws Exception {
        String suffix = Long.toString(PHONE_SEQ.incrementAndGet());
        UUID userId = registerOk(newKey(), body("Tạp hóa Hoa", " Hoa" + suffix + "@Mail.com ", PASSWORD, "Hoa"));
        String expected = "hoa" + suffix + "@mail.com";
        Map<String, Object> user = asStore(storeOf(userId), () -> jdbc.queryForMap(
                "select login_key, phone, email from app.app_user where id = ?", userId));
        assertThat(user).containsEntry("login_key", expected).containsEntry("email", expected)
                .containsEntry("phone", null);
    }

    @Test
    void internationalPhoneIsStoredWithLeadingZero() throws Exception {
        String phone = uniquePhone();
        UUID userId = registerOk(newKey(), body("Tạp hóa Hoa", "+84" + phone.substring(1), PASSWORD, "Hoa"));
        Map<String, Object> user = asStore(storeOf(userId), () -> jdbc.queryForMap(
                "select login_key, phone from app.app_user where id = ?", userId));
        assertThat(user).containsEntry("login_key", phone).containsEntry("phone", phone);
        // Đăng nhập được bằng cả hai cách viết
        login(phone, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId.toString()));
        login("+84" + phone.substring(1), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void takenLoginKeyIsRejectedAndNothingIsCreated() throws Exception {
        String phone = uniquePhone();
        registerOk(newKey(), body("Cửa hàng thứ nhất", phone, PASSWORD, "Hoa"));

        String key = newKey();
        register(key, body("Cửa hàng thứ hai", phone, "matkhau-khac", "Lan"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/login-taken"));
        assertNothingCreated(key);

        // Email chỉ khác hoa thường, khoảng trắng cũng là trùng
        String email = "trung" + PHONE_SEQ.incrementAndGet() + "@mail.com";
        registerOk(newKey(), body("Cửa hàng email", email, PASSWORD, "Trung"));
        String emailKey = newKey();
        register(emailKey, body("Cửa hàng khác", "  " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD, "Trung"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/login-taken"));
        assertNothingCreated(emailKey);

        // Khóa của lần bị từ chối không bị giữ: đổi số điện thoại rồi gửi lại cùng khóa thì tạo được
        register(key, body("Cửa hàng thứ hai", uniquePhone(), "matkhau-khac", "Lan")).andExpect(status().isCreated());
    }

    @Test
    void sameKeySameBodyReplaysTheSameStore() throws Exception {
        String key = newKey();
        String phone = uniquePhone();
        String body = body("Tạp hóa Hoa", phone, PASSWORD, "Hoa");
        JsonNode first = json.readTree(register(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        MvcResult replay = register(key, body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();
        assertThat(replay.getResponse().getCookie("access_token")).isNotNull();
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(first);

        // Cùng nội dung sau chuẩn hóa (+84, khoảng trắng) vẫn là phát lại
        register(key, body(" Tạp hóa Hoa ", "+84" + phone.substring(1), PASSWORD, "Hoa"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        UUID storeId = UUID.fromString(first.get("storeId").asString());
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.store", Long.class)))
                .isEqualTo(1L);
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.app_user", Long.class)))
                .isEqualTo(1L);
    }

    @Test
    void sameKeyWithDifferentBodyOrWrongPasswordConflicts() throws Exception {
        String key = newKey();
        String phone = uniquePhone();
        UUID userId = registerOk(key, body("Tạp hóa Hoa", phone, PASSWORD, "Hoa"));

        register(key, body("Tên khác", phone, PASSWORD, "Hoa"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));
        String otherPhone = uniquePhone();
        register(key, body("Tạp hóa Hoa", otherPhone, PASSWORD, "Hoa"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"));
        register(key, body("Tạp hóa Hoa", phone, "sai-mat-khau", "Hoa"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-reuse"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        UUID storeId = storeOf(userId);
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select name from app.store", String.class)))
                .isEqualTo("Tạp hóa Hoa");
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.app_user", Long.class)))
                .isEqualTo(1L);
        login(otherPhone, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void replayForALockedOwnerGivesTheLoginError() throws Exception {
        String key = newKey();
        String body = body("Tạp hóa Hoa", uniquePhone(), PASSWORD, "Hoa");
        UUID userId = registerOk(key, body);
        asStore(storeOf(userId), () -> jdbc.update("update app.app_user set status = 'LOCKED' where id = ?", userId));

        register(key, body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void invalidDataGivesFieldErrorsAndCreatesNothing() throws Exception {
        String key = newKey();
        register(key, body("", "abc", "short", "Hoa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("storeName", "loginKey", "password")));
        register(key, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("storeName", "loginKey", "password", "displayName")));
        for (String badKey : List.of("091234567", "09123456789", "+840912345678", "0912 345 678", "hoa@mail", "@mail.com")) {
            register(key, body("Tạp hóa Hoa", badKey, PASSWORD, "Hoa"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("loginKey")));
        }
        // Quá 72 ký tự, và 72 ký tự trở xuống nhưng quá 72 byte (giới hạn của bcrypt)
        register(key, body("Tạp hóa Hoa", uniquePhone(), "a".repeat(73), "Hoa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("password")));
        register(key, body("Tạp hóa Hoa", uniquePhone(), "ệ".repeat(30), "Hoa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("password")));
        // Toàn khoảng trắng Unicode là trống: U+3000 bị @NotBlank chặn; khoảng trắng không ngắt dòng (U+00A0,
        // U+202F) qua được @NotBlank và strip() nên service kiểm thêm
        register(key, body("\u3000\u3000", uniquePhone(), PASSWORD, "Hoa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("storeName")));
        register(key, body("\u00a0\u202f", uniquePhone(), PASSWORD, " \u00a0 "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("storeName", "displayName")));
        assertNothingCreated(key);

        // Đúng 8 và đúng 72 ký tự thì được
        registerOk(newKey(), body("Tạp hóa Hoa", uniquePhone(), "12345678", "Hoa"));
        registerOk(newKey(), body("Tạp hóa Hoa", uniquePhone(), "a".repeat(72), "Hoa"));
    }

    @Test
    void missingOrMalformedKeyGives422AndCreatesNothing() throws Exception {
        String phone = uniquePhone();
        register(null, body("Tạp hóa Hoa", phone, PASSWORD, "Hoa"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        register("khong-phai-uuid", body("Tạp hóa Hoa", phone, PASSWORD, "Hoa"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        // UUID.fromString nhận dạng rút gọn này (= 00000001-0001-0001-0001-000000000001); backend vẫn từ chối
        register("1-1-1-1-1", body("Tạp hóa Hoa", phone, PASSWORD, "Hoa"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/common/idempotency-key-missing"));
        assertNothingCreated(UUID.fromString("1-1-1-1-1").toString());
        login(phone, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentDuplicateRequestsCreateOneStore() throws Exception {
        String key = newKey();
        String body = body("Tạp hóa Hoa", uniquePhone(), PASSWORD, "Hoa");
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> send = () -> {
            start.await();
            return register(key, body).andReturn();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> a = pool.submit(send);
            Future<MvcResult> b = pool.submit(send);
            start.countDown();
            MvcResult ra = a.get(30, TimeUnit.SECONDS);
            MvcResult rb = b.get(30, TimeUnit.SECONDS);
            assertThat(List.of(ra.getResponse().getStatus(), rb.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 200);
            assertThat(json.readTree(ra.getResponse().getContentAsString()))
                    .isEqualTo(json.readTree(rb.getResponse().getContentAsString()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(asStore(storeIdFor(key), () -> jdbc.queryForObject("select count(*) from app.app_user", Long.class)))
                .isEqualTo(1L);
    }

    // --- Tiêu chí nghiệm thu ----------------------------------------------------------------------------------

    @Test
    void eachOwnerSeesOnlyTheirOwnUsers() throws Exception {
        UUID first = registerOk(newKey(), body("Cửa hàng một", uniquePhone(), PASSWORD, "Một"));
        UUID second = registerOk(newKey(), body("Cửa hàng hai", uniquePhone(), PASSWORD, "Hai"));
        UUID firstStore = storeOf(first);
        UUID secondStore = storeOf(second);

        List<UUID> seenByFirst = TenantScope.runAs(firstStore, first, Role.OWNER, () -> inTx(() ->
                jdbc.queryForList("select id from app.app_user", UUID.class)));
        List<UUID> seenBySecond = TenantScope.runAs(secondStore, second, Role.OWNER, () -> inTx(() ->
                jdbc.queryForList("select id from app.app_user", UUID.class)));
        assertThat(seenByFirst).containsExactly(first);
        assertThat(seenBySecond).containsExactly(second);
    }

    @Test
    void ownerCanLogInAgainWithDifferentlyCasedLoginKey() throws Exception {
        String email = "lan." + PHONE_SEQ.incrementAndGet() + "@mail.com";
        UUID userId = registerOk(newKey(), body("Tạp hóa Lan", "Lan" + email.substring(3), PASSWORD, "Lan"));
        login("  " + email.toUpperCase(Locale.ROOT) + " ", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.storeName").value("Tạp hóa Lan"));
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    static String uniquePhone() {
        return "09" + PHONE_SEQ.incrementAndGet();
    }

    /** Cùng công thức với {@code StoreRegistrationService.storeIdFor}. */
    static UUID storeIdFor(String key) {
        return UUID.nameUUIDFromBytes(("store-registration:" + key).getBytes(StandardCharsets.UTF_8));
    }

    String body(String storeName, String loginKey, String password, String displayName) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", storeName);
        body.put("loginKey", loginKey);
        body.put("password", password);
        body.put("displayName", displayName);
        return json.writeValueAsString(body);
    }

    ResultActions register(String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(STORES).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** Đăng ký thành công, trả {@code userId} của Chủ cửa hàng. */
    UUID registerOk(String key, String body) throws Exception {
        String response = register(key, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        UUID userId = UUID.fromString(json.readTree(response).get("userId").asString());
        storesByUser.put(userId, UUID.fromString(json.readTree(response).get("storeId").asString()));
        return userId;
    }

    UUID storeOf(UUID userId) {
        return storesByUser.get(userId);
    }

    ResultActions login(String loginKey, String password) throws Exception {
        Map<String, String> body = Map.of("loginKey", loginKey, "password", password);
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    void assertNothingCreated(String key) {
        UUID storeId = storeIdFor(key);
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.store", Long.class))).isZero();
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.order_counter", Long.class)))
                .isZero();
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.app_user", Long.class)))
                .isZero();
        assertThat(asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.idempotency_record",
                Long.class))).isZero();
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> inTx(work));
    }

    <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> work.get());
    }
}
