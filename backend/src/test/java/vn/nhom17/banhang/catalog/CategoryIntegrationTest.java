package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Quản lý Danh mục (FR-4, SP-04) trên PostgreSQL thật: toàn bộ ma trận I/O của spec. Mỗi test dùng Cửa hàng mới đăng
 * ký riêng, nên danh sách và số đếm không phụ thuộc test khác chạy chung database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryIntegrationTest {

    static final String CATEGORIES = "/api/v1/catalog/categories";
    static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** Mốc cũ cho {@code product.changed_at}, để thấy rõ đổi tên Danh mục làm cột này tăng. */
    static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;

    /** Chủ cửa hàng của một Cửa hàng mới đăng ký. */
    record Owner(Cookie cookie, UUID storeId) {
    }

    // --- Thêm ------------------------------------------------------------------------------------------------

    @Test
    void createTrimsTheNameAndStartsWithNoProducts() throws Exception {
        Owner owner = newStore();
        String body = create(owner, "  Đồ uống ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_REGEX)))
                .andExpect(jsonPath("$.name").value("Đồ uống"))
                .andExpect(jsonPath("$.productCount").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());

        Map<String, Object> row = asStore(owner.storeId(), () ->
                jdbc.queryForMap("select name, store_id from app.category where id = ?", id));
        assertThat(row).containsEntry("name", "Đồ uống").containsEntry("store_id", owner.storeId());

        // Khoảng trắng không ngắt dòng ở hai đầu cũng bị cắt
        create(owner, "\u00a0Bia ").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Bia"));
    }

    @Test
    void duplicateNameIgnoringCaseIsRejectedWithinTheStoreOnly() throws Exception {
        Owner owner = newStore();
        createOk(owner, "Đồ uống");

        create(owner, "đồ UỐNG")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/category-name-taken"))
                .andExpect(jsonPath("$.requestId").exists());
        create(owner, "  ĐỒ UỐNG  ").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/category-name-taken"));
        assertThat(categoryCount(owner.storeId())).isEqualTo(1);

        // Cửa hàng khác dùng cùng tên thì được
        createOk(newStore(), "Đồ uống");
    }

    @Test
    void nameIsRequiredAndAtMost100Characters() throws Exception {
        Owner owner = newStore();
        List<String> invalid = List.of("{}", "{\"name\":null}", nameBody(""), nameBody("   "),
                nameBody("\u00a0　\t"), nameBody("a".repeat(101)), nameBody("ệ".repeat(101)));
        for (String body : invalid) {
            send(owner, post(CATEGORIES), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        }
        assertThat(categoryCount(owner.storeId())).isZero();

        // Đúng 100 ký tự sau khi cắt khoảng trắng; chữ có dấu gõ dạng tổ hợp (NFD) được đếm như một ký tự
        createOk(owner, "a".repeat(100));
        create(owner, " " + "b".repeat(100) + " ").andExpect(status().isCreated());
        create(owner, "ệ".repeat(100))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ệ".repeat(100)));
        assertThat(categoryCount(owner.storeId())).isEqualTo(3);

        // Đổi tên cũng kiểm như vậy và không đổi gì
        UUID id = createOk(owner, "Bia");
        rename(owner, id, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        rename(owner, id, "c".repeat(101)).andExpect(status().isBadRequest());
        assertThat(categoryName(owner.storeId(), id)).isEqualTo("Bia");
    }

    @Test
    void controlCharactersAreRejectedAndInvisibleFormatCharactersAreRemoved() throws Exception {
        Owner owner = newStore();
        for (String name : List.of("Bia\u0000", "Bi\ta", "Đồ\nuống", "Rượu\u007f")) {
            create(owner, name)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].message").value("Tên Danh mục có ký tự không hợp lệ"));
        }
        // Chỉ toàn ký tự vô hình thì coi như tên trống
        create(owner, "\u200b\ufeff\u2060").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Nhập tên Danh mục"));
        assertThat(categoryCount(owner.storeId())).isZero();

        // Ký tự vô hình bị bỏ trước khi lưu, nên tên trông giống hệt vẫn bị bắt trùng
        UUID drinks = createOk(owner, "\ufeffĐồ\u200b uống\u2060");
        assertThat(categoryName(owner.storeId(), drinks)).isEqualTo("Đồ uống");
        create(owner, "Đồ uống\u200b").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/category-name-taken"));
        rename(owner, drinks, "Đồ\tuống").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        assertThat(categoryName(owner.storeId(), drinks)).isEqualTo("Đồ uống");
    }

    // --- Đổi tên -----------------------------------------------------------------------------------------------

    @Test
    void renameUpdatesTheChangedAtOfEveryProductInTheCategory() throws Exception {
        Owner owner = newStore();
        UUID drinks = createOk(owner, "Đồ uống");
        UUID snacks = createOk(owner, "Bánh kẹo");
        UUID active = insertProduct(owner.storeId(), drinks, "ACTIVE", false);
        UUID inactive = insertProduct(owner.storeId(), drinks, "INACTIVE", false);
        UUID softDeleted = insertProduct(owner.storeId(), drinks, "ACTIVE", true);
        UUID other = insertProduct(owner.storeId(), snacks, "ACTIVE", false);

        rename(owner, drinks, " Nước giải khát ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(drinks.toString()))
                .andExpect(jsonPath("$.name").value("Nước giải khát"))
                .andExpect(jsonPath("$.productCount").value(3));

        assertThat(categoryName(owner.storeId(), drinks)).isEqualTo("Nước giải khát");
        for (UUID product : List.of(active, inactive, softDeleted)) {
            assertThat(changedAt(owner.storeId(), product)).as("changed_at của " + product).isAfter(LONG_AGO);
        }
        assertThat(changedAt(owner.storeId(), other)).isEqualTo(LONG_AGO);

        // Đổi chữ hoa, chữ thường của chính nó không tính là trùng
        rename(owner, drinks, "nước giải khát").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("nước giải khát"));
    }

    @Test
    void renameToTheSameNormalizedNameChangesNothing() throws Exception {
        Owner owner = newStore();
        UUID drinks = createOk(owner, "Đồ uống");
        UUID product = insertProduct(owner.storeId(), drinks, "ACTIVE", false);
        String decomposed = Normalizer.normalize("Đồ uống", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("Đồ uống");

        for (String sameName : List.of(" Đồ uống ", decomposed, "\u00a0" + decomposed + "\u200b")) {
            rename(owner, drinks, sameName)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Đồ uống"))
                    .andExpect(jsonPath("$.productCount").value(1));
        }
        assertThat(categoryName(owner.storeId(), drinks)).isEqualTo("Đồ uống");
        assertThat(changedAt(owner.storeId(), product)).isEqualTo(LONG_AGO);
    }

    @Test
    void renameToATakenNameIsRejectedAndChangesNothing() throws Exception {
        Owner owner = newStore();
        createOk(owner, "Bia");
        UUID wine = createOk(owner, "Rượu");
        UUID product = insertProduct(owner.storeId(), wine, "ACTIVE", false);

        rename(owner, wine, "BIA")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/category-name-taken"));
        assertThat(categoryName(owner.storeId(), wine)).isEqualTo("Rượu");
        assertThat(changedAt(owner.storeId(), product)).isEqualTo(LONG_AGO);
    }

    // --- Xóa -------------------------------------------------------------------------------------------------

    @Test
    void deletingAnEmptyCategoryRemovesIt() throws Exception {
        Owner owner = newStore();
        UUID id = createOk(owner, "Văn phòng phẩm");
        createOk(owner, "Bia");

        send(owner, delete(CATEGORIES + "/" + id), null).andExpect(status().isNoContent());
        assertThat(categoryCount(owner.storeId())).isEqualTo(1);
        list(owner, "").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Bia"));

        send(owner, delete(CATEGORIES + "/" + id), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
    }

    @Test
    void deletingACategoryThatStillHasProductsIsRejected() throws Exception {
        Owner owner = newStore();
        UUID withInactive = createOk(owner, "Hàng ngừng bán");
        insertProduct(owner.storeId(), withInactive, "INACTIVE", false);
        UUID withSoftDeleted = createOk(owner, "Hàng đã xóa");
        insertProduct(owner.storeId(), withSoftDeleted, "ACTIVE", true);

        for (UUID id : List.of(withInactive, withSoftDeleted)) {
            send(owner, delete(CATEGORIES + "/" + id), null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("/errors/catalog/category-in-use"));
        }
        assertThat(categoryCount(owner.storeId())).isEqualTo(2);
        assertThat(categoryName(owner.storeId(), withInactive)).isEqualTo("Hàng ngừng bán");
    }

    // --- Danh sách -------------------------------------------------------------------------------------------

    @Test
    void listIsSortedByNameWithoutAccentsOrCaseAndCountsProducts() throws Exception {
        Owner owner = newStore();
        UUID drinks = createOk(owner, "Đồ uống");
        createOk(owner, "bánh kẹo");
        createOk(owner, "Áo");
        createOk(owner, "cà phê");
        UUID beer = createOk(owner, "Bia");
        insertProduct(owner.storeId(), drinks, "ACTIVE", false);
        insertProduct(owner.storeId(), drinks, "INACTIVE", false);
        insertProduct(owner.storeId(), beer, "ACTIVE", false);
        insertProduct(owner.storeId(), beer, "ACTIVE", true);

        JsonNode page = json.readTree(list(owner, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        page.get("items").forEach(item -> {
            names.add(item.get("name").asString());
            counts.add(item.get("productCount").asLong());
        });
        assertThat(names).containsExactly("Áo", "bánh kẹo", "Bia", "cà phê", "Đồ uống");
        // Mọi Sản phẩm thuộc Danh mục đều tính: "Đồ uống" có một Sản phẩm ngừng kinh doanh, "Bia" một đã xóa mềm
        assertThat(counts).containsExactly(0L, 0L, 2L, 0L, 2L);

        list(owner, "?page=1&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Bia"))
                .andExpect(jsonPath("$.items[1].name").value("cà phê"));
        list(owner, "?size=500").andExpect(jsonPath("$.size").value(100));
    }

    // --- Tách dữ liệu giữa Cửa hàng và phân quyền --------------------------------------------------------------

    @Test
    void anotherStoresCategoryIsNotFoundAndStaysUnchanged() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID ofB = createOk(b, "Hàng của B");
        createOk(a, "Hàng của A");

        rename(a, ofB, "Đổi bởi A")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        send(a, delete(CATEGORIES + "/" + ofB), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        assertThat(categoryName(b.storeId(), ofB)).isEqualTo("Hàng của B");
        list(a, "").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Hàng của A"));

        UUID missing = UUID.randomUUID();
        rename(a, missing, "Không có").andExpect(status().isNotFound());
        send(a, delete(CATEGORIES + "/" + missing), null).andExpect(status().isNotFound());
    }

    @Test
    void staffCanListButCannotWrite() throws Exception {
        Cookie staff = login("dev.nv.a");
        mvc.perform(get(CATEGORIES).cookie(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());

        String body = nameBody("Nhân viên thêm");
        String path = CATEGORIES + "/" + UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(post(CATEGORIES), put(path), delete(path))) {
            mvc.perform(request.cookie(staff).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        }
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử Danh mục");
        body.put("loginKey", "danhmuc." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        UUID storeId = UUID.fromString(json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("storeId").asString());
        return new Owner(response.getCookie("access_token"), storeId);
    }

    Cookie login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    String nameBody(String name) {
        return json.writeValueAsString(Map.of("name", name));
    }

    ResultActions send(Owner owner, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.cookie(owner.cookie());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    ResultActions create(Owner owner, String name) throws Exception {
        return send(owner, post(CATEGORIES), nameBody(name));
    }

    UUID createOk(Owner owner, String name) throws Exception {
        String body = create(owner, name).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    ResultActions rename(Owner owner, UUID id, String name) throws Exception {
        return send(owner, put(CATEGORIES + "/" + id), nameBody(name));
    }

    ResultActions list(Owner owner, String query) throws Exception {
        return send(owner, get(CATEGORIES + query), null);
    }

    /** Sản phẩm tối thiểu (API thêm Sản phẩm là SP-01), {@code changed_at} đặt về {@link #LONG_AGO}. */
    UUID insertProduct(UUID storeId, UUID categoryId, String status, boolean softDeleted) {
        UUID id = UUID.randomUUID();
        asStore(storeId, () -> jdbc.update("""
                insert into app.product (id, store_id, category_id, name, status, changed_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, storeId, categoryId, "Sản phẩm " + id, status, LONG_AGO, softDeleted ? LONG_AGO : null));
        return id;
    }

    OffsetDateTime changedAt(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForObject("select changed_at from app.product where id = ?",
                OffsetDateTime.class, productId)).withOffsetSameInstant(ZoneOffset.UTC);
    }

    String categoryName(UUID storeId, UUID categoryId) {
        return asStore(storeId, () -> jdbc.queryForObject("select name from app.category where id = ?", String.class,
                categoryId));
    }

    long categoryCount(UUID storeId) {
        return asStore(storeId, () -> jdbc.queryForObject("select count(*) from app.category", Long.class));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
