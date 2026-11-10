package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
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
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Tìm kiếm, lọc Sản phẩm (FR-5, SP-05) trên PostgreSQL thật: ma trận I/O của spec cho
 * {@code GET /api/v1/catalog/products}, và {@link VariantLookup} gọi thẳng dưới {@link TenantScope} như {@code sales}
 * sẽ gọi. Mỗi test dùng Cửa hàng mới đăng ký riêng, nên kết quả không phụ thuộc test khác chạy chung database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductSearchIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired VariantLookup lookup;

    record Owner(Cookie cookie, UUID storeId) {
    }

    record Created(UUID productId, UUID variantId) {
    }

    // --- q: tên không dấu, không phân biệt hoa thường ------------------------------------------------------------

    @Test
    void nameMatchesWithoutAccentsAndIgnoringCase() throws Exception {
        Owner owner = newStore();
        create(owner, product("Sữa tươi Vinamilk", 15000));
        create(owner, product("Sữa chua Ba Vì", 8000));
        create(owner, product("Nước suối Lavie", 5000));
        create(owner, product("Bánh quy bơ", 30000));

        assertThat(names(owner, "q", "sua tuoi")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "VINAMILK")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "tƯơI vInA")).containsExactly("Sữa tươi Vinamilk");
        // Nhân viên gõ "sua": chỉ còn Sản phẩm có "Sữa" trong tên ("suối" không khớp)
        assertThat(names(owner, "q", "sua")).containsExactly("Sữa chua Ba Vì", "Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "sữa")).containsExactly("Sữa chua Ba Vì", "Sữa tươi Vinamilk");
        // Chữ có dấu gõ dạng tổ hợp (NFD), khoảng trắng hai đầu (cả khoảng trắng không ngắt dòng) đều được chuẩn hóa
        assertThat(names(owner, "q", Normalizer.normalize("  Sữa tươi\u00A0", Normalizer.Form.NFD)))
                .containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "ba vi")).containsExactly("Sữa chua Ba Vì");
        assertThat(names(owner, "q", "không có hàng này")).isEmpty();
        // Chữ dán có tab, xuống dòng, nhiều dấu cách: vẫn là "sua tuoi"
        assertThat(names(owner, "q", "Sữa\ttươi")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "sua\n  tuoi")).containsExactly("Sữa tươi Vinamilk");
    }

    @Test
    void whitespaceRunsInTheNameMatchASingleSpace() throws Exception {
        Owner owner = newStore();
        create(owner, product("Bánh  mì   đôi", 5000));

        assertThat(names(owner, "q", "banh mi doi")).containsExactly("Bánh  mì   đôi");
        assertThat(names(owner, "q", "banh  mi")).containsExactly("Bánh  mì   đôi");
        assertThat(names(owner, "q", "banhmi")).isEmpty();
    }

    @Test
    void anEmptyQueryIsIgnored() throws Exception {
        Owner owner = newStore();
        create(owner, product("Bánh quy bơ", 30000));
        create(owner, product("Nước suối", 5000));

        assertThat(names(owner, "q", "   ")).containsExactly("Bánh quy bơ", "Nước suối");
        assertThat(names(owner, "q", "")).containsExactly("Bánh quy bơ", "Nước suối");
        assertThat(names(owner)).containsExactly("Bánh quy bơ", "Nước suối");
    }

    // --- q: SKU, mã vạch khớp đầu chuỗi --------------------------------------------------------------------------

    @Test
    void skuAndBarcodeMatchFromTheStartIgnoringCase() throws Exception {
        Owner owner = newStore();
        create(owner, product("Sữa tươi Vinamilk", 15000).sku("vnm-1l").barcode("8934673123456"));
        create(owner, product("Nước suối", 5000).sku("NS-500").barcode("8935005801234"));
        create(owner, product("Hàng có mã chữ", 1000).sku("ABC_1").barcode("xyz789"));
        create(owner, product("Hàng mã giống", 1000).sku("ABCX1"));

        assertThat(names(owner, "q", "8934")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "893")).containsExactly("Nước suối", "Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "8934673123456")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "VNM")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "vnm-1")).containsExactly("Sữa tươi Vinamilk");
        assertThat(names(owner, "q", "XYZ")).containsExactly("Hàng có mã chữ");
        // Chỉ khớp đầu chuỗi: giữa mã vạch, giữa SKU thì không
        assertThat(names(owner, "q", "4673")).isEmpty();
        assertThat(names(owner, "q", "1L")).isEmpty();
        // "_" trong q là chữ thường, không phải ký tự đại diện: không khớp "ABCX1"
        assertThat(names(owner, "q", "abc_")).containsExactly("Hàng có mã chữ");
        assertThat(names(owner, "q", "abc")).containsExactly("Hàng có mã chữ", "Hàng mã giống");
        assertThat(names(owner, "q", "ABCX")).containsExactly("Hàng mã giống");
    }

    // --- q: ký tự đặc biệt của LIKE -----------------------------------------------------------------------------

    @Test
    void likeWildcardsInTheQueryOnlyMatchThemselves() throws Exception {
        Owner owner = newStore();
        create(owner, product("Giảm giá 50% hộp", 1000));
        create(owner, product("Hộp 500ml", 1000));
        create(owner, product("Mã_A", 1000));
        create(owner, product("MãXA", 1000));
        create(owner, product("Kho C:\\hàng", 1000));
        create(owner, product("Kho C hàng", 1000));

        assertThat(names(owner, "q", "50%")).containsExactly("Giảm giá 50% hộp");
        assertThat(names(owner, "q", "%")).containsExactly("Giảm giá 50% hộp");
        assertThat(names(owner, "q", "_")).containsExactly("Mã_A");
        assertThat(names(owner, "q", "ma_a")).containsExactly("Mã_A");
        assertThat(names(owner, "q", "\\")).containsExactly("Kho C:\\hàng");
        assertThat(names(owner, "q", "c:\\h")).containsExactly("Kho C:\\hàng");
        assertThat(names(owner, "q", "%%")).isEmpty();
    }

    // --- Lọc Danh mục, giá, trạng thái ---------------------------------------------------------------------------

    @Test
    void categoryAndPriceRangeIncludeBothEnds() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        UUID snacks = createCategory(owner, "Bánh kẹo");
        for (long price : List.of(9_999L, 10_000L, 15_000L, 20_000L, 20_001L)) {
            create(owner, product("Giá %05d".formatted(price), price).category(drinks));
        }
        create(owner, product("Khác Danh mục", 15_000).category(snacks));
        create(owner, product("Không Danh mục", 12_000));

        assertThat(names(owner, "categoryId", drinks.toString(), "minPrice", "10000", "maxPrice", "20000"))
                .containsExactly("Giá 10000", "Giá 15000", "Giá 20000");
        assertThat(names(owner, "categoryId", drinks.toString()))
                .containsExactly("Giá 09999", "Giá 10000", "Giá 15000", "Giá 20000", "Giá 20001");
        assertThat(names(owner, "minPrice", "20000")).containsExactly("Giá 20000", "Giá 20001");
        assertThat(names(owner, "maxPrice", "10000")).containsExactly("Giá 09999", "Giá 10000");
        assertThat(names(owner, "minPrice", "15000", "maxPrice", "15000"))
                .containsExactly("Giá 15000", "Khác Danh mục");
        // Số nguyên viết dạng thập phân được nhận, như tiền trong body
        assertThat(names(owner, "minPrice", "12000.0", "maxPrice", "12000")).containsExactly("Không Danh mục");
        assertThat(names(owner, "categoryId", drinks.toString(), "q", "15000")).containsExactly("Giá 15000");
        assertThat(names(owner, "categoryId", drinks.toString(), "q", "gia 2"))
                .containsExactly("Giá 20000", "Giá 20001");
        // Danh mục không có: không lọc ra gì
        assertThat(names(owner, "categoryId", UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void statusFiltersOnSaleOrStoppedProducts() throws Exception {
        Owner owner = newStore();
        create(owner, product("Bánh đang bán", 1000));
        Created stopped = create(owner, product("Kẹo đã ngừng", 1000));
        deactivate(owner, stopped.productId());

        assertThat(names(owner)).containsExactly("Bánh đang bán", "Kẹo đã ngừng");
        assertThat(names(owner, "status", "ACTIVE")).containsExactly("Bánh đang bán");
        assertThat(names(owner, "status", "INACTIVE")).containsExactly("Kẹo đã ngừng");
        assertThat(names(owner, "status", "")).containsExactly("Bánh đang bán", "Kẹo đã ngừng");
    }

    // --- Lỗi ------------------------------------------------------------------------------------------------

    @Test
    void aReversedPriceRangeIsRejectedOnMinPrice() throws Exception {
        Owner owner = newStore();
        search(owner.cookie(), "minPrice", "5", "maxPrice", "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("minPrice"));
    }

    @Test
    void anUnknownSortIsRejected() throws Exception {
        Owner owner = newStore();
        for (String sort : List.of("password,asc", "name,up", "name,asc,id", "salePrice,", ",asc", "NAME,asc")) {
            search(owner.cookie(), "sort", sort)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                    .andExpect(jsonPath("$.errors[0].field").value("sort"));
        }
    }

    @Test
    void invalidFilterValuesAreAllReportedAtOnce() throws Exception {
        Owner owner = newStore();
        search(owner.cookie(), "categoryId", "khong-phai-uuid", "status", "DELETED", "minPrice", "1.5",
                "maxPrice", "-1", "sort", "cost,desc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("categoryId", "status", "minPrice", "maxPrice", "sort")));
        search(owner.cookie(), "minPrice", "abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minPrice"));
        search(owner.cookie(), "maxPrice", "9007199254740992").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("maxPrice"));
        // UUID rút gọn mà UUID.fromString vẫn nhận
        search(owner.cookie(), "categoryId", "1-1-1-1-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    // --- Sắp xếp, phân trang --------------------------------------------------------------------------------

    @Test
    void defaultOrderIsByNameWithoutAccentsAndSortParamsWork() throws Exception {
        Owner owner = newStore();
        create(owner, product("Đường cát", 20_000));
        create(owner, product("Bánh mì", 5_000));
        create(owner, product("Ác quy", 100_000));
        create(owner, product("an toàn", 5_000));

        assertThat(names(owner)).containsExactly("Ác quy", "an toàn", "Bánh mì", "Đường cát");
        assertThat(names(owner, "sort", "name,desc")).containsExactly("Đường cát", "Bánh mì", "an toàn", "Ác quy");
        // Cùng giá thì theo tên
        assertThat(names(owner, "sort", "salePrice,asc")).containsExactly("an toàn", "Bánh mì", "Đường cát", "Ác quy");
        assertThat(names(owner, "sort", "salePrice,desc")).containsExactly("Ác quy", "Đường cát", "an toàn", "Bánh mì");
        assertThat(names(owner, "sort", "createdAt,desc")).containsExactly("an toàn", "Ác quy", "Bánh mì", "Đường cát");
        assertThat(names(owner, "sort", "createdAt")).containsExactly("Đường cát", "Bánh mì", "Ác quy", "an toàn");
        assertThat(names(owner, "sort", " salePrice , DESC ")).containsExactly("Ác quy", "Đường cát", "an toàn",
                "Bánh mì");

        JsonNode second = page(owner, "page", "1", "size", "2");
        assertThat(second.get("total").asLong()).isEqualTo(4);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(namesOf(second)).containsExactly("Bánh mì", "Đường cát");
        JsonNode filtered = page(owner, "q", "a", "size", "1", "sort", "salePrice,desc");
        assertThat(filtered.get("total").asLong()).isEqualTo(4);
        assertThat(namesOf(filtered)).containsExactly("Ác quy");
        assertThat(page(owner, "size", "500").get("size").asInt()).isEqualTo(100);
        assertThat(page(owner, "q", "khong co").get("total").asLong()).isZero();
    }

    @Test
    void listRowsKeepAllVariantsAndHaveNoCost() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        Created milk = create(owner, product("Sữa tươi Vinamilk", 15000).category(drinks).sku("VNM-1L")
                .barcode("8934673123456").opening(24, 11000));

        String response = search(owner.cookie(), "q", "sua")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(milk.productId().toString()))
                .andExpect(jsonPath("$.items[0].categoryName").value("Đồ uống"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].variants.length()").value(1))
                .andExpect(jsonPath("$.items[0].variants[0].id").value(milk.variantId().toString()))
                .andExpect(jsonPath("$.items[0].variants[0].sku").value("VNM-1L"))
                .andExpect(jsonPath("$.items[0].variants[0].salePrice").value(15000))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // AD-15, AD-16: không có Giá vốn hay tồn đầu kỳ
        assertThat(response.toLowerCase(Locale.ROOT)).doesNotContain("cost").doesNotContain("opening");
    }

    @Test
    void aProductWithSeveralVariantsIsOneRowMatchedByAnyVariantCodeButPricedByTheDefault() throws Exception {
        Owner owner = newStore();
        Created shirt = create(owner, product("Áo thun", 120_000).sku("AO-M").barcode("8930000000017"));
        UUID large = UUID.randomUUID();
        insertVariant(owner, shirt.productId(), large, "XL-AO-01", "XL", 150_000, "ACTIVE", false);
        create(owner, product("Quần jean", 300_000));

        // Mã của Biến thể không mặc định tìm ra Sản phẩm, một dòng, đủ hai Biến thể (mặc định đứng đầu)
        search(owner.cookie(), "q", "xl-ao")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(shirt.productId().toString()))
                .andExpect(jsonPath("$.items[0].variants.length()").value(2))
                .andExpect(jsonPath("$.items[0].variants[0].id").value(shirt.variantId().toString()))
                .andExpect(jsonPath("$.items[0].variants[0].isDefault").value(true))
                .andExpect(jsonPath("$.items[0].variants[1].id").value(large.toString()));
        assertThat(names(owner, "q", "ao-m")).containsExactly("Áo thun");
        assertThat(names(owner, "q", "893")).containsExactly("Áo thun");
        // Không lọc thì vẫn một dòng cho Sản phẩm, không lặp theo Biến thể
        assertThat(page(owner).get("total").asLong()).isEqualTo(2);
        // Giá lọc theo Biến thể mặc định: khoảng chỉ chứa giá của Biến thể XL không ra Áo thun
        assertThat(names(owner, "minPrice", "150000", "maxPrice", "150000")).isEmpty();
        assertThat(names(owner, "minPrice", "120000", "maxPrice", "120000")).containsExactly("Áo thun");

        // Tìm để bán: quét mã của Biến thể XL ra đúng Biến thể đó
        assertThat(forSale(owner, "XL-AO", null, null, null, 0, 20).items())
                .extracting(SellableVariant::variantId).containsExactly(large);
        assertThat(forSale(owner, "ao thun", null, null, null, 0, 20).items())
                .extracting(SellableVariant::variantId).containsExactly(shirt.variantId(), large);
    }

    // --- Cửa hàng khác, Nhân viên -------------------------------------------------------------------------------

    @Test
    void anotherStoresProductsAreNeverFound() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Created ofA = create(a, product("Sữa tươi Vinamilk", 15000).barcode("1111"));
        Created ofB = create(b, product("Sữa tươi Vinamilk", 15000).barcode("8934673123456"));
        UUID categoryOfB = createCategory(b, "Đồ uống");

        JsonNode found = page(a, "q", "sua tuoi");
        assertThat(found.get("total").asLong()).isEqualTo(1);
        assertThat(found.get("items").get(0).get("id").asString()).isEqualTo(ofA.productId().toString());
        assertThat(names(a, "q", "8934")).isEmpty();
        assertThat(names(a, "categoryId", categoryOfB.toString())).isEmpty();

        PageResponse<SellableVariant> forSale = TenantScope.runAs(a.storeId(),
                () -> lookup.searchForSale("sua tuoi", null, null, null, 0, 20));
        assertThat(forSale.items()).extracting(SellableVariant::variantId).containsExactly(ofA.variantId());
        assertThat(forSale.total()).isEqualTo(1);
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.getForSale(List.of(ofB.variantId())))).isEmpty();
    }

    @Test
    void staffCanSearchToo() throws Exception {
        Cookie ownerA = login("dev.chu.a");
        Cookie staff = login("dev.nv.a");
        String token = UUID.randomUUID().toString().substring(0, 8);
        String name = "Hàng Nhân viên tìm " + token;
        mvc.perform(post(PRODUCTS).cookie(ownerA).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(product(name, 20000).body())))
                .andExpect(status().isCreated());

        search(staff, "q", token.toUpperCase(Locale.ROOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value(name));
    }

    // --- VariantLookup -------------------------------------------------------------------------------------------

    @Test
    void getForSaleReturnsOnlyVariantsStillOnSaleInTheCurrentStoreInInputOrder() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        Created milk = create(a, product("Sữa tươi Vinamilk", 15000).sku("VNM-1L").barcode("8934673123456"));
        Created water = create(a, product("Nước suối", 5000).sku("NS-500"));
        Created stopped = create(a, product("Hàng đã ngừng", 1000));
        deactivate(a, stopped.productId());
        Created softDeleted = create(a, product("Hàng đã xóa mềm", 1000));
        asStore(a.storeId(), () -> jdbc.update("update app.product set deleted_at = changed_at where id = ?",
                softDeleted.productId()));
        Created ofB = create(b, product("Hàng của B", 1000));
        // Chưa có API nhiều Biến thể (SP-07): thêm thẳng một Biến thể đang bán, một đã ngừng, một đã xóa mềm
        UUID size = UUID.randomUUID();
        UUID sizeStopped = UUID.randomUUID();
        UUID sizeDeleted = UUID.randomUUID();
        insertVariant(a, water.productId(), size, "NS-1500", "1,5 L", 9000, "ACTIVE", false);
        insertVariant(a, water.productId(), sizeStopped, "NS-6000", "6 L", 20000, "INACTIVE", false);
        insertVariant(a, water.productId(), sizeDeleted, "NS-350", "350 ml", 4000, "ACTIVE", true);

        List<SellableVariant> found = TenantScope.runAs(a.storeId(), () -> lookup.getForSale(List.of(
                size, stopped.variantId(), ofB.variantId(), UUID.randomUUID(), milk.variantId(),
                softDeleted.variantId(), sizeStopped, sizeDeleted, water.variantId(), size)));

        assertThat(found).containsExactly(
                new SellableVariant(size, water.productId(), "Nước suối", "1,5 L", "NS-1500", null, "cái", 9000),
                new SellableVariant(milk.variantId(), milk.productId(), "Sữa tươi Vinamilk", null, "VNM-1L",
                        "8934673123456", "cái", 15000),
                new SellableVariant(water.variantId(), water.productId(), "Nước suối", null, "NS-500", null, "cái",
                        5000));
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.getForSale(List.of()))).isEmpty();
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.getForSale(List.of(stopped.variantId())))).isEmpty();
        // Id null bị bỏ qua
        assertThat(TenantScope.runAs(a.storeId(),
                () -> lookup.getForSale(Arrays.asList(null, milk.variantId(), null))))
                .extracting(SellableVariant::variantId).containsExactly(milk.variantId());
        assertThat(TenantScope.runAs(a.storeId(), () -> lookup.getForSale(Arrays.asList((UUID) null)))).isEmpty();
        // Không có ngữ cảnh Cửa hàng (ngoài request, không TenantScope): lỗi lập trình
        assertThatThrownBy(() -> lookup.getForSale(List.of(milk.variantId())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lookup.getForSale(List.of())).isInstanceOf(IllegalStateException.class);
        // Bên B thấy hàng của B
        assertThat(TenantScope.runAs(b.storeId(), () -> lookup.getForSale(List.of(ofB.variantId(), milk.variantId()))))
                .extracting(SellableVariant::variantId).containsExactly(ofB.variantId());
    }

    @Test
    void searchForSaleOnlyReturnsWhatIsStillOnSale() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        Created fresh = create(owner, product("Sữa tươi Vinamilk", 15000).category(drinks).barcode("8934673123456"));
        Created condensed = create(owner, product("Sữa đặc Ông Thọ", 25000).category(drinks));
        Created stopped = create(owner, product("Sữa bột ngừng bán", 300000).category(drinks).barcode("8934000000001"));
        deactivate(owner, stopped.productId());
        create(owner, product("Bánh quy", 30000));
        UUID bigFresh = UUID.randomUUID();
        insertVariant(owner, fresh.productId(), bigFresh, "VNM-2L", "2 L", 28000, "ACTIVE", false);

        // Danh sách quản lý vẫn có hàng đã ngừng; tìm để bán thì không
        assertThat(names(owner, "q", "sua")).containsExactly("Sữa bột ngừng bán", "Sữa đặc Ông Thọ",
                "Sữa tươi Vinamilk");
        PageResponse<SellableVariant> all = forSale(owner, "sua", null, null, null, 0, 20);
        assertThat(all.items()).extracting(SellableVariant::variantId)
                .containsExactly(condensed.variantId(), fresh.variantId(), bigFresh);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.page()).isZero();
        assertThat(all.size()).isEqualTo(20);
        assertThat(forSale(owner, "8934", null, null, null, 0, 20).items()).extracting(SellableVariant::variantId)
                .containsExactly(fresh.variantId());
        // Giá lọc theo chính Biến thể, gồm cả hai đầu
        assertThat(forSale(owner, null, drinks, 15000L, 25000L, 0, 20).items())
                .extracting(SellableVariant::variantId).containsExactly(condensed.variantId(), fresh.variantId());
        assertThat(forSale(owner, " SỮA TƯƠI ", null, 20000L, null, 0, 20).items())
                .extracting(SellableVariant::variantId).containsExactly(bigFresh);
        // Phân trang
        PageResponse<SellableVariant> second = forSale(owner, null, null, null, null, 1, 2);
        assertThat(second.total()).isEqualTo(4);
        assertThat(second.items()).extracting(SellableVariant::variantId).containsExactly(fresh.variantId(), bigFresh);
        assertThat(forSale(owner, null, null, null, null, -3, 500).size()).isEqualTo(100);
        assertThat(forSale(owner, "50%", null, null, null, 0, 20).items()).isEmpty();

        assertThatThrownBy(() -> forSale(owner, null, null, 5L, 1L, 0, 20))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(problem.type()).isEqualTo("/errors/common/validation");
                    assertThat(problem.properties().get("errors").toString()).contains("minPrice");
                });
        assertThatThrownBy(() -> forSale(owner, null, null, -1L, null, 0, 20))
                .isInstanceOf(ProblemException.class);
        assertThatThrownBy(() -> forSale(owner, null, null, null, 9_007_199_254_740_992L, 0, 20))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.properties().get("errors").toString()).contains("maxPrice"));
        assertThatThrownBy(() -> lookup.searchForSale("sua", null, null, null, 0, 20))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRejectedSearchDoesNotRollTheCallersTransactionBack() throws Exception {
        Owner owner = newStore();
        Created milk = create(owner, product("Sữa tươi Vinamilk", 15000));

        // Như sales: ghi trong transaction của mình, gọi searchForSale với khoảng giá ngược, bắt lỗi 400, rồi commit
        ProblemException caught = asStore(owner.storeId(), () -> {
            jdbc.update("update app.product set description = 'ghi trước khi tìm' where id = ?", milk.productId());
            try {
                lookup.searchForSale("sua", null, 5L, 1L, 0, 20);
                return null;
            } catch (ProblemException rejected) {
                return rejected;
            }
        });

        assertThat(caught).isNotNull();
        assertThat(caught.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        // asStore không ném UnexpectedRollbackException, và phần đã ghi còn đó
        assertThat(asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select description from app.product where id = ?", String.class, milk.productId())))
                .isEqualTo("ghi trước khi tìm");
    }

    // --- Hỗ trợ -----------------------------------------------------------------------------------------------

    /** Body thêm Sản phẩm, dựng dần. */
    static final class ProductBody {

        private final Map<String, Object> body = new LinkedHashMap<>();

        ProductBody sku(String sku) {
            body.put("sku", sku);
            return this;
        }

        ProductBody barcode(String barcode) {
            body.put("barcode", barcode);
            return this;
        }

        ProductBody category(UUID categoryId) {
            body.put("categoryId", categoryId.toString());
            return this;
        }

        ProductBody opening(int quantity, long unitCost) {
            body.put("openingQuantity", quantity);
            body.put("openingUnitCost", unitCost);
            return this;
        }

        Map<String, Object> body() {
            return body;
        }
    }

    static ProductBody product(String name, long salePrice) {
        ProductBody product = new ProductBody();
        product.body.put("name", name);
        product.body.put("salePrice", salePrice);
        return product;
    }

    Created create(Owner owner, ProductBody product) throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(product.body())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode created = json.readTree(response);
        return new Created(UUID.fromString(created.get("id").asString()),
                UUID.fromString(created.get("variants").get(0).get("id").asString()));
    }

    void deactivate(Owner owner, UUID productId) throws Exception {
        mvc.perform(post(PRODUCTS + "/" + productId + "/deactivate").cookie(owner.cookie()))
                .andExpect(status().isOk());
    }

    UUID createCategory(Owner owner, String name) throws Exception {
        String response = mvc.perform(post("/api/v1/catalog/categories").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    /** Thêm thẳng một Biến thể không mặc định (chưa có API, SP-07). */
    void insertVariant(Owner owner, UUID productId, UUID variantId, String sku, String name, long salePrice,
                       String status, boolean softDeleted) {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1);
        asStore(owner.storeId(), () -> jdbc.update("""
                insert into app.product_variant (id, store_id, product_id, sku, name, sale_price, status,
                                                 created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, variantId, owner.storeId(), productId, sku, name, salePrice, status, at, at,
                softDeleted ? at : null));
    }

    PageResponse<SellableVariant> forSale(Owner owner, String q, UUID categoryId, Long minPrice, Long maxPrice,
                                          int page, int size) {
        return TenantScope.runAs(owner.storeId(),
                () -> lookup.searchForSale(q, categoryId, minPrice, maxPrice, page, size));
    }

    ResultActions search(Cookie cookie, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(PRODUCTS).cookie(cookie);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    JsonNode page(Owner owner, String... params) throws Exception {
        String response = search(owner.cookie(), params)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    List<String> names(Owner owner, String... params) throws Exception {
        return namesOf(page(owner, params));
    }

    static List<String> namesOf(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử tìm kiếm");
        body.put("loginKey", "tim." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()));
    }

    Cookie login(String loginKey) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginKey\":\"" + loginKey + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
