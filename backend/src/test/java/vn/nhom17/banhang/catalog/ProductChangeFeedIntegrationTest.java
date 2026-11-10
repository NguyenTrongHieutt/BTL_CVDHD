package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.nhom17.banhang.catalog.internal.feed.ProductChangeRepository.SAFETY_LAG;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.catalog.internal.feed.SyncReaderJdbc;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Change-feed Sản phẩm cho dịch vụ AI (AD-12, hợp đồng 3.2 điểm 7) trên PostgreSQL thật: ma trận I/O của spec.
 *
 * <p>Database dùng chung với các test khác cùng context, và feed thấy Sản phẩm của mọi Cửa hàng, nên:
 * <ul>
 *   <li>Test "từ đầu" đặt {@code changed_at} của Sản phẩm mình trước mọi dòng đang có ({@link #beforeEverything()}).</li>
 *   <li>Test khác đọc từ con trỏ ngay trước {@code changed_at} của Sản phẩm mình ({@link #justBefore}); con trỏ dựng
 *       theo đúng định dạng của hợp đồng ({@link #cursor}).</li>
 *   <li>Thay cho chờ {@code SAFETY_LAG}, {@code changed_at} được đặt bằng UPDATE theo {@code Clock} của ứng dụng (cùng
 *       nguồn giờ với lúc ghi và với độ trễ an toàn).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductChangeFeedIntegrationTest {

    static final String FEED = "/internal/catalog/product-changes";
    static final String PRODUCTS = "/api/v1/catalog/products";
    static final UUID ZERO = new UUID(0L, 0L);
    /** Định dạng thời điểm trong con trỏ theo hợp đồng: ISO-8601 UTC đủ micro giây. */
    static final DateTimeFormatter CURSOR_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    /** Thứ tự của PostgreSQL: {@code changed_at}, rồi {@code uuid} theo byte (bằng thứ tự chuỗi hex chữ thường). */
    static final Comparator<Item> FEED_ORDER =
            Comparator.comparing(Item::changedAt).thenComparing(item -> item.productId().toString());

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SyncReaderJdbc syncReader;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired Clock clock;
    @Autowired ApplicationContext context;
    @Autowired HealthContributorRegistry health;

    record Owner(Cookie cookie, UUID storeId) {
    }

    record Item(UUID storeId, UUID productId, String searchText, String status, Instant changedAt) {
    }

    record Page(List<Item> items, String nextCursor) {
    }

    // --- Từ đầu, phân trang, hết -------------------------------------------------------------------------------

    @Test
    void fromTheStartTheFeedHasEveryStoreSortedByChangedAtThenProductId() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID milk = create(a, "Sữa tươi Vinamilk 1L", null);
        UUID bread = create(a, "Bánh mì sandwich", null);
        UUID beer = create(b, "Bia Tiger lon 330ml", null);
        Instant t0 = beforeEverything();
        setChangedAt(a, milk, t0);
        setChangedAt(b, beer, t0.plusSeconds(1));
        Instant last = t0.plusSeconds(2).plus(1, ChronoUnit.MICROS);   // micro giây phải còn nguyên trong con trỏ
        setChangedAt(a, bread, last);

        Page page = read(null, 3);
        assertThat(page.items())
                .extracting(Item::storeId, Item::productId, Item::searchText, Item::status, Item::changedAt)
                .containsExactly(
                        tuple(a.storeId(), milk, "Sữa tươi Vinamilk 1L", "ACTIVE", t0),
                        tuple(b.storeId(), beer, "Bia Tiger lon 330ml", "ACTIVE", t0.plusSeconds(1)),
                        tuple(a.storeId(), bread, "Bánh mì sandwich", "ACTIVE", last));
        // nextCursor là vị trí phần tử cuối: base64url không padding của "changedAt|productId", đủ micro giây
        assertThat(page.nextCursor()).isEqualTo(cursor(last, bread)).doesNotContain("=");
        assertThat(new String(Base64.getUrlDecoder().decode(page.nextCursor()), StandardCharsets.UTF_8))
                .isEqualTo(CURSOR_TIME.format(last) + "|" + bread)
                .endsWith(".000001Z|" + bread);

        // after rỗng cũng là từ đầu; bỏ limit thì mặc định 500
        assertThat(read("", 3).items()).isEqualTo(page.items());
        Page defaults = read(null, null);
        assertThat(defaults.items()).hasSizeBetween(3, 500);
        assertThat(defaults.items().subList(0, 3)).isEqualTo(page.items());
    }

    @Test
    void theResponseHasExactlyTheContractFields() throws Exception {
        Owner owner = newStore();
        UUID product = create(owner, "Mì Hảo Hảo tôm chua cay", null);
        Instant settled = settledAgo(Duration.ofSeconds(1));
        setChangedAt(owner, product, settled);

        String body = mvc.perform(feedRequest().param("after", justBefore(settled)).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(root.get("items").get(0).propertyNames())
                .containsExactlyInAnyOrder("storeId", "productId", "searchText", "status", "changedAt");
    }

    @Test
    void pagingWithNextCursorNeitherRepeatsNorSkips() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID milk = create(a, "Sữa tươi Vinamilk 1L", null);
        UUID bread = create(a, "Bánh mì sandwich", null);
        UUID beer = create(b, "Bia Tiger lon 330ml", null);
        Instant t0 = beforeEverything();
        setChangedAt(a, milk, t0);
        setChangedAt(b, beer, t0.plusSeconds(1));
        setChangedAt(a, bread, t0.plusSeconds(2));

        Page first = read(null, 2);
        assertThat(ids(first)).containsExactly(milk, beer);
        assertThat(first.nextCursor()).isEqualTo(cursor(t0.plusSeconds(1), beer));

        Page second = read(first.nextCursor(), 2);
        assertThat(second.items()).isNotEmpty();
        assertThat(second.items().getFirst().productId()).isEqualTo(bread);
        assertThat(second.items().getFirst().storeId()).isEqualTo(a.storeId());
        assertThat(ids(second)).doesNotContain(milk, beer);
    }

    @Test
    void changesAtTheSameInstantAreOrderedByProductIdAcrossThePageBoundary() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID tea = create(a, "Trà xanh C2", null);
        UUID coffee = create(b, "Cà phê G7", null);
        Instant t0 = beforeEverything();
        setChangedAt(a, tea, t0);
        setChangedAt(b, coffee, t0);
        List<UUID> expected = Stream.of(tea, coffee).sorted(Comparator.comparing(UUID::toString)).toList();

        Page first = read(null, 1);
        assertThat(ids(first)).containsExactly(expected.get(0));
        Page second = read(first.nextCursor(), 1);
        assertThat(ids(second)).containsExactly(expected.get(1));
        assertThat(second.items().getFirst().changedAt()).isEqualTo(t0);
    }

    @Test
    void readingToTheEndGivesAnEmptyPageThatHandsBackTheSameCursor() throws Exception {
        Owner owner = newStore();
        UUID product = create(owner, "Nước rửa chén Sunlight", null);
        setChangedAt(owner, product, settledAgo(Duration.ofSeconds(1)));   // feed chắc chắn có ít nhất một dòng

        String after = null;
        Item previous = null;
        boolean sawProduct = false;
        for (int round = 0; ; round++) {
            assertThat(round).as("feed phải hết sau một số trang").isLessThan(1000);
            Page page = read(after, 500);
            if (page.items().isEmpty()) {
                assertThat(after).isNotNull();
                assertThat(page.nextCursor()).isEqualTo(after);
                break;
            }
            for (Item item : page.items()) {
                if (previous != null) {
                    assertThat(FEED_ORDER.compare(previous, item)).as("tăng dần, không lặp: %s rồi %s", previous, item)
                            .isNegative();
                }
                sawProduct |= item.productId().equals(product);
                previous = item;
            }
            assertThat(page.nextCursor()).isEqualTo(cursor(previous.changedAt(), previous.productId()));
            after = page.nextCursor();
        }
        assertThat(sawProduct).isTrue();

        // Con trỏ ở sau mọi dòng: trang rỗng, nextCursor là đúng after (dịch vụ AI không ghi lại, không lùi)
        String farAhead = cursor(clock.instant().plus(Duration.ofDays(365)), ZERO);
        Page none = read(farAhead, 500);
        assertThat(none.items()).isEmpty();
        assertThat(none.nextCursor()).isEqualTo(farAhead);
    }

    // --- Độ trễ an toàn ----------------------------------------------------------------------------------------

    @Test
    void aChangeYoungerThanTheSafetyLagIsHeldBackUntilItIsOlder() throws Exception {
        assertThat(SAFETY_LAG).isEqualTo(Duration.ofSeconds(5));
        Owner owner = newStore();
        UUID product = create(owner, "Nước suối Aquafina 500ml", null);
        // Vừa thêm: changed_at là giờ tạo (Clock), chưa có trong feed
        assertThat(find(justBefore(changedAt(owner, product)), product)).isEmpty();
        setChangedAt(owner, product, settledAgo(Duration.ofSeconds(1)));
        assertThat(find(justBefore(changedAt(owner, product)), product)).hasValueSatisfying(item ->
                assertThat(item.searchText()).isEqualTo("Nước suối Aquafina 500ml"));

        // Vừa đổi tên: changed_at lấy giờ mới, chưa có trong feed
        mvc.perform(put(PRODUCTS + "/" + product).cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(productBody("Nước khoáng Lavie 500ml", null))))
                .andExpect(status().isOk());
        assertThat(find(justBefore(changedAt(owner, product)), product)).isEmpty();
        // Còn 2 giây nữa mới đủ độ trễ: vẫn chưa có
        setChangedAt(owner, product, settledAgo(Duration.ofSeconds(-2)));
        assertThat(find(justBefore(changedAt(owner, product)), product)).isEmpty();

        // Đã quá độ trễ: có trong feed với tên mới
        setChangedAt(owner, product, settledAgo(Duration.ofSeconds(1)));
        Item item = find(justBefore(changedAt(owner, product)), product).orElseThrow();
        assertThat(item.searchText()).isEqualTo("Nước khoáng Lavie 500ml");
        assertThat(item.status()).isEqualTo("ACTIVE");
    }

    // --- searchText, trạng thái --------------------------------------------------------------------------------

    @Test
    void searchTextJoinsNameCategoryAndLiveVariantLabelsAndAttributeValues() throws Exception {
        Owner owner = newStore();
        UUID fashion = createCategory(owner, "Thời trang");
        UUID shirt = create(owner, "Áo thun", fashion);
        UUID milk = create(owner, "Sữa tươi Vinamilk 1L", null);
        // Chưa có API nhiều Biến thể (SP-07): thêm thẳng một Biến thể đang bán và một đã xóa mềm
        insertVariant(owner, shirt, "AO-L", "Đỏ / L",
                "{\"màu\": \"Đỏ\", \"cỡ\": \"L\", \"ghi chú\": \"  \", \"mã\": null}", false);
        insertVariant(owner, shirt, "AO-M", "Xanh / M", "{\"màu\": \"Xanh\", \"cỡ\": \"M\"}", true);
        Instant settled = settledAgo(Duration.ofSeconds(1));
        setChangedAt(owner, shirt, settled);
        setChangedAt(owner, milk, settled);

        // Biến thể mặc định (không nhãn, thuộc tính {}) không thêm gì; phần rỗng bị bỏ; giá trị thuộc tính theo tên
        // khóa (cỡ, màu); Biến thể đã xóa mềm không có
        assertThat(find(justBefore(settled), milk).orElseThrow().searchText()).isEqualTo("Sữa tươi Vinamilk 1L");
        assertThat(find(justBefore(settled), shirt).orElseThrow().searchText())
                .isEqualTo("Áo thun Thời trang Đỏ / L L Đỏ");
        assertThat(viewSearchText(owner, shirt)).isEqualTo("Áo thun Thời trang Đỏ / L L Đỏ");
    }

    @Test
    void theViewStillAppliesRlsToAppBackendAndTheFeedOrderHasAnIndex() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        create(a, "Nước tăng lực Sting", null);
        create(b, "Nước tăng lực Redbull", null);
        // security_invoker: app_backend đọc view vẫn chỉ thấy Cửa hàng của mình
        assertThat(asStore(a.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.catalog_product_changes where store_id = ?", Long.class, b.storeId())))
                .isZero();
        assertThat(asStore(a.storeId(), () -> jdbc.queryForObject(
                "select count(*) from app.catalog_product_changes", Long.class))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select array_to_string(reloptions, ',') from pg_class
                where oid = 'app.catalog_product_changes'::regclass
                """, String.class)).contains("security_invoker=true");
        assertThat(jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'app' and indexname = 'product_changed_feed_idx'",
                String.class)).endsWith("ON app.product USING btree (changed_at, id)");
    }

    @Test
    void aDeactivatedProductComesThroughAsInactive() throws Exception {
        Owner owner = newStore();
        UUID product = create(owner, "Kẹo cao su Doublemint", null);
        mvc.perform(post(PRODUCTS + "/" + product + "/deactivate").cookie(owner.cookie()))
                .andExpect(status().isOk());
        setChangedAt(owner, product, settledAgo(Duration.ofSeconds(1)));

        Item item = find(justBefore(changedAt(owner, product)), product).orElseThrow();
        assertThat(item.status()).isEqualTo("INACTIVE");
        assertThat(item.storeId()).isEqualTo(owner.storeId());
        assertThat(item.searchText()).isEqualTo("Kẹo cao su Doublemint");
    }

    @Test
    void aSoftDeletedProductComesThroughAsInactive() throws Exception {
        Owner owner = newStore();
        UUID product = create(owner, "Bánh quy Cosy", null);
        Instant settled = settledAgo(Duration.ofSeconds(1));
        int rows = asStore(owner.storeId(), () -> jdbc.update(
                "update app.product set deleted_at = ?, changed_at = ? where id = ?",
                OffsetDateTime.ofInstant(settled, ZoneOffset.UTC), OffsetDateTime.ofInstant(settled, ZoneOffset.UTC),
                product));
        assertThat(rows).isEqualTo(1);

        Item item = find(justBefore(settled), product).orElseThrow();
        assertThat(item.status()).isEqualTo("INACTIVE");
        assertThat(item.searchText()).isEqualTo("Bánh quy Cosy");
    }

    @Test
    void renamingTheCategoryBringsTheNewNameIntoSearchText() throws Exception {
        Owner owner = newStore();
        UUID drinks = createCategory(owner, "Đồ uống");
        UUID tea = create(owner, "Trà xanh C2", drinks);
        UUID tissue = create(owner, "Khăn giấy Pulppy", null);
        Instant old = settledAgo(Duration.ofSeconds(1));
        setChangedAt(owner, tea, old);
        setChangedAt(owner, tissue, old);
        assertThat(find(justBefore(old), tea)).hasValueSatisfying(item ->
                assertThat(item.searchText()).isEqualTo("Trà xanh C2 Đồ uống"));

        mvc.perform(put("/api/v1/catalog/categories/" + drinks).cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Nước giải khát"))))
                .andExpect(status().isOk());
        // SP-04: Sản phẩm thuộc Danh mục có changed_at mới; Sản phẩm khác giữ nguyên
        assertThat(changedAt(owner, tea)).isAfter(old);
        assertThat(changedAt(owner, tissue)).isEqualTo(old);

        setChangedAt(owner, tea, settledAgo(Duration.ofSeconds(1)));
        Item item = find(justBefore(changedAt(owner, tea)), tea).orElseThrow();
        assertThat(item.searchText()).isEqualTo("Trà xanh C2 Nước giải khát");
    }

    // --- Tham số sai -------------------------------------------------------------------------------------------

    static Stream<String> brokenCursors() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String id = "0b9d7a3e-5f0c-4c1e-9a5e-2f7d1c3b4a50";
        return Stream.of(
                "abc",
                "!!!!",
                "x".repeat(200),
                // Thiếu dấu | và productId
                b64.encodeToString("2026-11-10T00:00:00.000000Z".getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("2026-11-10T00:00:00Z|" + id).getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("2026-11-10T00:00:00.000000Z|" + id.toUpperCase(Locale.ROOT))
                        .getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("2026-02-30T00:00:00.000000Z|" + id).getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString("2026-11-10T00:00:00.000000Z|khong-phai-uuid".getBytes(StandardCharsets.UTF_8)),
                // Năm có dấu, quá lớn: mã hóa lại đúng nhưng PostgreSQL không nhận
                b64.encodeToString(("+300000-01-01T00:00:00.000000Z|" + id).getBytes(StandardCharsets.UTF_8)),
                b64.encodeToString(("0000-01-01T00:00:00.000000Z|" + id).getBytes(StandardCharsets.UTF_8)),
                // Đúng nội dung nhưng có padding
                Base64.getUrlEncoder().encodeToString(("2026-11-10T00:00:00.000000Z|" + id)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @MethodSource("brokenCursors")
    void aBrokenCursorIsAValidationError(String after) throws Exception {
        mvc.perform(feedRequest().param("after", after))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("after"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "501", "-1", "abc", "1.5", "99999999999"})
    void aLimitOutsideOneToFiveHundredIsAValidationError(String limit) throws Exception {
        mvc.perform(feedRequest().param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void bothBrokenParametersAreReportedTogetherAndTheLimitBoundsAreAccepted() throws Exception {
        mvc.perform(feedRequest().param("after", "abc").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("after"))
                .andExpect(jsonPath("$.errors[1].field").value("limit"));
        assertThat(read(null, 1).items()).hasSizeLessThanOrEqualTo(1);
        assertThat(read(null, 500).items()).hasSizeLessThanOrEqualTo(500);
    }

    // --- Chỉ X-Service-Key -------------------------------------------------------------------------------------

    @Test
    void onlyTheServiceKeyOpensTheFeed() throws Exception {
        mvc.perform(get(FEED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/errors/common/unauthorized"));
        mvc.perform(get(FEED).header("X-Service-Key", "sai-khoa"))
                .andExpect(status().isUnauthorized());
        Owner owner = newStore();
        mvc.perform(get(FEED).cookie(owner.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(get(FEED).header("Authorization", "Bearer " + owner.cookie().getValue()))
                .andExpect(status().isForbidden());
    }

    // --- Kết nối sync_reader -----------------------------------------------------------------------------------

    @Test
    void theFeedHasItsOwnReadOnlySyncReaderPoolAndTheMainPoolStaysUnderRls() {
        JdbcTemplate sync = syncReader.jdbc();
        assertThat(sync.getDataSource()).isNotSameAs(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource pool = (HikariDataSource) sync.getDataSource();
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        assertThat(pool.isReadOnly()).isTrue();
        assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
        assertThat(sync.getQueryTimeout()).isEqualTo(8);

        assertThat(sync.queryForObject("select current_user", String.class)).isEqualTo("sync_reader");
        assertThat(sync.queryForObject("select rolbypassrls from pg_roles where rolname = current_user", Boolean.class))
                .isTrue();
        assertThat(sync.queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
        assertThatThrownBy(() -> sync.update("update app.product set name = name where false"))
                .isInstanceOf(DataAccessException.class);
        // Chỉ được đọc product, product_variant, category và view; bảng khác: permission denied
        for (String table : List.of("app.app_user", "app.customer", "app.sales_order")) {
            assertThatThrownBy(() -> sync.queryForList("select 1 from " + table + " limit 1"))
                    .as(table)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        }

        // Datasource chính (JPA, JdbcTemplate, TenantTransactionManager) vẫn là app_backend, không BYPASSRLS
        assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(1);
        assertThat(jdbc.getDataSource()).isSameAs(dataSource);
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("app_backend");
        assertThat(jdbc.queryForObject("select rolbypassrls from pg_roles where rolname = current_user", Boolean.class))
                .isFalse();
    }

    @Test
    void actuatorHealthStaysUpAndDoesNotCheckTheSyncReaderPool() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // Health "db" chỉ kiểm datasource chính; pool sync_reader không là bean DataSource nên không bị kiểm
        assertThat(health.getContributor("db")).isInstanceOf(DataSourceHealthIndicator.class);
        assertThat(contributorNames(health, "")).isNotEmpty()
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("sync"));
    }

    // --- Hỗ trợ ------------------------------------------------------------------------------------------------

    static List<String> contributorNames(HealthContributors contributors, String prefix) {
        List<String> names = new ArrayList<>();
        contributors.stream().forEach(entry -> {
            String name = prefix + entry.name();
            names.add(name);
            if (entry.contributor() instanceof CompositeHealthContributor composite) {
                names.addAll(contributorNames(composite, name + "/"));
            }
        });
        return names;
    }

    MockHttpServletRequestBuilder feedRequest() {
        return get(FEED).header("X-Service-Key", TestcontainersConfiguration.SERVICE_KEY);
    }

    Page read(String after, Integer limit) throws Exception {
        MockHttpServletRequestBuilder request = feedRequest();
        if (after != null) {
            request.param("after", after);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        String body = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        List<Item> items = new ArrayList<>();
        for (JsonNode node : root.get("items")) {
            items.add(new Item(UUID.fromString(node.get("storeId").asString()),
                    UUID.fromString(node.get("productId").asString()), node.get("searchText").asString(),
                    node.get("status").asString(), Instant.parse(node.get("changedAt").asString())));
        }
        JsonNode next = root.get("nextCursor");
        assertThat(next).as("phản hồi luôn có nextCursor").isNotNull();
        return new Page(items, next.isNull() ? null : next.asString());
    }

    /** Đọc từ {@code after} tới hết feed, dừng ở dòng của {@code productId}. */
    Optional<Item> find(String after, UUID productId) throws Exception {
        String cursor = after;
        for (int round = 0; round < 1000; round++) {
            Page page = read(cursor, 500);
            Optional<Item> found = page.items().stream().filter(i -> i.productId().equals(productId)).findFirst();
            if (found.isPresent() || page.items().isEmpty()) {
                return found;
            }
            cursor = page.nextCursor();
        }
        throw new AssertionError("feed không hết sau 1000 trang");
    }

    static List<UUID> ids(Page page) {
        return page.items().stream().map(Item::productId).toList();
    }

    /** Con trỏ theo hợp đồng: base64url không padding của {@code "<changedAt đủ micro giây>|<productId>"}. */
    static String cursor(Instant changedAt, UUID productId) {
        String raw = CURSOR_TIME.format(changedAt) + "|" + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Con trỏ đứng ngay trước mọi dòng có {@code changed_at >= at}. */
    static String justBefore(Instant at) {
        return cursor(at.minus(1, ChronoUnit.MICROS), ZERO);
    }

    /** Mốc đã quá độ trễ an toàn thêm {@code margin} (âm: còn thiếu), theo {@code Clock} của ứng dụng. */
    Instant settledAgo(Duration margin) {
        return clock.instant().minus(SAFETY_LAG).minus(margin).truncatedTo(ChronoUnit.MICROS);
    }

    /** Một ngày trước dòng sớm nhất đang có trong feed (database dùng chung với test khác), tròn giây. */
    Instant beforeEverything() throws Exception {
        Page first = read(null, 1);
        Instant earliest = first.items().isEmpty() ? clock.instant() : first.items().getFirst().changedAt();
        return earliest.minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    }

    void setChangedAt(Owner owner, UUID productId, Instant at) {
        int rows = asStore(owner.storeId(), () -> jdbc.update("update app.product set changed_at = ? where id = ?",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), productId));
        assertThat(rows).isEqualTo(1);
    }

    Instant changedAt(Owner owner, UUID productId) {
        return asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select changed_at from app.product where id = ?", OffsetDateTime.class, productId)).toInstant();
    }

    String viewSearchText(Owner owner, UUID productId) {
        return asStore(owner.storeId(), () -> jdbc.queryForObject(
                "select search_text from app.catalog_product_changes where product_id = ?", String.class, productId));
    }

    void insertVariant(Owner owner, UUID productId, String sku, String name, String attributes, boolean deleted) {
        OffsetDateTime deletedAt = deleted ? OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC) : null;
        asStore(owner.storeId(), () -> jdbc.update("""
                insert into app.product_variant (store_id, product_id, sku, name, attributes, sale_price, deleted_at)
                values (?, ?, ?, ?, ?::jsonb, 120000, ?)
                """, owner.storeId(), productId, sku, name, attributes, deletedAt));
    }

    static Map<String, Object> productBody(String name, UUID categoryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("salePrice", 10000);
        if (categoryId != null) {
            body.put("categoryId", categoryId.toString());
        }
        return body;
    }

    UUID create(Owner owner, String name, UUID categoryId) throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(productBody(name, categoryId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    UUID createCategory(Owner owner, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/catalog/categories").cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử change-feed");
        body.put("loginKey", "feed." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()));
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
