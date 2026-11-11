package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.CustomerLookup.CustomerSummary;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Hồ sơ và tìm kiếm Khách hàng (FR-18 KH-01, FR-19 KH-02) trên PostgreSQL thật: ma trận I/O của spec cho REST
 * {@code /api/v1/catalog/customers} và {@link CustomerLookup} gọi thẳng dưới {@link TenantScope} như {@code sales} sẽ
 * gọi. Phần lớn test dùng Cửa hàng mới đăng ký riêng; test của Nhân viên dùng Cửa hàng dev A với số điện thoại ngẫu
 * nhiên, nên không phụ thuộc test khác chạy chung database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerIntegrationTest {

    static final String CUSTOMERS = "/api/v1/catalog/customers";
    static final String PHONE_TAKEN = "/errors/catalog/customer-phone-taken";
    static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** Dãy số điện thoại không trùng giữa các test chạy trên Cửa hàng dev dùng chung. */
    static final AtomicLong PHONES = new AtomicLong(ThreadLocalRandom.current().nextLong(10_000_000L, 80_000_000L));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired CustomerLookup lookup;

    /** Người dùng đã đăng nhập và Cửa hàng của họ. */
    record Session(Cookie cookie, UUID storeId) {
    }

    // --- Thêm ------------------------------------------------------------------------------------------------

    @Test
    void staffQuickCreatesWithOnlyAPhoneAndSeesItInTheListRightAway() throws Exception {
        Session staff = staff();
        String digits = newPhone();
        String typed = digits.substring(0, 4) + " " + digits.substring(4, 7) + " " + digits.substring(7);

        String body = send(staff, post(CUSTOMERS), json(Map.of("phone", typed)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.phone").value(digits))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = idOf(body);

        // Có ngay trong bảng (AC): tìm theo số vừa thêm ra đúng Khách hàng đó
        JsonNode page = page(staff, "q", digits);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("customerId").asString()).isEqualTo(id.toString());
        assertThat(page.get("items").get(0).get("name").isNull()).isTrue();
        Map<String, Object> row = asStore(staff.storeId(), () ->
                jdbc.queryForMap("select name, phone, note, store_id from app.customer where id = ?", id));
        assertThat(row).containsEntry("phone", digits).containsEntry("store_id", staff.storeId())
                .containsEntry("name", null).containsEntry("note", null);
    }

    @Test
    void createNormalizesThePhoneToTheLocalForm() throws Exception {
        Session owner = newStore();
        createOk(owner, "0912 345 678", null, null);

        Map<String, String> full = new LinkedHashMap<>();
        full.put("phone", "+84912345679");
        full.put("name", "  Chị Hoa ");
        full.put("note", "Khách quen");
        String body = send(owner, post(CUSTOMERS), json(full))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(CUSTOMERS + "/" + UUID_REGEX)))
                .andExpect(jsonPath("$.phone").value("0912345679"))
                .andExpect(jsonPath("$.name").value("Chị Hoa"))
                .andExpect(jsonPath("$.note").value("Khách quen"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = idOf(body);

        // Dấu chấm, gạch ngang, khoảng trắng không ngắt dòng cũng được bỏ
        createOk(owner, "0912.345.670", null, null);
        createOk(owner, " +84 912-345-671 ", null, null);
        assertThat(phones(owner)).containsExactlyInAnyOrder("0912345678", "0912345679", "0912345670", "0912345671");

        // GET /{id} trả đúng như lúc tạo
        String fetched = send(owner, get(CUSTOMERS + "/" + id), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(fetched)).isEqualTo(json.readTree(body));
        assertThat(json.readTree(body).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("customerId", "name", "phone", "note", "createdAt");
    }

    @Test
    void aDuplicatePhoneIsRejectedWithTheExistingCustomerId() throws Exception {
        Session owner = newStore();
        UUID existing = createOk(owner, "0912345678", "Chị Hoa", null);

        for (String samePhone : List.of("0912345678", "+84 912 345 678", "0912.345.678")) {
            send(owner, post(CUSTOMERS), json(Map.of("phone", samePhone, "name", "Người khác")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(PHONE_TAKEN))
                    .andExpect(jsonPath("$.existingCustomerId").value(existing.toString()))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        assertThat(customerCount(owner.storeId())).isEqualTo(1);
        assertThat(name(owner.storeId(), existing)).isEqualTo("Chị Hoa");
    }

    @Test
    void anotherStoreMayUseTheSamePhone() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofB = createOk(b, "0912345678", "Khách của B", null);

        UUID ofA = createOk(a, "0912345678", "Khách của A", null);
        assertThat(ofA).isNotEqualTo(ofB);
        assertThat(customerCount(a.storeId())).isEqualTo(1);
        assertThat(customerCount(b.storeId())).isEqualTo(1);
    }

    @Test
    void anInvalidPhoneIsRejectedOnThePhoneField() throws Exception {
        Session owner = newStore();
        List<String> invalid = List.of("123", "0812", "09123456789", "091234567", "+8491234567", "+840912345678",
                "84912345678", "1912345678", "0912a45678", "0912\t345678", "(0912) 345 678", "０９１２３４５６７８");
        for (String phone : invalid) {
            send(owner, post(CUSTOMERS), json(Map.of("phone", phone)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("phone"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số"));
        }
        for (String body : List.of("{}", "{\"phone\":null}", json(Map.of("phone", "")), json(Map.of("phone", "  ")),
                json(Map.of("phone", "​")))) {
            send(owner, post(CUSTOMERS), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("phone"))
                    .andExpect(jsonPath("$.errors[0].message").value("Nhập số điện thoại"));
        }
        assertThat(customerCount(owner.storeId())).isZero();
    }

    @Test
    void nameAndNoteAreNormalizedAndLimited() throws Exception {
        Session owner = newStore();
        // Mọi trường sai được báo trong một lần
        Map<String, String> allWrong = Map.of("phone", "123", "name", "a".repeat(101), "note", "b".repeat(501));
        String body = send(owner, post(CUSTOMERS), json(allWrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> fields = new ArrayList<>();
        json.readTree(body).get("errors").forEach(e -> fields.add(e.get("field").asString()));
        assertThat(fields).containsExactlyInAnyOrder("phone", "name", "note");

        send(owner, post(CUSTOMERS), json(Map.of("phone", "0912345678", "name", "Chị\tHoa")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("Tên Khách hàng có ký tự không hợp lệ"));
        assertThat(customerCount(owner.storeId())).isZero();

        // Đúng giới hạn sau khi chuẩn hóa: chữ gõ dạng tổ hợp (NFD) là một ký tự; ký tự vô hình bị bỏ; ghi chú được
        // xuống dòng; tên, ghi chú chỉ có khoảng trắng là trống
        String decomposed = Normalizer.normalize("ệ".repeat(100), Normalizer.Form.NFD);
        UUID id = createOk(owner, "0912345678", " " + decomposed + "​ ", "Dòng 1\r\nDòng 2" + "c".repeat(487));
        assertThat(name(owner.storeId(), id)).isEqualTo("ệ".repeat(100));
        assertThat(note(owner.storeId(), id)).isEqualTo("Dòng 1\nDòng 2" + "c".repeat(487));
        UUID blank = createOk(owner, "0912345679", "   ", " 　");
        assertThat(name(owner.storeId(), blank)).isNull();
        assertThat(note(owner.storeId(), blank)).isNull();
    }

    // --- Sửa -------------------------------------------------------------------------------------------------

    @Test
    void updateReplacesTheProfile() throws Exception {
        Session owner = newStore();
        UUID id = createOk(owner, "0912345678", "Chi Hoa", "Ghi chú cũ");
        OffsetDateTime createdUpdatedAt = updatedAt(owner.storeId(), id);

        update(owner, id, "+84 912 345 678", "Chị Hoa", "Khách quen, thích trà")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Chị Hoa"))
                .andExpect(jsonPath("$.phone").value("0912345678"))
                .andExpect(jsonPath("$.note").value("Khách quen, thích trà"));
        assertThat(name(owner.storeId(), id)).isEqualTo("Chị Hoa");
        assertThat(note(owner.storeId(), id)).isEqualTo("Khách quen, thích trà");
        OffsetDateTime renamedAt = updatedAt(owner.storeId(), id);
        assertThat(renamedAt).isAfterOrEqualTo(createdUpdatedAt);

        // Đổi số sang số còn trống thì được
        update(owner, id, "0987 654 321", "Chị Hoa", "Khách quen, thích trà")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0987654321"));

        // Thay toàn bộ: tên, ghi chú thiếu hoặc rỗng thì xóa
        send(owner, put(CUSTOMERS + "/" + id), json(Map.of("phone", "0987654321", "note", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());
        assertThat(name(owner.storeId(), id)).isNull();
        assertThat(note(owner.storeId(), id)).isNull();

        // Sai thì không đổi gì
        update(owner, id, "0812", "Tên mới", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        assertThat(phones(owner)).containsExactly("0987654321");
    }

    @Test
    void updateWithNothingChangedWritesNothing() throws Exception {
        Session owner = newStore();
        UUID id = createOk(owner, "0912345678", "Chị Hoa", "Khách quen");
        OffsetDateTime before = updatedAt(owner.storeId(), id);

        update(owner, id, "0912.345.678", " Chị Hoa ", "Khách quen").andExpect(status().isOk());
        assertThat(updatedAt(owner.storeId(), id)).isEqualTo(before);
    }

    @Test
    void updateToAPhoneTakenByAnotherCustomerIsRejected() throws Exception {
        Session owner = newStore();
        UUID hoa = createOk(owner, "0912345678", "Chị Hoa", null);
        UUID ba = createOk(owner, "0987654321", "Anh Ba", null);

        update(owner, ba, "+84912345678", "Anh Ba", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PHONE_TAKEN))
                .andExpect(jsonPath("$.existingCustomerId").value(hoa.toString()));
        assertThat(phones(owner)).containsExactlyInAnyOrder("0912345678", "0987654321");
        assertThat(name(owner.storeId(), ba)).isEqualTo("Anh Ba");
    }

    @Test
    void staffCanReadAndUpdateButNotDelete() throws Exception {
        Session staff = staff();
        String digits = newPhone();
        UUID id = createOk(staff, digits, "Khách của Nhân viên", null);

        send(staff, get(CUSTOMERS + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(digits));
        update(staff, id, digits, "Đã đổi tên", "Ghi chú").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Đã đổi tên"));
        // Không có xóa Khách hàng
        send(staff, delete(CUSTOMERS + "/" + id), null).andExpect(status().isMethodNotAllowed());
        assertThat(name(staff.storeId(), id)).isEqualTo("Đã đổi tên");
    }

    // --- Tìm kiếm, danh sách --------------------------------------------------------------------------------

    @Test
    void nameMatchesWithoutAccentsAndIgnoringCase() throws Exception {
        Session owner = newStore();
        createOk(owner, "0912345678", "Chị Hoa", null);
        createOk(owner, "0912000001", "Anh Ba Đức", null);
        createOk(owner, "0912000002", null, null);

        assertThat(names(owner, "q", "chi hoa")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "CHỊ")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "  hoa ")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", Normalizer.normalize("Chị", Normalizer.Form.NFD))).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "ba duc")).containsExactly("Anh Ba Đức");
        assertThat(names(owner, "q", "chi\t hoa")).containsExactly("Chị Hoa");
        // "%", "_" là chữ thường, không phải ký tự đại diện
        assertThat(names(owner, "q", "%")).isEmpty();
        assertThat(names(owner, "q", "không có")).isEmpty();
        // q trống thì bỏ qua
        assertThat(page(owner, "q", "  ").get("total").asLong()).isEqualTo(3);
    }

    @Test
    void likeWildcardsInTheQueryMatchLiterally() throws Exception {
        Session owner = newStore();
        createOk(owner, "0912000001", "Hàng_A", null);
        createOk(owner, "0912000002", "HàngXA", null);
        createOk(owner, "0912000003", "Sạp\\B", null);
        createOk(owner, "0912000004", "SạpB", null);

        // "_" không phải ký tự đại diện một chữ, "\" không phải ký tự thoát
        assertThat(names(owner, "q", "hang_a")).containsExactly("Hàng_A");
        assertThat(names(owner, "q", "_")).containsExactly("Hàng_A");
        assertThat(names(owner, "q", "sap\\b")).containsExactly("Sạp\\B");
        assertThat(names(owner, "q", "\\")).containsExactly("Sạp\\B");
        assertThat(names(owner, "q", "hang")).containsExactly("Hàng_A", "HàngXA");
    }

    @Test
    void phoneMatchesPartiallyComparingDigitsOnly() throws Exception {
        Session owner = newStore();
        createOk(owner, "0912345678", "Chị Hoa", null);
        createOk(owner, "0987654321", "Anh Ba", null);

        assertThat(names(owner, "q", "345 678")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "345678")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "0912.345")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "0912-345-678")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "+84 912")).containsExactly("Chị Hoa");
        assertThat(names(owner, "q", "+84987654321")).containsExactly("Anh Ba");
        assertThat(names(owner, "q", "09")).containsExactly("Anh Ba", "Chị Hoa");
        assertThat(names(owner, "q", "111")).isEmpty();
        // Có chữ cái là tìm theo tên: chữ số trong đó không so với số điện thoại
        assertThat(names(owner, "q", "Hoa 345")).isEmpty();
    }

    @Test
    void listIsSortedByNameThenPhoneAndPaged() throws Exception {
        Session owner = newStore();
        createOk(owner, "0900000007", null, null);
        createOk(owner, "0900000001", "Chị Hoa", null);
        createOk(owner, "0900000002", "Bảo", null);
        createOk(owner, "0900000006", null, null);
        createOk(owner, "0900000003", "anh Ba", null);
        createOk(owner, "0900000005", "Bao", null);
        createOk(owner, "0900000004", "Ánh Dương", null);

        JsonNode all = page(owner);
        assertThat(all.get("total").asLong()).isEqualTo(7);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);
        List<String> order = new ArrayList<>();
        all.get("items").forEach(item -> order.add(item.get("phone").asString()));
        // Tên không dấu, không phân biệt hoa thường; cùng tên thì theo số; chưa có tên xếp sau, theo số
        assertThat(order).containsExactly("0900000003", "0900000004", "0900000002", "0900000005", "0900000001",
                "0900000006", "0900000007");

        JsonNode second = page(owner, "page", "1", "size", "3");
        assertThat(second.get("total").asLong()).isEqualTo(7);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(3);
        assertThat(second.get("items").get(0).get("phone").asString()).isEqualTo("0900000005");
        assertThat(page(owner, "size", "500").get("size").asInt()).isEqualTo(100);
    }

    @Test
    void softDeletedCustomersAreInvisibleAndFreeTheirPhone() throws Exception {
        Session owner = newStore();
        UUID deleted = insertSoftDeleted(owner.storeId(), "0912345678", "Đã xóa");

        assertThat(page(owner).get("total").asLong()).isZero();
        send(owner, get(CUSTOMERS + "/" + deleted), null).andExpect(status().isNotFound());
        update(owner, deleted, "0912345678", "Sửa", null).andExpect(status().isNotFound());
        assertThat(TenantScope.runAs(owner.storeId(), () -> lookup.findByPhone("0912345678"))).isEmpty();
        assertThat(TenantScope.runAs(owner.storeId(), () -> lookup.findById(deleted))).isEmpty();

        // Số của Khách hàng đã xóa mềm dùng lại được
        UUID fresh = createOk(owner, "0912345678", "Khách mới", null);
        assertThat(TenantScope.runAs(owner.storeId(), () -> lookup.findByPhone("0912345678")))
                .map(CustomerSummary::customerId).contains(fresh);
    }

    // --- Tách dữ liệu giữa Cửa hàng --------------------------------------------------------------------------

    @Test
    void anotherStoresCustomerIsNotFoundAndStaysUnchanged() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofB = createOk(b, "0912345678", "Khách của B", null);
        createOk(a, "0987654321", "Khách của A", null);

        send(a, get(CUSTOMERS + "/" + ofB), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        update(a, ofB, "0912345678", "Đổi bởi A", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        assertThat(name(b.storeId(), ofB)).isEqualTo("Khách của B");
        assertThat(names(a)).containsExactly("Khách của A");
        assertThat(names(a, "q", "0912345678")).isEmpty();

        UUID missing = UUID.randomUUID();
        send(a, get(CUSTOMERS + "/" + missing), null).andExpect(status().isNotFound());
        update(a, missing, "0912345678", null, null).andExpect(status().isNotFound());

        // Dữ liệu sai được báo trước 404, kể cả với id của Cửa hàng khác hay id lạ
        for (UUID id : List.of(ofB, missing)) {
            update(a, id, "0812", "Đổi bởi A", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("phone"));
        }
        assertThat(name(b.storeId(), ofB)).isEqualTo("Khách của B");
    }

    // --- CustomerLookup --------------------------------------------------------------------------------------

    @Test
    void customerLookupFindsOnlyTheCurrentStoresCustomers() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofA = createOk(a, "0912345678", "Chị Hoa", null);
        UUID ofB = createOk(b, "0912345678", "Khách của B", null);
        UUID nameless = createOk(a, "0987654321", null, null);

        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findByPhone("+84912345678")))
                .contains(new CustomerSummary(ofA, "Chị Hoa", "0912345678"));
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findByPhone("0912 345.678")))
                .map(CustomerSummary::customerId).contains(ofA);
        assertThat(TenantScope.runAs(b.storeId(), () -> lookup.findByPhone("0912345678")))
                .map(CustomerSummary::customerId).contains(ofB);
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(ofA)))
                .contains(new CustomerSummary(ofA, "Chị Hoa", "0912345678"));
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(nameless)))
                .contains(new CustomerSummary(nameless, null, "0987654321"));

        // Của Cửa hàng khác, id lạ, số sai hoặc null: rỗng
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(ofB))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(UUID.randomUUID()))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(null))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findByPhone("0911111111"))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findByPhone("0812"))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findByPhone(null))).isEmpty();

        // Gọi trong transaction của bên gọi cũng được
        Optional<CustomerSummary> inCallerTx = asStore(a.storeId(), () -> lookup.findByPhone("0912345678"));
        assertThat(inCallerTx).map(CustomerSummary::customerId).contains(ofA);

        // Không có ngữ cảnh Cửa hàng: lỗi lập trình
        assertThatThrownBy(() -> lookup.findByPhone("0912345678")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lookup.findByPhone(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lookup.findByPhone("0812")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lookup.findById(null)).isInstanceOf(IllegalStateException.class);
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Số điện thoại 10 chữ số chưa dùng trong lần chạy này. */
    static String newPhone() {
        return "09" + PHONES.incrementAndGet();
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả phiên của Chủ cửa hàng. */
    Session newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Khách hàng");
        body.put("loginKey", "khachhang." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        UUID storeId = UUID.fromString(json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("storeId").asString());
        return new Session(response.getCookie("access_token"), storeId);
    }

    /** Nhân viên dev của Cửa hàng A. */
    Session staff() throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"dev.nv.a\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Session(cookie, UUID.fromString(json.readTree(me).get("storeId").asString()));
    }

    String json(Map<String, ?> body) {
        return json.writeValueAsString(body);
    }

    ResultActions send(Session session, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.cookie(session.cookie());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    UUID createOk(Session session, String phone, String name, String note) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("name", name);
        body.put("note", note);
        String response = send(session, post(CUSTOMERS), json(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return idOf(response);
    }

    ResultActions update(Session session, UUID id, String phone, String name, String note) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("name", name);
        body.put("note", note);
        return send(session, put(CUSTOMERS + "/" + id), json(body));
    }

    UUID idOf(String body) {
        return UUID.fromString(json.readTree(body).get("customerId").asString());
    }

    JsonNode page(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(CUSTOMERS);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String response = send(session, request, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    /** Tên của các dòng khớp, theo thứ tự trả về ({@code null} khi chưa có tên). */
    List<String> names(Session session, String... params) throws Exception {
        List<String> names = new ArrayList<>();
        page(session, params).get("items").forEach(item ->
                names.add(item.get("name").isNull() ? null : item.get("name").asString()));
        return names;
    }

    List<String> phones(Session session) {
        return asStore(session.storeId(), () -> jdbc.queryForList("select phone from app.customer", String.class));
    }

    UUID insertSoftDeleted(UUID storeId, String phone, String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        asStore(storeId, () -> jdbc.update("""
                insert into app.customer (id, store_id, name, phone, created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, storeId, name, phone, at, at, at));
        return id;
    }

    String name(UUID storeId, UUID id) {
        return asStore(storeId, () -> jdbc.queryForObject("select name from app.customer where id = ?", String.class,
                id));
    }

    String note(UUID storeId, UUID id) {
        return asStore(storeId, () -> jdbc.queryForObject("select note from app.customer where id = ?", String.class,
                id));
    }

    OffsetDateTime updatedAt(UUID storeId, UUID id) {
        return asStore(storeId, () -> jdbc.queryForObject("select updated_at from app.customer where id = ?",
                OffsetDateTime.class, id)).withOffsetSameInstant(ZoneOffset.UTC);
    }

    long customerCount(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.customer", Long.class));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
