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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import vn.nhom17.banhang.catalog.SupplierLookup.SupplierSummary;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Quản lý Nhà cung cấp (FR-23, KHO-02) trên PostgreSQL thật: ma trận I/O của spec cho REST
 * {@code /api/v1/catalog/suppliers} và {@link SupplierLookup} gọi thẳng dưới {@link TenantScope} như {@code inventory}
 * sẽ gọi. Phần lớn test dùng Cửa hàng mới đăng ký riêng; test của Nhân viên dùng Cửa hàng dev A với mã ngẫu nhiên, nên
 * không phụ thuộc test khác chạy chung database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SupplierIntegrationTest {

    static final String SUPPLIERS = "/api/v1/catalog/suppliers";
    static final String CODE_TAKEN = "/errors/catalog/supplier-code-taken";
    static final String CODE_INVALID =
            "Mã Nhà cung cấp chỉ gồm chữ cái không dấu, chữ số, dấu - và dấu _, bắt đầu bằng chữ cái hoặc chữ số";
    static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired SupplierLookup lookup;

    /** Người dùng đã đăng nhập và Cửa hàng của họ. */
    record Session(Cookie cookie, UUID storeId) {
    }

    // --- Thêm ------------------------------------------------------------------------------------------------

    @Test
    void ownerAddsASupplierAndSeesItInTheListRightAway() throws Exception {
        Session owner = newStore();

        String body = send(owner, post(SUPPLIERS),
                json(Map.of("name", "Công ty Vinamilk", "code", "vnm", "phone", "028 1234 5678")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(SUPPLIERS + "/" + UUID_REGEX)))
                .andExpect(jsonPath("$.supplierId").value(matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.code").value("VNM"))
                .andExpect(jsonPath("$.name").value("Công ty Vinamilk"))
                .andExpect(jsonPath("$.phone").value("028 1234 5678"))
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = idOf(body);
        assertThat(json.readTree(body).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("supplierId", "code", "name", "phone", "address", "note");

        // Có ngay trong bảng (AC)
        JsonNode page = page(owner);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0)).isEqualTo(json.readTree(body));

        // GET /{id} trả đúng như lúc tạo
        String fetched = send(owner, get(SUPPLIERS + "/" + id), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(fetched)).isEqualTo(json.readTree(body));

        Map<String, Object> row = asStore(owner.storeId(), () ->
                jdbc.queryForMap("select code, name, phone, address, note, store_id from app.supplier where id = ?",
                        id));
        assertThat(row).containsEntry("code", "VNM").containsEntry("store_id", owner.storeId())
                .containsEntry("address", null).containsEntry("note", null);
    }

    @Test
    void allFieldsAreNormalizedAndOptionalOnesMayBeBlank() throws Exception {
        Session owner = newStore();
        String body = send(owner, post(SUPPLIERS), json(full("  Đại lý  Hồng Hà ", " ncc-a_01 ", " 0283 711 0002 ",
                " 88 Lý Thường Kiệt ", "Giao sáng\r\nthứ Hai")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Đại lý  Hồng Hà"))
                .andExpect(jsonPath("$.code").value("NCC-A_01"))
                .andExpect(jsonPath("$.phone").value("0283 711 0002"))
                .andExpect(jsonPath("$.address").value("88 Lý Thường Kiệt"))
                .andExpect(jsonPath("$.note").value("Giao sáng\nthứ Hai"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(idOf(body)).isNotNull();

        // Chỉ có tên; trường tùy chọn chỉ có khoảng trắng hay ký tự vô hình là trống
        send(owner, post(SUPPLIERS), json(full("Chợ đầu mối", "  ", "​", " 　", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());
        // Nhiều Nhà cung cấp không có mã: unique chỉ áp cho mã có giá trị
        createOk(owner, Map.of("name", "Chợ đầu mối 2"));

        // Đúng giới hạn sau khi chuẩn hóa: chữ gõ dạng tổ hợp (NFD) là một ký tự
        String decomposed = Normalizer.normalize("ệ".repeat(200), Normalizer.Form.NFD);
        UUID atLimit = createOk(owner, full(decomposed, "C".repeat(30), "9".repeat(20), "đ".repeat(300),
                "n".repeat(500)));
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select name from app.supplier where id = ?", String.class, atLimit))).isEqualTo("ệ".repeat(200));
        assertThat(supplierCount(owner.storeId())).isEqualTo(4);
    }

    @Test
    void aDuplicateCodeIsRejectedIgnoringCase() throws Exception {
        Session owner = newStore();
        UUID existing = createOk(owner, Map.of("name", "Công ty Vinamilk", "code", "vnm"));

        for (String sameCode : List.of("VNM", "vnm", " Vnm ")) {
            send(owner, post(SUPPLIERS), json(Map.of("name", "Nhà cung cấp khác", "code", sameCode)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(CODE_TAKEN))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        assertThat(supplierCount(owner.storeId())).isEqualTo(1);
        assertThat(code(owner.storeId(), existing)).isEqualTo("VNM");
    }

    @Test
    void duplicateNamesAreAllowed() throws Exception {
        Session owner = newStore();
        UUID first = createOk(owner, Map.of("name", "Công ty Vinamilk", "code", "VNM-HN"));
        UUID second = createOk(owner, Map.of("name", "Công ty Vinamilk", "code", "VNM-HCM"));
        UUID third = createOk(owner, Map.of("name", "công ty vinamilk"));
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        assertThat(supplierCount(owner.storeId())).isEqualTo(3);
    }

    @Test
    void anotherStoreMayUseTheSameCode() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofB = createOk(b, Map.of("name", "Vinamilk của B", "code", "VNM"));

        UUID ofA = createOk(a, Map.of("name", "Vinamilk của A", "code", "VNM"));
        assertThat(ofA).isNotEqualTo(ofB);
        assertThat(supplierCount(a.storeId())).isEqualTo(1);
        assertThat(supplierCount(b.storeId())).isEqualTo(1);
    }

    @Test
    void aMissingNameIsRejectedOnTheNameField() throws Exception {
        Session owner = newStore();
        for (String body : List.of("{}", "{\"name\":null,\"code\":\"VNM\"}", json(Map.of("name", "")),
                json(Map.of("name", "   ")), json(Map.of("name", "​")))) {
            send(owner, post(SUPPLIERS), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].message").value("Nhập tên Nhà cung cấp"));
        }
        assertThat(supplierCount(owner.storeId())).isZero();
    }

    @Test
    void aCodeWithOtherCharactersIsRejectedOnTheCodeField() throws Exception {
        Session owner = newStore();
        for (String code : List.of("VN M!", "VN M", "VNM!", "NCC.01", "MÃ01", "ＶＮＭ", "VN/M", "VN\tM", "-", "_",
                "---", "-VNM", "_01")) {
            send(owner, post(SUPPLIERS), json(Map.of("name", "Công ty Vinamilk", "code", code)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
        }
        send(owner, post(SUPPLIERS), json(Map.of("name", "Công ty Vinamilk", "code", "VN M!")))
                .andExpect(jsonPath("$.errors[0].message").value(CODE_INVALID));
        assertThat(supplierCount(owner.storeId())).isZero();
        // "-", "_" được ở sau ký tự đầu
        createOk(owner, Map.of("name", "Công ty Vinamilk", "code", "0-vnm_01"));
        assertThat(codes(owner)).containsExactly("0-VNM_01");
    }

    @Test
    void aPhoneMustBeDigitsWithSeparatorsOnly() throws Exception {
        Session owner = newStore();
        Map<String, String> rejected = Map.of(
                "028 1234 abc", "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )",
                "Liên hệ chị Hoa", "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )",
                "028 1234 5678 😀", "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )",
                "028/1234", "Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )",
                "+ - . ( )", "Số điện thoại phải có chữ số");
        for (Map.Entry<String, String> phone : rejected.entrySet()) {
            send(owner, post(SUPPLIERS), json(Map.of("name", "Công ty Vinamilk", "phone", phone.getKey())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(1))
                    .andExpect(jsonPath("$.errors[0].field").value("phone"))
                    .andExpect(jsonPath("$.errors[0].message").value(phone.getValue()));
        }
        assertThat(supplierCount(owner.storeId())).isZero();

        for (String phone : List.of("(028) 1234-5678", "+84 28.1234.5678", "02837110001")) {
            send(owner, post(SUPPLIERS), json(Map.of("name", "Nhà cung cấp " + phone, "phone", phone)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.phone").value(phone));
        }
    }

    @Test
    void everyFieldOverItsLimitIsReportedAtOnce() throws Exception {
        Session owner = newStore();
        String body = send(owner, post(SUPPLIERS), json(full("a".repeat(201), "C".repeat(31), "9".repeat(21),
                "đ".repeat(301), "n".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(5))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> messages = new LinkedHashMap<>();
        json.readTree(body).get("errors").forEach(e -> messages.put(e.get("field").asString(),
                e.get("message").asString()));
        assertThat(messages).containsExactlyInAnyOrderEntriesOf(Map.of(
                "name", "Tên Nhà cung cấp tối đa 200 ký tự",
                "code", "Mã Nhà cung cấp tối đa 30 ký tự",
                "phone", "Số điện thoại tối đa 20 ký tự",
                "address", "Địa chỉ tối đa 300 ký tự",
                "note", "Ghi chú tối đa 500 ký tự"));

        // Ký tự điều khiển: trường một dòng từ chối xuống dòng, ghi chú thì được
        send(owner, post(SUPPLIERS), json(full("Công ty\nVinamilk", null, "028\t1234", "Dòng 1\nDòng 2", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        assertThat(supplierCount(owner.storeId())).isZero();
    }

    // --- Sửa -------------------------------------------------------------------------------------------------

    @Test
    void updateReplacesTheWholeSupplier() throws Exception {
        Session owner = newStore();
        UUID id = createOk(owner, full("Công ty Vinamilk", "VNM", "028 1234 5678", "Quận 7", "Giao thứ Hai"));
        OffsetDateTime createdUpdatedAt = updatedAt(owner.storeId(), id);

        update(owner, id, full("Công ty CP Sữa Việt Nam", "vnm-01", "028 9999 0000", "Quận 1", "Giao thứ Ba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(id.toString()))
                .andExpect(jsonPath("$.code").value("VNM-01"))
                .andExpect(jsonPath("$.name").value("Công ty CP Sữa Việt Nam"))
                .andExpect(jsonPath("$.phone").value("028 9999 0000"))
                .andExpect(jsonPath("$.address").value("Quận 1"))
                .andExpect(jsonPath("$.note").value("Giao thứ Ba"));
        assertThat(code(owner.storeId(), id)).isEqualTo("VNM-01");
        assertThat(updatedAt(owner.storeId(), id)).isAfter(createdUpdatedAt);

        // Thay toàn bộ: trường tùy chọn thiếu hoặc rỗng thì xóa
        send(owner, put(SUPPLIERS + "/" + id), json(Map.of("name", "Công ty CP Sữa Việt Nam", "note", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());
        Map<String, Object> row = asStore(owner.storeId(), () ->
                jdbc.queryForMap("select code, phone, address, note from app.supplier where id = ?", id));
        assertThat(row).containsEntry("code", null).containsEntry("phone", null).containsEntry("address", null)
                .containsEntry("note", null);

        // Sai thì không đổi gì
        update(owner, id, Map.of("name", "", "code", "VN M!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject("select name from app.supplier where id = ?",
                String.class, id))).isEqualTo("Công ty CP Sữa Việt Nam");
    }

    @Test
    void updateWithNothingChangedWritesNothing() throws Exception {
        Session owner = newStore();
        UUID id = createOk(owner, full("Công ty Vinamilk", "VNM", "028 1234 5678", null, null));
        OffsetDateTime before = updatedAt(owner.storeId(), id);

        update(owner, id, full(" Công ty Vinamilk ", "vnm", "028 1234 5678 ", "", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("VNM"));
        assertThat(updatedAt(owner.storeId(), id)).isEqualTo(before);
    }

    @Test
    void updateToACodeTakenByAnotherSupplierIsRejected() throws Exception {
        Session owner = newStore();
        createOk(owner, Map.of("name", "Công ty Vinamilk", "code", "VNM"));
        UUID other = createOk(owner, Map.of("name", "Công ty TH", "code", "TH"));

        update(owner, other, Map.of("name", "Công ty TH", "code", "vnm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(CODE_TAKEN));
        assertThat(code(owner.storeId(), other)).isEqualTo("TH");

        // Giữ nguyên mã của chính mình thì được
        update(owner, other, Map.of("name", "Công ty TH True Milk", "code", "th"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TH"));
    }

    // --- Vai trò -----------------------------------------------------------------------------------------------

    @Test
    void staffCanReadButCannotWrite() throws Exception {
        Session ownerA = login("dev.chu.a");
        Session staff = login("dev.nv.a");
        String code = "NV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID id = createOk(ownerA, Map.of("name", "Nhà cung cấp cho Nhân viên xem", "code", code));

        send(staff, get(SUPPLIERS), null).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        assertThat(codes(staff, "q", code)).containsExactly(code);
        send(staff, get(SUPPLIERS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));

        String body = json(Map.of("name", "Nhân viên sửa"));
        for (MockHttpServletRequestBuilder request : List.of(post(SUPPLIERS), put(SUPPLIERS + "/" + id))) {
            send(staff, request, body)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        }
        assertThat(asStore(staff.storeId(), () -> jdbc.queryForObject("select name from app.supplier where id = ?",
                String.class, id))).isEqualTo("Nhà cung cấp cho Nhân viên xem");
        // Không có xóa Nhà cung cấp
        send(ownerA, delete(SUPPLIERS + "/" + id), null).andExpect(status().isMethodNotAllowed());
    }

    // --- Tìm kiếm, danh sách --------------------------------------------------------------------------------

    @Test
    void searchMatchesNameWithoutAccentsCodePrefixOrPartOfThePhone() throws Exception {
        Session owner = newStore();
        createOk(owner, full("Công ty Vinamilk", "VNM", "028 1234 5678", null, null));
        createOk(owner, full("Đại lý Sữa Hồng Hà", "NCC-A02", "0283.711.0002", null, null));
        createOk(owner, full("Chợ đầu mối Bình Điền", null, null, null, null));
        createOk(owner, full("Công ty Phú An", null, "+84 28 3711 0003", null, null));

        assertThat(names(owner, "q", "vinamilk")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "VINAMILK")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "sua hong ha")).containsExactly("Đại lý Sữa Hồng Hà");
        assertThat(names(owner, "q", Normalizer.normalize("Sữa", Normalizer.Form.NFD)))
                .containsExactly("Đại lý Sữa Hồng Hà");
        assertThat(names(owner, "q", "binh dien")).containsExactly("Chợ đầu mối Bình Điền");
        assertThat(names(owner, "q", "  cho\t dau ")).containsExactly("Chợ đầu mối Bình Điền");
        // Mã bắt đầu bằng q, không phân biệt hoa thường; ở giữa mã thì không khớp
        assertThat(names(owner, "q", "vn")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "ncc-a")).containsExactly("Đại lý Sữa Hồng Hà");
        assertThat(names(owner, "q", "A02")).isEmpty();
        // Số điện thoại khớp một phần, chỉ so chữ số
        assertThat(names(owner, "q", "1234 5678")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "12345678")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "711-0002")).containsExactly("Đại lý Sữa Hồng Hà");
        assertThat(names(owner, "q", "999")).isEmpty();
        // 84, +84 ở đầu (mã nước) hiểu như 0, ở cả từ khóa lẫn số đã lưu
        assertThat(names(owner, "q", "028"))
                .containsExactly("Công ty Phú An", "Công ty Vinamilk", "Đại lý Sữa Hồng Hà");
        assertThat(names(owner, "q", "028 3711 0003")).containsExactly("Công ty Phú An");
        assertThat(names(owner, "q", "+84 28 1234")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "84281234")).containsExactly("Công ty Vinamilk");
        assertThat(names(owner, "q", "+84 28 3711 0003")).containsExactly("Công ty Phú An");
        // Có chữ cái là tìm theo tên, mã: chữ số trong đó không so với số điện thoại
        assertThat(names(owner, "q", "Vinamilk 1234")).isEmpty();
        // "%", "_" là chữ thường, không phải ký tự đại diện
        assertThat(names(owner, "q", "%")).isEmpty();
        assertThat(names(owner, "q", "_")).isEmpty();
        // q trống thì bỏ qua
        assertThat(page(owner, "q", "  ").get("total").asLong()).isEqualTo(4);
    }

    @Test
    void codePrefixMatchesLiterallyAndOnlyForCodeLikeQueries() throws Exception {
        Session owner = newStore();
        createOk(owner, Map.of("name", "Kho một", "code", "NCC_01"));
        createOk(owner, Map.of("name", "Kho hai", "code", "NCC-01"));
        createOk(owner, Map.of("name", "Kho ba", "code", "NCCX01"));
        createOk(owner, Map.of("name", "Kho\\Sỉ", "code", "SS01"));

        // "_" trong q là chữ "_", không phải ký tự đại diện một chữ
        assertThat(codes(owner, "q", "NCC_")).containsExactly("NCC_01");
        assertThat(codes(owner, "q", "ncc_0")).containsExactly("NCC_01");
        assertThat(codes(owner, "q", "NCC-")).containsExactly("NCC-01");
        assertThat(codes(owner, "q", "ncc")).containsExactlyInAnyOrder("NCC_01", "NCC-01", "NCCX01");
        // "\" không có trong mã: chỉ so tên, không thoát sai mẫu LIKE
        assertThat(codes(owner, "q", "NCC\\")).isEmpty();
        assertThat(codes(owner, "q", "\\")).containsExactly("SS01");
        assertThat(codes(owner, "q", "kho\\s")).containsExactly("SS01");
        // q không có dạng mã thì không so với đầu mã: "ß" viết HOA là "SS" nhưng không khớp "SS01"
        assertThat(codes(owner, "q", "ß")).isEmpty();
        assertThat(codes(owner, "q", "ss")).containsExactly("SS01");
    }

    @Test
    void listIsSortedByNameWithoutAccentsAndPaged() throws Exception {
        Session owner = newStore();
        createOk(owner, Map.of("name", "Chợ Bình Điền"));
        createOk(owner, Map.of("name", "Bảo An", "code", "B2"));
        createOk(owner, Map.of("name", "anh Ba"));
        createOk(owner, Map.of("name", "Bao An", "code", "B1"));
        createOk(owner, Map.of("name", "Ánh Dương"));

        JsonNode all = page(owner);
        assertThat(all.get("total").asLong()).isEqualTo(5);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);
        List<String> order = new ArrayList<>();
        all.get("items").forEach(item -> order.add(item.get("name").asString()
                + (item.get("code").isNull() ? "" : " " + item.get("code").asString())));
        // Tên không dấu, không phân biệt hoa thường; cùng tên thì theo mã
        assertThat(order).containsExactly("anh Ba", "Ánh Dương", "Bao An B1", "Bảo An B2", "Chợ Bình Điền");

        JsonNode second = page(owner, "page", "1", "size", "2");
        assertThat(second.get("total").asLong()).isEqualTo(5);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("items").get(0).get("code").asString()).isEqualTo("B1");
        assertThat(page(owner, "size", "500").get("size").asInt()).isEqualTo(100);
        assertThat(page(owner, "page", "-1").get("page").asInt()).isZero();

        // sort: tên giảm dần; ngày tạo (mới nhất trước, hoặc cũ nhất trước)
        assertThat(names(owner, "sort", "name,desc"))
                .containsExactly("Chợ Bình Điền", "Bảo An", "Bao An", "Ánh Dương", "anh Ba");
        assertThat(names(owner, "sort", "createdAt,desc"))
                .containsExactly("Ánh Dương", "Bao An", "anh Ba", "Bảo An", "Chợ Bình Điền");
        assertThat(names(owner, "sort", "createdAt"))
                .containsExactly("Chợ Bình Điền", "Bảo An", "anh Ba", "Bao An", "Ánh Dương");
        assertThat(names(owner, "sort", "name,asc")).containsExactly("anh Ba", "Ánh Dương", "Bao An", "Bảo An",
                "Chợ Bình Điền");
        for (String sort : List.of("code", "salePrice", "name,up", "createdAt,desc,x", "Name")) {
            send(owner, get(SUPPLIERS).param("sort", sort), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("sort"))
                    .andExpect(jsonPath("$.errors[0].message")
                            .value("Chỉ sắp xếp được theo name hoặc createdAt, kèm asc hoặc desc"));
        }
    }

    @Test
    void softDeletedSuppliersAreInvisibleAndFreeTheirCode() throws Exception {
        Session owner = newStore();
        UUID deleted = insertSoftDeleted(owner.storeId(), "VNM", "Đã xóa");

        assertThat(page(owner).get("total").asLong()).isZero();
        send(owner, get(SUPPLIERS + "/" + deleted), null).andExpect(status().isNotFound());
        update(owner, deleted, Map.of("name", "Sửa")).andExpect(status().isNotFound());
        assertThat(TenantScope.runAs(owner.storeId(), () -> lookup.findById(deleted))).isEmpty();

        // Mã của Nhà cung cấp đã xóa mềm dùng lại được
        UUID fresh = createOk(owner, Map.of("name", "Nhà cung cấp mới", "code", "vnm"));
        assertThat(TenantScope.runAs(owner.storeId(), () -> lookup.findById(fresh)))
                .contains(new SupplierSummary(fresh, "VNM", "Nhà cung cấp mới"));
    }

    // --- Tách dữ liệu giữa Cửa hàng --------------------------------------------------------------------------

    @Test
    void anotherStoresSupplierIsNotFoundAndStaysUnchanged() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofB = createOk(b, full("Vinamilk của B", "VNM", "028 1234 5678", null, null));
        createOk(a, Map.of("name", "Nhà cung cấp của A"));

        send(a, get(SUPPLIERS + "/" + ofB), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        update(a, ofB, Map.of("name", "Đổi bởi A"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        assertThat(asStore(b.storeId(), () -> jdbc.queryForObject("select name from app.supplier where id = ?",
                String.class, ofB))).isEqualTo("Vinamilk của B");
        assertThat(names(a)).containsExactly("Nhà cung cấp của A");
        assertThat(names(a, "q", "vinamilk")).isEmpty();
        assertThat(names(a, "q", "VNM")).isEmpty();
        assertThat(names(a, "q", "1234")).isEmpty();

        UUID missing = UUID.randomUUID();
        send(a, get(SUPPLIERS + "/" + missing), null).andExpect(status().isNotFound());
        update(a, missing, Map.of("name", "Không có")).andExpect(status().isNotFound());

        // Dữ liệu sai được báo trước 404, kể cả với id của Cửa hàng khác hay id lạ
        for (UUID id : List.of(ofB, missing)) {
            update(a, id, Map.of("name", "", "code", "VNM"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"));
        }
    }

    // --- SupplierLookup --------------------------------------------------------------------------------------

    @Test
    void supplierLookupFindsOnlyTheCurrentStoresSuppliers() throws Exception {
        Session a = newStore();
        Session b = newStore();
        UUID ofA = createOk(a, full("Công ty Vinamilk", "vnm", "028 1234 5678", "Quận 7", null));
        UUID codeless = createOk(a, Map.of("name", "Chợ đầu mối"));
        UUID ofB = createOk(b, Map.of("name", "Vinamilk của B", "code", "VNM"));

        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(ofA)))
                .contains(new SupplierSummary(ofA, "VNM", "Công ty Vinamilk"));
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(codeless)))
                .contains(new SupplierSummary(codeless, null, "Chợ đầu mối"));
        assertThat(TenantScope.runAs(b.storeId(), () -> lookup.findById(ofB)))
                .map(SupplierSummary::supplierId).contains(ofB);

        // Của Cửa hàng khác, id lạ, null: rỗng
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(ofB))).isEmpty();
        assertThat(TenantScope.runAs(b.storeId(), () -> lookup.findById(ofA))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(UUID.randomUUID()))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.findById(null))).isEmpty();

        // Gọi trong transaction của bên gọi cũng được
        Optional<SupplierSummary> inCallerTx = asStore(a.storeId(), () -> lookup.findById(ofA));
        assertThat(inCallerTx).map(SupplierSummary::code).contains("VNM");

        // Không có ngữ cảnh Cửa hàng: lỗi lập trình
        assertThatThrownBy(() -> lookup.findById(ofA)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lookup.findById(null)).isInstanceOf(IllegalStateException.class);
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Đăng ký Cửa hàng mới (FR-43), trả phiên của Chủ cửa hàng. */
    Session newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Nhà cung cấp");
        body.put("loginKey", "nhacungcap." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
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

    /** Tài khoản dev ({@code dev.chu.a}, {@code dev.nv.a}…). */
    Session login(String loginKey) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        String me = mvc.perform(get("/api/v1/platform/me").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new Session(cookie, UUID.fromString(json.readTree(me).get("storeId").asString()));
    }

    /** Body đủ năm trường; {@code null} thì gửi {@code null}. */
    static Map<String, String> full(String name, String code, String phone, String address, String note) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("code", code);
        body.put("phone", phone);
        body.put("address", address);
        body.put("note", note);
        return body;
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

    UUID createOk(Session session, Map<String, String> body) throws Exception {
        String response = send(session, post(SUPPLIERS), json(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return idOf(response);
    }

    ResultActions update(Session session, UUID id, Map<String, String> body) throws Exception {
        return send(session, put(SUPPLIERS + "/" + id), json(body));
    }

    UUID idOf(String body) {
        return UUID.fromString(json.readTree(body).get("supplierId").asString());
    }

    JsonNode page(Session session, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(SUPPLIERS);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String response = send(session, request, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    /** Tên của các dòng khớp, theo thứ tự trả về. */
    List<String> names(Session session, String... params) throws Exception {
        List<String> names = new ArrayList<>();
        page(session, params).get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    List<String> codes(Session session, String... params) throws Exception {
        List<String> codes = new ArrayList<>();
        page(session, params).get("items").forEach(item -> codes.add(item.get("code").asString()));
        return codes;
    }

    UUID insertSoftDeleted(UUID storeId, String code, String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        asStore(storeId, () -> jdbc.update("""
                insert into app.supplier (id, store_id, code, name, created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, storeId, code, name, at, at, at));
        return id;
    }

    String code(UUID storeId, UUID id) {
        return asStore(storeId, () -> jdbc.queryForObject("select code from app.supplier where id = ?", String.class,
                id));
    }

    OffsetDateTime updatedAt(UUID storeId, UUID id) {
        return asStore(storeId, () -> jdbc.queryForObject("select updated_at from app.supplier where id = ?",
                OffsetDateTime.class, id)).withOffsetSameInstant(ZoneOffset.UTC);
    }

    long supplierCount(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.supplier", Long.class));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
