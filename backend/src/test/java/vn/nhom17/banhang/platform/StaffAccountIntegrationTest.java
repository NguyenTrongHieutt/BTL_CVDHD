package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;

/**
 * Tài khoản Nhân viên (FR-45, TK-02) trên PostgreSQL thật: toàn bộ ma trận I/O của spec, gồm cả "đang dùng thì bị
 * khóa → {@code 401 account-locked}" (việc hoãn lại từ TK-01). Mỗi test dùng Cửa hàng mới đăng ký riêng, trừ test
 * Nhân viên gọi API dùng {@code dev.nv.a} (không khóa, không đổi mật khẩu tài khoản dev).
 *
 * <p>Khóa, mở khóa qua API bỏ trạng thái đã cache ({@code AccountStatusCache}) nên có hiệu lực ngay. Thay đổi không
 * đi qua API (UPDATE thẳng) chỉ tới phiên đang mở khi cache 60 giây hết hạn: {@link TestClock} thay {@code Clock} của
 * ứng dụng, tiến giờ 61 giây là cache hết hạn mà không phải chờ. Token kiểm theo cùng {@code Clock}, nên chỉ tiến
 * (token cấp trước đó vẫn còn hạn 8 giờ).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, StaffAccountIntegrationTest.ClockConfig.class})
class StaffAccountIntegrationTest {

    static final String USERS = "/api/v1/platform/users";
    static final String OWNER_PASSWORD = "matkhau123";
    static final UUID STORE_A = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    /** Mọi trường của một dòng trong danh sách; không có {@code passwordHash}. */
    static final Set<String> USER_FIELDS =
            Set.of("userId", "loginKey", "displayName", "role", "status", "phone", "createdAt");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired TestClock clock;

    record Owner(Cookie cookie, UUID storeId, UUID userId, String loginKey) {
    }

    @BeforeEach
    @AfterEach
    void systemTime() {
        clock.useSystemTime();
    }

    // --- Tạo -----------------------------------------------------------------------------------------------------

    @Test
    void createdStaffLogsInWithThatLoginKeyAsStaff() throws Exception {
        Owner owner = newStore();
        String response = create(owner, body("Minh.Lan", "Minh", "matkhau1", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginKey").value("minh.lan"))
                .andExpect(jsonPath("$.displayName").value("Minh"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID staffId = UUID.fromString(json.readTree(response).get("userId").asString());

        Map<String, Object> row = asStore(owner.storeId(), () -> jdbc.queryForMap(
                "select store_id, login_key, role, status, password_hash from app.app_user where id = ?", staffId));
        assertThat(row).containsEntry("store_id", owner.storeId()).containsEntry("login_key", "minh.lan")
                .containsEntry("role", "STAFF").containsEntry("status", "ACTIVE");
        assertThat((String) row.get("password_hash")).startsWith("{bcrypt}").doesNotContain("matkhau1");

        // Nhật ký: một dòng, không có mật khẩu
        List<Map<String, Object>> audit = auditRows(owner.storeId());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst())
                .containsEntry("action", "platform.user.create")
                .containsEntry("entity", "app_user")
                .containsEntry("entity_id", staffId.toString())
                .containsEntry("actor_id", owner.userId())
                .containsEntry("before", null);
        assertThat(audit.getFirst().get("request_id")).isNotNull();
        assertThat(json.readTree((String) audit.getFirst().get("after"))).isEqualTo(json.readTree("""
                {"loginKey": "minh.lan", "displayName": "Minh", "role": "STAFF", "status": "ACTIVE"}
                """));
        assertNoPasswordInAudit(owner.storeId(), "matkhau1");

        // Nhân viên đăng nhập bằng tên đó (không phân biệt hoa thường), vào đúng Cửa hàng, vai trò Nhân viên
        Cookie staff = login(" MINH.LAN ", "matkhau1");
        mvc.perform(get("/api/v1/platform/me").cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staffId.toString()))
                .andExpect(jsonPath("$.storeId").value(owner.storeId().toString()))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.displayName").value("Minh"));

        // Danh sách: Chủ cửa hàng trước, rồi Nhân viên; đúng các trường, không có mật khẩu
        String list = mvc.perform(get(USERS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].userId").value(owner.userId().toString()))
                .andExpect(jsonPath("$.items[0].role").value("OWNER"))
                .andExpect(jsonPath("$.items[0].loginKey").value(owner.loginKey()))
                .andExpect(jsonPath("$.items[1].userId").value(staffId.toString()))
                .andExpect(jsonPath("$.items[1].loginKey").value("minh.lan"))
                .andExpect(jsonPath("$.items[1].role").value("STAFF"))
                .andExpect(jsonPath("$.items[1].status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode item : json.readTree(list).get("items")) {
            assertThat(fieldNames(item)).isEqualTo(USER_FIELDS);
        }
        assertThat(list).doesNotContain("bcrypt").doesNotContain("password");
    }

    @Test
    void newestStaffComeRightAfterTheOwner() throws Exception {
        Owner owner = newStore();
        UUID first = createStaff(owner, uniqueKey(), "Nhân viên 1", "matkhau1");
        UUID second = createStaff(owner, uniqueKey(), "Nhân viên 2", "matkhau1");
        mvc.perform(get(USERS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].role").value("OWNER"))
                .andExpect(jsonPath("$.items[1].userId").value(second.toString()))
                .andExpect(jsonPath("$.items[2].userId").value(first.toString()));
        // Phân trang (AD-19)
        mvc.perform(get(USERS).param("page", "1").param("size", "2").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].userId").value(first.toString()));
    }

    @Test
    void displayNameLoginKeyAndPhoneAreNormalized() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        create(owner, body("  " + key.toUpperCase() + "  ", "  Lan Anh​ ", "matkhau1", " 0912 345 678 "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginKey").value(key))
                .andExpect(jsonPath("$.displayName").value("Lan Anh"))
                .andExpect(jsonPath("$.phone").value("0912 345 678"));
        login(key, "matkhau1");
    }

    // --- Trùng tên đăng nhập (AD-9: unique toàn hệ thống) ----------------------------------------------------------

    @Test
    void aLoginKeyUsedInAnyStoreIsTaken() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        String key = uniqueKey();
        createStaff(a, key, "Nhân viên A", "matkhau1");

        // Cửa hàng khác, khác hoa thường
        create(b, body(key.toUpperCase(), "Nhân viên B", "matkhau2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/login-taken"))
                .andExpect(jsonPath("$.requestId").exists());
        // Cùng Cửa hàng
        create(a, body(key, "Nhân viên A2", "matkhau2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/login-taken"));
        // Tài khoản có sẵn của Cửa hàng khác (dev)
        create(b, body("dev.nv.a", "Nhân viên", "matkhau2", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/login-taken"));

        // Không tạo gì, không ghi nhật ký ở B; A chỉ có một dòng tạo
        assertThat(userCount(b.storeId())).isEqualTo(1);
        assertThat(auditRows(b.storeId())).isEmpty();
        assertThat(auditRows(a.storeId())).hasSize(1);
        // Tài khoản đã có vẫn giữ mật khẩu cũ
        login(key, "matkhau1");
    }

    // --- Dữ liệu sai --------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+84912345678", " 0912345678 ", "a@b.vn", "minh@cuahang"})
    void phoneOrEmailShapedLoginKeysAreRejected(String loginKey) throws Exception {
        Owner owner = newStore();
        create(owner, body(loginKey, "Minh", "matkhau1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("loginKey"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("số điện thoại hay email")));
        assertThat(userCount(owner.storeId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "_minh", ".minh", "-minh", "minh lan", "Lân", "minh/lan", "minh+lan",
            "a123456789a123456789a123456789a123456789a123456789x"})
    void loginKeysOutsideTheAllowedShapeAreRejected(String loginKey) throws Exception {
        Owner owner = newStore();
        create(owner, body(loginKey, "Minh", "matkhau1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("loginKey"));
    }

    @Test
    void loginKeyOfExactly3And50CharactersIsAccepted() throws Exception {
        Owner owner = newStore();
        // Chữ số thập lục phân: luôn bắt đầu bằng chữ cái hoặc chữ số; chưa ai dùng tên 3 hay 50 ký tự trong container này
        String hex = UUID.randomUUID().toString().replace("-", "");
        String fifty = ("n" + hex + hex).substring(0, 50);
        create(owner, body(fifty, "Năm mươi", "matkhau1", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginKey").value(fifty));
        String three = hex.substring(0, 3);
        create(owner, body(three.toUpperCase(), "Ba", "matkhau1", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginKey").value(three));
        login(fifty, "matkhau1");
        login(three, "matkhau1");
    }

    @Test
    void everyInvalidFieldIsReportedAtOnceAndNothingIsCreated() throws Exception {
        Owner owner = newStore();
        create(owner, body("ab", "   ", "1234567", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].field").value("loginKey"))
                .andExpect(jsonPath("$.errors[1].field").value("displayName"))
                .andExpect(jsonPath("$.errors[1].message").value("Nhập tên Nhân viên"))
                .andExpect(jsonPath("$.errors[2].field").value("password"))
                .andExpect(jsonPath("$.errors[2].message").value("Mật khẩu từ 8 đến 72 ký tự"))
                .andExpect(jsonPath("$.errors[3].field").value("phone"));
        // Thiếu hết
        mvc.perform(post(USERS).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].message").value("Nhập tên đăng nhập"))
                .andExpect(jsonPath("$.errors[2].message").value("Nhập mật khẩu"));
        assertThat(userCount(owner.storeId())).isEqualTo(1);
        assertThat(auditRows(owner.storeId())).isEmpty();
    }

    @Test
    void displayNameAndPasswordLimits() throws Exception {
        Owner owner = newStore();
        // Tên 101 ký tự; tên có ký tự điều khiển
        create(owner, body(uniqueKey(), "a".repeat(101), "matkhau1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].message").value("Tên Nhân viên tối đa 100 ký tự"));
        create(owner, body(uniqueKey(), "Minh\u0007", "matkhau1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        // Mật khẩu 73 ký tự; 37 ký tự nhưng 74 byte (bcrypt chỉ nhận 72 byte)
        create(owner, body(uniqueKey(), "Minh", "a".repeat(73), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        create(owner, body(uniqueKey(), "Minh", "ă".repeat(37), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu quá dài, hãy dùng mật khẩu ngắn hơn"));
        // Chỉ khoảng trắng: đăng nhập (@NotBlank) sẽ không bao giờ nhận, nên là thiếu
        create(owner, body(uniqueKey(), "Minh", " ".repeat(8), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Nhập mật khẩu"));
        // Surrogate lẻ (JSON escape của U+D800, như trình duyệt gửi): UTF-8 sẽ đổi thành "?"
        mvc.perform(post(USERS).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + uniqueKey()
                                + "\",\"displayName\":\"Minh\",\"password\":\"matkhau\\ud800x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu có ký tự không hợp lệ"));
        // Số điện thoại quá dài, không có chữ số
        create(owner, body(uniqueKey(), "Minh", "matkhau1", "0".repeat(21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        create(owner, body(uniqueKey(), "Minh", "matkhau1", "+ . -"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        // Đúng giới hạn: tên 100 ký tự, mật khẩu 72 byte
        String key = uniqueKey();
        create(owner, body(key, "a".repeat(100), "ă".repeat(36), null)).andExpect(status().isCreated());
        login(key, "ă".repeat(36));
        assertThat(userCount(owner.storeId())).isEqualTo(2);
    }

    // --- Khóa: không đăng nhập được, phiên đang mở bị chặn ngay (AD-9) ------------------------------------------------

    @Test
    void lockingBlocksLoginAndTheOpenSessionRightAway() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        UUID staffId = createStaff(owner, key, "Nhân viên", "matkhau1");
        Cookie session = login(key, "matkhau1");
        mvc.perform(get("/api/v1/platform/me").cookie(session)).andExpect(status().isOk());   // cache: đang hoạt động

        mvc.perform(post(USERS + "/" + staffId + "/lock").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staffId.toString()))
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.role").value("STAFF"));
        assertThat(statusOf(owner.storeId(), staffId)).isEqualTo("LOCKED");

        // Đăng nhập mới: lỗi chung, không lộ là bị khóa (FR-44)
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", key, "password", "matkhau1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        // Phiên đang mở: request kế tiếp bị chặn (khóa đã bỏ trạng thái "đang hoạt động" đã cache), giờ không đổi
        MockHttpServletResponse blocked = mvc.perform(get("/api/v1/platform/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/account-locked"))
                .andReturn().getResponse();
        assertThat(blocked.getHeader("Set-Cookie")).contains("access_token=").contains("Max-Age=0");
        // Mọi API khác cũng bị chặn, không chỉ /me
        mvc.perform(get("/api/v1/catalog/products").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/account-locked"));

        // Khóa lại tài khoản đã khóa: 200, không ghi nhật ký lần hai
        mvc.perform(post(USERS + "/" + staffId + "/lock").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
        List<Map<String, Object>> locks = auditRows(owner.storeId(), "platform.user.lock");
        assertThat(locks).hasSize(1);
        assertThat(locks.getFirst()).containsEntry("entity_id", staffId.toString())
                .containsEntry("actor_id", owner.userId());
        assertThat(json.readTree((String) locks.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"status\": \"ACTIVE\"}"));
        assertThat(json.readTree((String) locks.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"status\": \"LOCKED\"}"));

        // Danh sách hiện trạng thái mới
        mvc.perform(get(USERS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].status").value("LOCKED"));
    }

    @Test
    void unlockingLetsTheStaffLogInAgainRightAway() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        UUID staffId = createStaff(owner, key, "Nhân viên", "matkhau1");
        Cookie session = login(key, "matkhau1");
        mvc.perform(get("/api/v1/platform/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(post(USERS + "/" + staffId + "/lock").cookie(owner.cookie())).andExpect(status().isOk());
        // Phiên cũ bị chặn; filter cache "đã khóa"
        mvc.perform(get("/api/v1/platform/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/account-locked"));

        mvc.perform(post(USERS + "/" + staffId + "/unlock").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(statusOf(owner.storeId(), staffId)).isEqualTo("ACTIVE");

        // Đăng nhập lại được, và dùng được ngay: mở khóa bỏ "đã khóa" đã cache, không phải chờ 60 giây
        Cookie again = login(key, "matkhau1");
        mvc.perform(get("/api/v1/platform/me").cookie(again))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staffId.toString()));

        // Mở khóa tài khoản đang hoạt động: 200, không ghi nhật ký lần hai
        mvc.perform(post(USERS + "/" + staffId + "/unlock").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        List<Map<String, Object>> unlocks = auditRows(owner.storeId(), "platform.user.unlock");
        assertThat(unlocks).hasSize(1);
        assertThat(unlocks.getFirst()).containsEntry("entity", "app_user")
                .containsEntry("entity_id", staffId.toString());
        assertThat(json.readTree((String) unlocks.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"status\": \"LOCKED\"}"));
        assertThat(json.readTree((String) unlocks.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"status\": \"ACTIVE\"}"));
        assertThat(auditRows(owner.storeId(), "platform.user.lock")).hasSize(1);
    }

    /**
     * Thay đổi không đi qua API (UPDATE thẳng, hoặc khóa ở máy chủ khác) không bỏ cache: phiên đang mở thấy trạng thái
     * mới khi cache 60 giây hết hạn. Cả "đang hoạt động" lẫn "đã khóa" đều được cache (việc hoãn lại từ TK-01).
     */
    @Test
    void aStatusChangedOutsideTheApiReachesOpenSessionsWhenTheCacheExpires() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        UUID staffId = createStaff(owner, key, "Nhân viên", "matkhau1");
        Cookie session = login(key, "matkhau1");
        mvc.perform(get("/api/v1/platform/me").cookie(session)).andExpect(status().isOk());   // cache: đang hoạt động

        setStatus(owner.storeId(), staffId, "LOCKED");
        mvc.perform(get("/api/v1/platform/me").cookie(session)).andExpect(status().isOk());   // còn trong cache
        clock.freezeAt(Duration.ofSeconds(61));
        MockHttpServletResponse blocked = mvc.perform(get("/api/v1/platform/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/account-locked"))
                .andReturn().getResponse();
        assertThat(blocked.getHeader("Set-Cookie")).contains("access_token=").contains("Max-Age=0");

        setStatus(owner.storeId(), staffId, "ACTIVE");
        mvc.perform(get("/api/v1/platform/me").cookie(session))
                .andExpect(status().isUnauthorized());   // "đã khóa" còn trong cache
        clock.freezeAt(Duration.ofSeconds(122));
        mvc.perform(get("/api/v1/platform/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void concurrentLocksWriteOneAuditRow() throws Exception {
        Owner owner = newStore();
        UUID staffId = createStaff(owner, uniqueKey(), "Nhân viên", "matkhau1");
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post(USERS + "/" + staffId + "/lock").cookie(owner.cookie()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> locks = auditRows(owner.storeId(), "platform.user.lock");
        assertThat(locks).hasSize(1);
        assertThat(json.readTree((String) locks.getFirst().get("before")))
                .isEqualTo(json.readTree("{\"status\": \"ACTIVE\"}"));
        assertThat(json.readTree((String) locks.getFirst().get("after")))
                .isEqualTo(json.readTree("{\"status\": \"LOCKED\"}"));
        assertThat(statusOf(owner.storeId(), staffId)).isEqualTo("LOCKED");
    }

    // --- Đặt lại mật khẩu ----------------------------------------------------------------------------------------

    @Test
    void resetPasswordReplacesTheOldOne() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        UUID staffId = createStaff(owner, key, "Nhân viên", "matkhau1");

        resetPassword(owner, staffId, "mat-khau-moi-9")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", key, "password", "matkhau1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"));
        login(key, "mat-khau-moi-9");

        List<Map<String, Object>> resets = auditRows(owner.storeId(), "platform.user.reset_password");
        assertThat(resets).hasSize(1);
        assertThat(resets.getFirst())
                .containsEntry("entity", "app_user")
                .containsEntry("entity_id", staffId.toString())
                .containsEntry("actor_id", owner.userId())
                .containsEntry("before", null)
                .containsEntry("after", null);
        assertNoPasswordInAudit(owner.storeId(), "mat-khau-moi-9");
        assertNoPasswordInAudit(owner.storeId(), "matkhau1");
    }

    @Test
    void resetPasswordValidatesTheNewPasswordFirst() throws Exception {
        Owner owner = newStore();
        UUID staffId = createStaff(owner, uniqueKey(), "Nhân viên", "matkhau1");
        resetPassword(owner, staffId, "ngan")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu từ 8 đến 72 ký tự"));
        mvc.perform(post(USERS + "/" + staffId + "/reset-password").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Nhập mật khẩu"));
        resetPassword(owner, staffId, " ".repeat(8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Nhập mật khẩu"));
        mvc.perform(post(USERS + "/" + staffId + "/reset-password").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"matkhau\\udc00x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu có ký tự không hợp lệ"));
        // Dữ liệu sai báo trước 404
        resetPassword(owner, UUID.randomUUID(), "ngan").andExpect(status().isBadRequest());
        assertThat(auditRows(owner.storeId(), "platform.user.reset_password")).isEmpty();
    }

    @Test
    void aLockedStaffCanHaveTheirPasswordResetAndStaysLocked() throws Exception {
        Owner owner = newStore();
        String key = uniqueKey();
        UUID staffId = createStaff(owner, key, "Nhân viên", "matkhau1");
        mvc.perform(post(USERS + "/" + staffId + "/lock").cookie(owner.cookie())).andExpect(status().isOk());
        resetPassword(owner, staffId, "mat-khau-moi-9").andExpect(status().isNoContent());
        assertThat(statusOf(owner.storeId(), staffId)).isEqualTo("LOCKED");
        mvc.perform(post(USERS + "/" + staffId + "/unlock").cookie(owner.cookie())).andExpect(status().isOk());
        login(key, "mat-khau-moi-9");
    }

    // --- Chủ cửa hàng, Cửa hàng khác, Nhân viên gọi ------------------------------------------------------------

    @Test
    void theOwnerAccountIsProtected() throws Exception {
        Owner owner = newStore();
        mvc.perform(post(USERS + "/" + owner.userId() + "/lock").cookie(owner.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/owner-protected"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(post(USERS + "/" + owner.userId() + "/unlock").cookie(owner.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/owner-protected"));
        resetPassword(owner, owner.userId(), "mat-khau-moi-9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/platform/owner-protected"));

        assertThat(statusOf(owner.storeId(), owner.userId())).isEqualTo("ACTIVE");
        assertThat(auditRows(owner.storeId())).isEmpty();
        login(owner.loginKey(), OWNER_PASSWORD);
        mvc.perform(get("/api/v1/platform/me").cookie(owner.cookie())).andExpect(status().isOk());
    }

    @Test
    void staffOfAnotherStoreIsNotFound() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        String key = uniqueKey();
        UUID staffOfB = createStaff(b, key, "Nhân viên B", "matkhau1");

        for (String action : List.of("lock", "unlock")) {
            mvc.perform(post(USERS + "/" + staffOfB + "/" + action).cookie(a.cookie()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        }
        resetPassword(a, staffOfB, "mat-khau-moi-9")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        // Id không có
        mvc.perform(post(USERS + "/" + UUID.randomUUID() + "/lock").cookie(a.cookie()))
                .andExpect(status().isNotFound());

        // Danh sách của A không có Nhân viên của B; Nhân viên của B không bị đụng
        mvc.perform(get(USERS).cookie(a.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[*].userId", everyItem(not(staffOfB.toString()))));
        assertThat(statusOf(b.storeId(), staffOfB)).isEqualTo("ACTIVE");
        login(key, "matkhau1");
        assertThat(auditRows(a.storeId())).isEmpty();
        assertThat(auditRows(b.storeId())).hasSize(1);
    }

    @Test
    void staffCannotManageAccounts() throws Exception {
        Cookie staff = login("dev.nv.a", "demo1234");
        UUID self = UUID.fromString(json.readTree(mvc.perform(get("/api/v1/platform/me").cookie(staff))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("userId").asString());
        String key = uniqueKey();
        List<ResultActions> calls = new ArrayList<>();
        calls.add(mvc.perform(get(USERS).cookie(staff)));
        calls.add(mvc.perform(post(USERS).cookie(staff).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(key, "Lén tạo", "matkhau1", null)))));
        calls.add(mvc.perform(post(USERS + "/" + self + "/lock").cookie(staff)));
        calls.add(mvc.perform(post(USERS + "/" + self + "/unlock").cookie(staff)));
        calls.add(mvc.perform(post(USERS + "/" + self + "/reset-password").cookie(staff)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"mat-khau-moi-9\"}")));
        for (ResultActions call : calls) {
            call.andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        }
        assertThat(asStore(STORE_A, () -> jdbc.queryForObject(
                "select count(*) from app.app_user where login_key = ?", Long.class, key))).isZero();
        assertThat(statusOf(STORE_A, self)).isEqualTo("ACTIVE");
        login("dev.nv.a", "demo1234");
    }

    // --- Hỗ trợ --------------------------------------------------------------------------------------------------

    static Map<String, String> body(String loginKey, String displayName, String password, String phone) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("loginKey", loginKey);
        body.put("displayName", displayName);
        body.put("password", password);
        if (phone != null) {
            body.put("phone", phone);
        }
        return body;
    }

    /** Tên đăng nhập chưa ai dùng (unique toàn hệ thống). */
    static String uniqueKey() {
        return "nv." + UUID.randomUUID().toString().substring(0, 8);
    }

    ResultActions create(Owner owner, Map<String, String> body) throws Exception {
        return mvc.perform(post(USERS).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    UUID createStaff(Owner owner, String loginKey, String displayName, String password) throws Exception {
        String response = create(owner, body(loginKey, displayName, password, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(response).get("userId").asString());
    }

    ResultActions resetPassword(Owner owner, UUID userId, String password) throws Exception {
        return mvc.perform(post(USERS + "/" + userId + "/reset-password").cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("password", password))));
    }

    String statusOf(UUID storeId, UUID userId) {
        return asStore(storeId, () -> jdbc.queryForObject("select status from app.app_user where id = ?",
                String.class, userId));
    }

    /** Đổi trạng thái thẳng trong database, không qua API (không bỏ cache trạng thái). */
    void setStatus(UUID storeId, UUID userId, String status) {
        asStore(storeId, () -> jdbc.update("update app.app_user set status = ? where id = ?", status, userId));
    }

    long userCount(UUID storeId) {
        Long count = asStore(storeId, () -> jdbc.queryForObject(
                "select count(*) from app.app_user where store_id = ?", Long.class, storeId));
        return count == null ? 0 : count;
    }

    List<Map<String, Object>> auditRows(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForList("""
                select action, entity, entity_id, actor_id, before::text as before, after::text as after, request_id
                from app.audit_log where entity = 'app_user'
                order by created_at, id
                """));
    }

    List<Map<String, Object>> auditRows(UUID storeId, String action) {
        return auditRows(storeId).stream().filter(row -> action.equals(row.get("action"))).toList();
    }

    void assertNoPasswordInAudit(UUID storeId, String password) {
        String all = asStore(storeId, () -> jdbc.queryForObject(
                "select coalesce(string_agg(coalesce(before::text, '') || coalesce(after::text, ''), ''), '') "
                        + "from app.audit_log", String.class));
        assertThat(all).doesNotContain(password);
    }

    static Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }

    Owner newStore() throws Exception {
        String loginKey = "cuahang." + UUID.randomUUID().toString().replace("-", "") + "@test.vn";
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Nhân viên");
        body.put("loginKey", loginKey);
        body.put("password", OWNER_PASSWORD);
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()),
                UUID.fromString(me.get("userId").asString()), loginKey);
    }

    Cookie login(String loginKey, String password) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /**
     * {@code Clock} của ứng dụng trong test: giờ hệ thống, hoặc đứng yên ở một mốc sau {@link #freezeAt}.
     * {@link #useSystemTime} trả lại giờ hệ thống. Chỉ cho đứng ở mốc sau giờ hệ thống, để token cấp trước đó vẫn còn
     * hạn và cache trạng thái tài khoản đã ghi trước đó được coi là cũ.
     */
    public static class TestClock extends Clock {

        private volatile Instant frozen;

        Instant freezeAt(Duration ahead) {
            Instant at = Clock.systemUTC().instant().plus(ahead).truncatedTo(ChronoUnit.MICROS);
            frozen = at;
            return at;
        }

        void useSystemTime() {
            frozen = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            Instant at = frozen;
            return at != null ? at : Clock.systemUTC().instant();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        TestClock staffAccountTestClock() {
            return new TestClock();
        }
    }
}
