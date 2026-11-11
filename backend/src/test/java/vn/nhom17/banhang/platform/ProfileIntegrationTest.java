package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * Thông tin cá nhân, đổi mật khẩu (FR-47, TK-04) trên PostgreSQL thật: ma trận I/O của spec. Mỗi test dùng Cửa hàng
 * mới đăng ký riêng (và Nhân viên mới tạo), không đụng tài khoản dev.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileIntegrationTest {

    static final String ME = "/api/v1/platform/me";
    static final String PROFILE = "/api/v1/platform/me/profile";
    static final String PASSWORD = "/api/v1/platform/me/password";
    static final String OWNER_PASSWORD = "matkhau123";
    static final String STAFF_PASSWORD = "matkhau1";
    static final Set<String> PROFILE_FIELDS = Set.of("userId", "loginKey", "displayName", "role", "phone", "email");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;

    record Account(Cookie cookie, UUID storeId, UUID userId, String loginKey, String password) {
    }

    // --- Xem hồ sơ -----------------------------------------------------------------------------------------------

    @Test
    void staffSeesTheirOwnProfile() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", "0912 345 678");
        String body = mvc.perform(get(PROFILE).cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staff.userId().toString()))
                .andExpect(jsonPath("$.loginKey").value(staff.loginKey()))
                .andExpect(jsonPath("$.displayName").value("Minh"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.phone").value("0912345678"))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(fieldNames(json.readTree(body))).isEqualTo(PROFILE_FIELDS);
        assertThat(body).doesNotContain("bcrypt").doesNotContain("password");

        // Chủ cửa hàng thấy hồ sơ của mình, không phải của Nhân viên
        mvc.perform(get(PROFILE).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.userId().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.displayName").value("Chủ thử"));
    }

    @Test
    void anOwnerRegisteredByEmailOrPhoneHasThatContact() throws Exception {
        Account byEmail = newStore();
        mvc.perform(get(PROFILE).cookie(byEmail.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginKey").value(byEmail.loginKey()))
                .andExpect(jsonPath("$.email").value(byEmail.loginKey()))
                .andExpect(jsonPath("$.phone").value(nullValue()));

        Account byPhone = newStore(uniquePhone());
        mvc.perform(get(PROFILE).cookie(byPhone.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginKey").value(byPhone.loginKey()))
                .andExpect(jsonPath("$.phone").value(byPhone.loginKey()))
                .andExpect(jsonPath("$.email").value(nullValue()));
    }

    // --- Sửa hồ sơ -----------------------------------------------------------------------------------------------

    @Test
    void renamingTrimsTheNameAndMeShowsItRightAway() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        updateProfile(staff, " Minh Anh ", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staff.userId().toString()))
                .andExpect(jsonPath("$.displayName").value("Minh Anh"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.loginKey").value(staff.loginKey()));

        // Tên trên thanh trên cùng (GET /me) đổi theo, cùng phiên, không cần đăng nhập lại
        mvc.perform(get(ME).cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Minh Anh"));
        mvc.perform(get(PROFILE).cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Minh Anh"));
        // Bảng Nhân viên của Chủ cửa hàng cũng thấy tên mới
        mvc.perform(get("/api/v1/platform/users").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].displayName").value("Minh Anh"));
        // Tên chuẩn hóa như các tên khác: khoảng trắng Unicode, ký tự định dạng vô hình, NFD
        updateProfile(staff, "\u00a0Lan\u200b Anh\u3000", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Lan Anh"));
        updateProfile(staff, "Lê\u0301", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Lế"));
    }

    @Test
    void phoneIsAVietnamNumberStoredAsZeroPrefixed() throws Exception {
        Account owner = newStore();
        updateProfile(owner, "Chủ thử", "+84 912-345.678")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0912345678"));
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select phone from app.app_user where id = ?", String.class, owner.userId()))).isEqualTo("0912345678");
        updateProfile(owner, "Chủ thử", " 0987 654 321 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0987654321"));

        // Bỏ trống hoặc không gửi thì xóa số đang lưu
        updateProfile(owner, "Chủ thử", "  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(nullValue()));
        updateProfile(owner, "Chủ thử", "0987654321").andExpect(status().isOk());
        updateProfile(owner, "Chủ thử", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(nullValue()));
        // Tên đăng nhập, email không đổi theo số điện thoại
        mvc.perform(get(PROFILE).cookie(owner.cookie()))
                .andExpect(jsonPath("$.loginKey").value(owner.loginKey()))
                .andExpect(jsonPath("$.email").value(owner.loginKey()));
    }

    /**
     * Dòng cũ: Nhân viên tạo trước khi TK-02 dùng quy tắc số Việt Nam có thể có số bàn lưu nguyên như lúc nhập. Gửi lại
     * y nguyên thì giữ, để vẫn sửa được tên; đổi sang số khác thì phải là số Việt Nam.
     */
    @Test
    void aLegacyLandlineSentBackUnchangedIsKept() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        asStore(staff.storeId(), () -> jdbc.update("update app.app_user set phone = ? where id = ?",
                "028 1234 5678", staff.userId()));

        updateProfile(staff, "Minh Anh", " 028 1234 5678 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Minh Anh"))
                .andExpect(jsonPath("$.phone").value("028 1234 5678"));
        assertThat(userRow(staff)).containsEntry("phone", "028 1234 5678").containsEntry("display_name", "Minh Anh");

        // Số khác vẫn theo quy tắc số Việt Nam
        for (String other : List.of("028 1234 5679", "02812345678", "(028) 1234 5678")) {
            updateProfile(staff, "Minh", other)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }
        assertThat(userRow(staff)).containsEntry("phone", "028 1234 5678").containsEntry("display_name", "Minh Anh");

        // Đổi sang số di động: lưu dạng 0…; số bàn cũ không còn nên gửi lại bị từ chối
        updateProfile(staff, "Minh Anh", "0912 345 678")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0912345678"));
        updateProfile(staff, "Minh Anh", "028 1234 5678")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"091234567", "09123456789", "1912345678", "+8491234567", "84912345678", "0912abc678",
            "(091) 234 5678", "0912/345/678"})
    void phonesThatAreNotAVietnamNumberAreRejected(String phone) throws Exception {
        Account owner = newStore();
        updateProfile(owner, "Chủ thử", phone)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("phone"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số"));
    }

    @Test
    void anEmptyNameIsRejectedAndNothingChanges() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        updateProfile(staff, "Minh", "0912345678").andExpect(status().isOk());
        Map<String, Object> before = userRow(staff);

        for (String name : new String[] {"", "   ", "\u200b"}) {
            updateProfile(staff, name, "0987654321")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                    .andExpect(jsonPath("$.errors[0].message").value("Nhập tên hiển thị"));
        }
        // Không gửi gì
        mvc.perform(put(ME).cookie(staff.cookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        // Mọi trường sai trong một lần
        updateProfile(staff, "a".repeat(101), "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].message").value("Tên hiển thị tối đa 100 ký tự"))
                .andExpect(jsonPath("$.errors[1].field").value("phone"));
        updateProfile(staff, "Minh\u0007", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Tên hiển thị có ký tự không hợp lệ"));

        assertThat(userRow(staff)).isEqualTo(before);
        // Đúng giới hạn: 100 ký tự
        updateProfile(staff, "a".repeat(100), null).andExpect(status().isOk());
    }

    @Test
    void loginKeyEmailAndRoleCannotBeChanged() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", "Minh Anh");
        body.put("loginKey", "ten.moi." + UUID.randomUUID().toString().substring(0, 8));
        body.put("email", "moi@test.vn");
        body.put("role", "OWNER");
        body.put("userId", owner.userId().toString());
        mvc.perform(put(ME).cookie(staff.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staff.userId().toString()))
                .andExpect(jsonPath("$.displayName").value("Minh Anh"))
                .andExpect(jsonPath("$.loginKey").value(staff.loginKey()))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.role").value("STAFF"));

        Map<String, Object> row = userRow(staff);
        assertThat(row).containsEntry("login_key", staff.loginKey()).containsEntry("email", null)
                .containsEntry("role", "STAFF").containsEntry("display_name", "Minh Anh");
        // Chủ cửa hàng (id gửi trong body) không bị đụng
        mvc.perform(get(PROFILE).cookie(owner.cookie())).andExpect(jsonPath("$.displayName").value("Chủ thử"));
        // Vẫn đăng nhập bằng tên cũ, vẫn là Nhân viên
        login(staff.loginKey(), staff.password());
        mvc.perform(get(ME).cookie(staff.cookie())).andExpect(jsonPath("$.role").value("STAFF"));
    }

    @Test
    void savingTheSameValuesWritesNothing() throws Exception {
        Account owner = newStore();
        updateProfile(owner, "Chủ Hoa", "0912345678").andExpect(status().isOk());
        Map<String, Object> before = userRow(owner);
        // Y nguyên sau chuẩn hóa: khoảng trắng hai đầu, số viết khác
        updateProfile(owner, "  Chủ Hoa ", "+84 912 345 678")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Chủ Hoa"))
                .andExpect(jsonPath("$.phone").value("0912345678"));
        assertThat(userRow(owner)).isEqualTo(before);   // gồm cả updated_at
        assertThat(auditCount(owner.storeId())).isZero();

        OffsetDateTime savedAt = updatedAt(owner);
        updateProfile(owner, "Chủ Hoa Mới", "0912345678").andExpect(status().isOk());
        assertThat(updatedAt(owner)).isAfter(savedAt);
    }

    // --- Đổi mật khẩu --------------------------------------------------------------------------------------------

    @Test
    void changingThePasswordReplacesTheOldOneAndKeepsTheSession() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        changePassword(staff, STAFF_PASSWORD, "mat-khau-moi-9")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));

        // Mật khẩu cũ hết dùng được, mật khẩu mới đăng nhập được
        mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", staff.loginKey(), "password", STAFF_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/platform/invalid-credentials"));
        login(staff.loginKey(), "mat-khau-moi-9");
        // Phiên hiện tại vẫn dùng được
        mvc.perform(get(ME).cookie(staff.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staff.userId().toString()));
        // Mật khẩu lưu dạng băm, không ghi vào Nhật ký
        String hash = (String) userRow(staff).get("password_hash");
        assertThat(hash).startsWith("{bcrypt}").doesNotContain("mat-khau-moi-9");
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select coalesce(string_agg(coalesce(before::text, '') || coalesce(after::text, ''), ''), '') "
                        + "from app.audit_log", String.class))).doesNotContain("mat-khau-moi-9");

        // Chủ cửa hàng cũng đổi được mật khẩu của mình
        changePassword(owner, OWNER_PASSWORD, "chu-mat-khau-moi").andExpect(status().isNoContent());
        login(owner.loginKey(), "chu-mat-khau-moi");
        mvc.perform(get(ME).cookie(owner.cookie())).andExpect(status().isOk());
        // Mật khẩu 72 byte (giới hạn của bcrypt) đặt được
        changePassword(owner, "chu-mat-khau-moi", "ă".repeat(36)).andExpect(status().isNoContent());
        login(owner.loginKey(), "ă".repeat(36));
    }

    @Test
    void aWrongCurrentPasswordIs422AndChangesNothing() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        String hashBefore = (String) userRow(staff).get("password_hash");

        for (String wrong : new String[] {"sai-mat-khau", STAFF_PASSWORD + " ", "MATKHAU1", "a".repeat(80)}) {
            MockHttpServletResponse response = changePassword(staff, wrong, "mat-khau-moi-9")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("/errors/platform/wrong-current-password"))
                    .andExpect(jsonPath("$.requestId").exists())
                    .andReturn().getResponse();
            // Không phải hết phiên: không xóa cookie
            assertThat(response.getHeader("Set-Cookie")).isNull();
        }
        assertThat(userRow(staff).get("password_hash")).isEqualTo(hashBefore);
        login(staff.loginKey(), STAFF_PASSWORD);
        mvc.perform(get(ME).cookie(staff.cookie())).andExpect(status().isOk());
    }

    /**
     * bcrypt chỉ so 72 byte đầu: mật khẩu đúng 72 byte cộng thêm ký tự vẫn khớp nếu đưa cho bcrypt. Mật khẩu hiện tại
     * dài hơn 72 byte phải bị coi là sai mà không qua bcrypt.
     */
    @Test
    void aCurrentPasswordLongerThan72BytesIsWrongEvenIfItsFirst72BytesMatch() throws Exception {
        Account owner = newStore();
        String seventyTwoBytes = "ă".repeat(36);
        changePassword(owner, OWNER_PASSWORD, seventyTwoBytes).andExpect(status().isNoContent());
        String hashBefore = (String) userRow(owner).get("password_hash");

        changePassword(owner, seventyTwoBytes + "x", "mat-khau-moi-9")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("/errors/platform/wrong-current-password"));
        assertThat(userRow(owner).get("password_hash")).isEqualTo(hashBefore);
        login(owner.loginKey(), seventyTwoBytes);
    }

    @Test
    void theNewPasswordMustBeValidAndDifferent() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        changePassword(staff, STAFF_PASSWORD, "ngan")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu mới từ 8 đến 72 ký tự"));
        changePassword(staff, STAFF_PASSWORD, STAFF_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu mới phải khác mật khẩu hiện tại"));
        changePassword(staff, STAFF_PASSWORD, "a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        changePassword(staff, STAFF_PASSWORD, "ă".repeat(37))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu mới quá dài, hãy dùng mật khẩu ngắn hơn"));
        changePassword(staff, STAFF_PASSWORD, " ".repeat(8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Nhập mật khẩu mới"));
        mvc.perform(post(PASSWORD).cookie(staff.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + STAFF_PASSWORD + "\",\"newPassword\":\"matkhau\\ud800x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Mật khẩu mới có ký tự không hợp lệ"));
        // Thiếu cả hai: mọi trường trong một lần
        mvc.perform(post(PASSWORD).cookie(staff.cookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("Nhập mật khẩu hiện tại"))
                .andExpect(jsonPath("$.errors[1].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[1].message").value("Nhập mật khẩu mới"));
        // Lỗi nhập liệu báo trước sai mật khẩu hiện tại
        changePassword(staff, "sai-mat-khau", "ngan")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

        login(staff.loginKey(), STAFF_PASSWORD);
    }

    @Test
    void concurrentChangesWithTheSameCurrentPasswordLetOnlyOneThrough() throws Exception {
        Account owner = newStore();
        Account staff = newStaff(owner, "Minh", null);
        int callers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<String> newPasswords = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String next = "mat-khau-moi-" + i;
                newPasswords.add(next);
                results.add(pool.submit(() -> {
                    start.await();
                    return changePassword(staff, STAFF_PASSWORD, next).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(204, 422);
            assertThat(statuses.stream().filter(s -> s == 204)).hasSize(1);
            int winner = statuses.indexOf(204);
            login(staff.loginKey(), newPasswords.get(winner));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Chưa đăng nhập ------------------------------------------------------------------------------------------

    @Test
    void anonymousCallersGet401() throws Exception {
        List<ResultActions> calls = List.of(
                mvc.perform(get(PROFILE)),
                mvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Lén\"}")),
                mvc.perform(post(PASSWORD).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"demo1234\",\"newPassword\":\"mat-khau-moi-9\"}")));
        for (ResultActions call : calls) {
            call.andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("/errors/common/unauthorized"));
        }
    }

    // --- Hỗ trợ --------------------------------------------------------------------------------------------------

    ResultActions updateProfile(Account account, String displayName, String phone) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("displayName", displayName);
        if (phone != null) {
            body.put("phone", phone);
        }
        return mvc.perform(put(ME).cookie(account.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    ResultActions changePassword(Account account, String currentPassword, String newPassword) throws Exception {
        return mvc.perform(post(PASSWORD).cookie(account.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", currentPassword, "newPassword", newPassword))));
    }

    Map<String, Object> userRow(Account account) {
        return asStore(account.storeId(), () -> jdbc.queryForMap("""
                select login_key, display_name, password_hash, role, status, phone, email, updated_at
                from app.app_user where id = ?
                """, account.userId()));
    }

    OffsetDateTime updatedAt(Account account) {
        return asStore(account.storeId(), () -> jdbc.queryForObject(
                "select updated_at from app.app_user where id = ?", OffsetDateTime.class, account.userId()));
    }

    long auditCount(UUID storeId) {
        Long count = asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.audit_log", Long.class));
        return count == null ? 0 : count;
    }

    static Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }

    /** Số điện thoại chưa ai dùng làm tên đăng nhập. */
    static String uniquePhone() {
        return "09" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    Account newStore() throws Exception {
        return newStore("cuahang." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
    }

    /** Cửa hàng mới, Chủ cửa hàng đăng ký bằng {@code loginKey} (email hoặc số điện thoại), tên "Chủ thử". */
    Account newStore(String loginKey) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Thông tin cá nhân");
        body.put("loginKey", loginKey);
        body.put("password", OWNER_PASSWORD);
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Account(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()),
                UUID.fromString(me.get("userId").asString()), loginKey, OWNER_PASSWORD);
    }

    /** Nhân viên mới của Cửa hàng của {@code owner}, đã đăng nhập. */
    Account newStaff(Account owner, String displayName, String phone) throws Exception {
        String loginKey = "nv." + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("loginKey", loginKey);
        body.put("displayName", displayName);
        body.put("password", STAFF_PASSWORD);
        if (phone != null) {
            body.put("phone", phone);
        }
        String created = mvc.perform(post("/api/v1/platform/users").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID userId = UUID.fromString(json.readTree(created).get("userId").asString());
        return new Account(login(loginKey, STAFF_PASSWORD), owner.storeId(), userId, loginKey, STAFF_PASSWORD);
    }

    Cookie login(String loginKey, String password) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }
}
