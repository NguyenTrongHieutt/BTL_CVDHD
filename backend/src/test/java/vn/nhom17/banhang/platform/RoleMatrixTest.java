package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.internal.security.JwtService;
import vn.nhom17.banhang.platform.internal.security.SecurityProperties;

/**
 * Ma trận vai trò × endpoint (AD-16, AD-21; FR-46), chạy mỗi lần build.
 *
 * <ul>
 *   <li>{@code ALLOW}: lớp phân quyền cho qua (không 401/403) và không lỗi 5xx. Endpoint chưa làm trả 404/405 vẫn
 *       tính là qua, nên các dòng của route sắp làm đã chốt quyền trước.</li>
 *   <li>{@code UNAUTHORIZED}/{@code FORBIDDEN}: đúng 401/403 với {@code type} của lớp phân quyền, không phải lỗi
 *       nghiệp vụ trùng mã HTTP.</li>
 * </ul>
 *
 * <p>Mở route mới thì thêm dòng ở đây: {@link #everyMappedApiRouteHasAMatrixRow()} hỏng nếu một route {@code /api/**}
 * hoặc {@code /internal/**} đã map mà chưa có dòng. Biến đường dẫn viết dạng {@code {id}} (tên nào cũng được).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMatrixTest {

    static final UUID STORE_A = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    static final UUID STORE_B = UUID.fromString("d0000000-0000-4000-8000-00000000000b");
    /** Giá trị cố định thay cho mọi biến đường dẫn, để tên test không đổi giữa các lần chạy. */
    static final String PATH_ID = "00000000-0000-4000-8000-000000000001";

    /** Kết quả mong đợi cho một kiểu người gọi. */
    enum Access { ALLOW, UNAUTHORIZED, FORBIDDEN }

    static final Access A = Access.ALLOW;
    static final Access U = Access.UNAUTHORIZED;
    static final Access F = Access.FORBIDDEN;

    /** Kiểu người gọi; Bearer giống cookie nhưng gửi qua header như dịch vụ AI (AD-9). */
    enum Caller { ANONYMOUS, STAFF, OWNER, STAFF_BEARER }

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    String staffToken;
    String ownerToken;
    UUID ownerUserId;

    @BeforeAll
    void logIn() throws Exception {
        staffToken = login("dev.nv.a");
        ownerToken = login("dev.chu.a");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(new Cookie("access_token", ownerToken)))
                .andReturn().getResponse().getContentAsString();
        ownerUserId = UUID.fromString(JsonPath.read(me, "$.userId"));
    }

    /**
     * (phương thức, đường dẫn, chưa đăng nhập, Nhân viên, Chủ cửa hàng) — theo PRD 4.7, docs/api/contracts.md và
     * SecurityConfig. Nhân viên gọi bằng cookie và bằng header Bearer phải ra cùng kết quả.
     */
    static Stream<Arguments> matrix() {
        return Stream.of(
                // Công khai. Đăng ký Cửa hàng là cho người mới: token gửi kèm bị bỏ qua (resolver), nên ai gọi cũng
                // như khách và tạo một Cửa hàng MỚI, không đụng Cửa hàng của người đang đăng nhập.
                row("POST", "/api/v1/platform/auth/login", A, A, A),
                row("POST", "/api/v1/platform/auth/logout", A, A, A),
                row("POST", "/api/v1/platform/stores", A, A, A),
                row("GET", "/v3/api-docs", A, A, A),
                row("GET", "/swagger-ui.html", A, A, A),
                row("GET", "/actuator/health", A, A, A),
                row("GET", "/actuator/health/liveness", A, A, A),
                row("GET", "/actuator/health/readiness", A, A, A),
                // Actuator khác đã expose nhưng không mở ra ngoài
                row("GET", "/actuator/info", U, F, F),
                row("GET", "/actuator/modulith", U, F, F),
                // platform
                row("GET", "/api/v1/platform/me", U, A, A),
                row("PUT", "/api/v1/platform/me", U, A, A),
                row("POST", "/api/v1/platform/me/password", U, A, A),
                row("GET", "/api/v1/platform/store", U, F, A),
                row("PUT", "/api/v1/platform/store", U, F, A),
                row("GET", "/api/v1/platform/users", U, F, A),
                row("POST", "/api/v1/platform/users", U, F, A),
                row("POST", "/api/v1/platform/users/{id}/lock", U, F, A),
                row("POST", "/api/v1/platform/users/{id}/reset-password", U, F, A),
                row("GET", "/api/v1/platform/audit-log", U, F, A),
                // catalog: xem cả hai vai trò; thêm, sửa Sản phẩm (cả giá), Danh mục, Nhà cung cấp chỉ Chủ cửa hàng
                row("GET", "/api/v1/catalog/products", U, A, A),
                row("GET", "/api/v1/catalog/products/{id}", U, A, A),
                row("POST", "/api/v1/catalog/products", U, F, A),
                row("PUT", "/api/v1/catalog/products/{id}", U, F, A),
                row("POST", "/api/v1/catalog/products/{id}/deactivate", U, F, A),
                row("GET", "/api/v1/catalog/categories", U, A, A),
                row("POST", "/api/v1/catalog/categories", U, F, A),
                row("PUT", "/api/v1/catalog/categories/{id}", U, F, A),
                row("DELETE", "/api/v1/catalog/categories/{id}", U, F, A),
                row("GET", "/api/v1/catalog/suppliers", U, A, A),
                row("POST", "/api/v1/catalog/suppliers", U, F, A),
                row("PUT", "/api/v1/catalog/suppliers/{id}", U, F, A),
                // Khách hàng: cả hai vai trò thêm, sửa (FR-18). SecurityConfig mở mọi phương thức trên nhánh này.
                row("GET", "/api/v1/catalog/customers", U, A, A),
                row("POST", "/api/v1/catalog/customers", U, A, A),
                row("PUT", "/api/v1/catalog/customers/{id}", U, A, A),
                row("DELETE", "/api/v1/catalog/customers/{id}", U, A, A),
                // sales: cả hai vai trò; quy tắc hủy đơn của Nhân viên kiểm ở sales (AD-16)
                row("GET", "/api/v1/sales/sellable", U, A, A),
                row("POST", "/api/v1/sales/orders/quote", U, A, A),
                row("POST", "/api/v1/sales/orders", U, A, A),
                row("GET", "/api/v1/sales/orders", U, A, A),
                row("POST", "/api/v1/sales/orders/{id}/cancel", U, A, A),
                row("POST", "/api/v1/sales/orders/{id}/returns", U, A, A),
                row("GET", "/api/v1/sales/customers/{id}/purchase-summary", U, A, A),
                // inventory: Nhân viên chỉ xem Tồn kho (DTO không có Giá vốn) và cảnh báo tồn thấp
                row("GET", "/api/v1/inventory/stock", U, A, A),
                row("GET", "/api/v1/inventory/stock/{id}", U, A, A),
                row("GET", "/api/v1/inventory/low-stock", U, A, A),
                row("GET", "/api/v1/inventory/ledger", U, F, A),
                row("POST", "/api/v1/inventory/purchase-receipts", U, F, A),
                row("POST", "/api/v1/inventory/stocktakes", U, F, A),
                row("PUT", "/api/v1/inventory/stock/{id}/min-quantity", U, F, A),
                // reporting: chỉ Chủ cửa hàng
                row("GET", "/api/v1/reporting/q/{id}", U, F, A),
                // Chỉ dịch vụ AI, bằng X-Service-Key: token người dùng không đủ (xem serviceKey…)
                row("GET", "/internal/catalog/product-changes", U, F, F),
                // Route chưa khai báo: mặc định cấm (AD-16)
                row("GET", "/api/v1/khong-khai-bao", U, F, F),
                // Quyền theo đường dẫn, không theo phương thức: PATCH /me qua lớp quyền rồi nhận 405
                row("PATCH", "/api/v1/platform/me", U, A, A),
                row("GET", "/api/v2/platform/me", U, F, F));
    }

    static Arguments row(String method, String path, Access anonymous, Access staff, Access owner) {
        return Arguments.of(method, path, anonymous, staff, owner);
    }

    @ParameterizedTest(name = "{0} {1}: khách {2}, Nhân viên {3}, Chủ {4}")
    @MethodSource("matrix")
    void eachCallerGetsTheDeclaredAccess(String method, String path, Access anonymous, Access staff, Access owner)
            throws Exception {
        String url = path.replaceAll("\\{[^}]+}", PATH_ID);
        SoftAssertions soft = new SoftAssertions();
        check(soft, method, url, Caller.ANONYMOUS, anonymous);
        check(soft, method, url, Caller.STAFF, staff);
        check(soft, method, url, Caller.STAFF_BEARER, staff);
        check(soft, method, url, Caller.OWNER, owner);
        soft.assertAll();
    }

    private void check(SoftAssertions soft, String method, String url, Caller caller, Access expected)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), url)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        switch (caller) {
            case STAFF -> builder.cookie(new Cookie("access_token", staffToken));
            case STAFF_BEARER -> builder.header("Authorization", "Bearer " + staffToken);
            case OWNER -> builder.cookie(new Cookie("access_token", ownerToken));
            case ANONYMOUS -> { }
        }
        MockHttpServletResponse response = mvc.perform(builder).andReturn().getResponse();
        int status = response.getStatus();
        String label = method + " " + url + " (" + caller + ")";
        switch (expected) {
            case ALLOW -> soft.assertThat(status).as(label).isNotIn(401, 403).isLessThan(500);
            case UNAUTHORIZED -> {
                soft.assertThat(status).as(label).isEqualTo(401);
                soft.assertThat(problemType(response)).as(label).isEqualTo("/errors/common/unauthorized");
            }
            case FORBIDDEN -> {
                soft.assertThat(status).as(label).isEqualTo(403);
                soft.assertThat(problemType(response)).as(label).isEqualTo("/errors/common/forbidden");
            }
        }
    }

    private static String problemType(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        try {
            return JsonPath.read(body, "$.type");
        } catch (RuntimeException notAProblem) {
            return "(không phải Problem: " + body + ")";
        }
    }

    /** Route đã map mà quên thêm dòng thì nó lặng lẽ thừa hưởng quyền của wildcard trong SecurityConfig. */
    @Test
    void everyMappedApiRouteHasAMatrixRow() {
        Set<String> rows = matrix().map(a -> a.get()[0] + " " + normalize((String) a.get()[1]))
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/") && !pattern.startsWith("/internal/")) {
                    continue;
                }
                info.getMethodsCondition().getMethods().forEach(m -> {
                    String key = m.name() + " " + normalize(pattern);
                    if (!rows.contains(key)) {
                        missing.add(key);
                    }
                });
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    missing.add("(mọi phương thức) " + pattern);
                }
            }
        }
        assertThat(missing).as("Route chưa có dòng trong RoleMatrixTest.matrix()").isEmpty();
    }

    private static String normalize(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", "{}");
    }

    // --- X-Service-Key: chỉ mở /internal, không mở gì ở /api ---------------------------------------------------------

    @Test
    void serviceKeyOpensOnlyInternalRoutes() throws Exception {
        int withKey = mvc.perform(get("/internal/catalog/product-changes")
                .header("X-Service-Key", TestcontainersConfiguration.SERVICE_KEY)).andReturn().getResponse().getStatus();
        assertThat(withKey).isNotIn(401, 403).isLessThan(500);
        mvc.perform(get("/internal/catalog/product-changes").header("X-Service-Key", "sai-khoa"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/me").header("X-Service-Key", TestcontainersConfiguration.SERVICE_KEY))
                .andExpect(status().isUnauthorized());
    }

    // --- FR-46: không đổi được Cửa hàng đang dùng bằng dữ liệu trong yêu cầu ------------------------------------------

    /**
     * Ngữ cảnh Cửa hàng chỉ lấy từ token đã ký. Mã Cửa hàng khác đặt trong query hay header bị bỏ qua. Trường hợp id
     * bản ghi của Cửa hàng khác trong đường dẫn hoặc body được kiểm ở test của từng module (404, AD-5 mục 8).
     */
    @Test
    void storeIdInQueryOrHeaderIsIgnored() throws Exception {
        for (String token : List.of(ownerToken, staffToken)) {
            mvc.perform(get("/api/v1/platform/me").param("storeId", STORE_B.toString())
                            .header("X-Store-Id", STORE_B.toString()).cookie(new Cookie("access_token", token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storeId").value(STORE_A.toString()));
        }
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        // Đúng định dạng, đúng người dùng có thật (Chủ cửa hàng A), chỉ khác khóa ký và ghi store_id của B
        JwtService otherKey = new JwtService(new SecurityProperties("mot-khoa-khac-0123456789-0123456789-xyz",
                Duration.ofHours(8), Duration.ofMinutes(15), Duration.ofSeconds(60), false, "k"), Clock.systemUTC());
        String forged = otherKey.issue(ownerUserId, STORE_B, Role.OWNER);
        mvc.perform(get("/api/v1/platform/me").cookie(new Cookie("access_token", forged)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/common/unauthorized"));
    }

    @Test
    void anUnsignedTokenIsRejected() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = b64.encodeToString(("{\"sub\":\"" + ownerUserId + "\",\"store_id\":\"" + STORE_B
                + "\",\"role\":\"OWNER\",\"exp\":4102444800}").getBytes());
        mvc.perform(get("/api/v1/platform/me").cookie(new Cookie("access_token", header + "." + payload + ".")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/common/unauthorized"));
    }

    private String login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token").getValue();
    }
}
