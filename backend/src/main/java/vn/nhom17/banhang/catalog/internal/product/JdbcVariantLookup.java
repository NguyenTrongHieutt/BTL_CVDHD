package vn.nhom17.banhang.catalog.internal.product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Hiện thực {@link VariantLookup} cho {@code sales} (AD-2, AD-8; SP-05). Dùng chung truy vấn của {@link ProductSearch}
 * với phạm vi {@link ProductSearch.Scope#SELLABLE}: chỉ Biến thể {@code ACTIVE}, chưa xóa mềm, của Sản phẩm
 * {@code ACTIVE}, chưa xóa mềm, của Cửa hàng hiện tại ({@code store_id} và RLS). Không có Giá vốn (AD-15); không đọc
 * bảng {@code inventory}.
 *
 * <p>Mỗi phương thức chạy trong transaction chỉ đọc, hoặc tham gia transaction của bên gọi (ví dụ lúc tạo Đơn hàng),
 * nên dùng được cả dưới {@code TenantScope.runAs}.
 */
@Component
class JdbcVariantLookup implements VariantLookup {

    private static final String COLUMNS = """
            select v.id as variant_id, p.id as product_id, p.name as product_name, v.name as variant_name, v.sku,
                   v.barcode, p.unit, v.sale_price
            """;
    /** Theo tên không dấu như danh sách Sản phẩm; Biến thể của cùng Sản phẩm: mặc định trước, rồi theo ngày tạo. */
    private static final String ORDER = "order by " + ProductSearch.FOLDED_NAME
            + ", lower(p.name), p.id, v.is_default desc, v.created_at, v.id\n";

    private static final RowMapper<SellableVariant> ROW = (rs, i) -> new SellableVariant(
            rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getString("product_name"), rs.getString("variant_name"), rs.getString("sku"), rs.getString("barcode"),
            rs.getString("unit"), rs.getLong("sale_price"));

    @Override
    @Transactional(readOnly = true)
    public List<SellableVariant> findForInventory(String query, UUID categoryId, boolean includeInactive) {
        var criteria = ProductSearch.forSale(query, categoryId, null, null);
        var scope = includeInactive ? ProductSearch.Scope.INVENTORY : ProductSearch.Scope.SELLABLE;
        var matching = ProductSearch.from(scope, context.storeId(), criteria);
        return jdbc.query(COLUMNS + matching.text(), ROW, matching.args().toArray());
    }

    private final JdbcTemplate jdbc;
    private final CurrentContext context;

    JdbcVariantLookup(JdbcTemplate jdbc, CurrentContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    /**
     * Giữ thứ tự id đầu vào; id trùng chỉ trả một lần (lần xuất hiện đầu). Id {@code null}, id lạ, của Cửa hàng khác,
     * Biến thể đã ngừng kinh doanh hay đã xóa mềm (hoặc Sản phẩm của nó như vậy) thì bỏ qua.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SellableVariant> getForSale(Collection<UUID> variantIds) {
        // Không có ngữ cảnh Cửa hàng thì lỗi ngay, kể cả khi danh sách rỗng
        UUID storeId = context.storeId();
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> wanted = new LinkedHashSet<>();
        variantIds.stream().filter(Objects::nonNull).forEach(wanted::add);
        if (wanted.isEmpty()) {
            return List.of();
        }
        ProductSearch.Sql matching = ProductSearch.from(ProductSearch.Scope.SELLABLE, storeId,
                ProductSearch.Criteria.NONE);
        List<Object> args = matching.args();
        List<SellableVariant> found = jdbc.query(COLUMNS + matching.text() + " and v.id = any(?)\n", ps -> {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            ps.setArray(args.size() + 1, ps.getConnection().createArrayOf("uuid", wanted.toArray()));
        }, ROW);
        Map<UUID, SellableVariant> byId = new LinkedHashMap<>();
        found.forEach(variant -> byId.put(variant.variantId(), variant));
        List<SellableVariant> ordered = new ArrayList<>(byId.size());
        for (UUID id : wanted) {
            SellableVariant variant = byId.get(id);
            if (variant != null) {
                ordered.add(variant);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Tìm kiếm thường (FR-5) như {@code GET /api/v1/catalog/products} nhưng chỉ hàng còn bán, mỗi Biến thể một dòng;
     * giá, SKU, mã vạch là của chính Biến thể đó. Sắp theo tên không dấu. {@code page < 0} thành 0, {@code size}
     * mặc định 20, tối đa 100 (AD-19).
     *
     * <p>Tham số sai ném {@code ProblemException 400} nhưng KHÔNG đánh dấu rollback transaction của bên gọi
     * ({@code noRollbackFor}): bên gọi bắt lỗi rồi vẫn commit được.
     */
    @Override
    @Transactional(readOnly = true, noRollbackFor = ProblemException.class)
    public PageResponse<SellableVariant> searchForSale(String query, UUID categoryId, Long minPrice, Long maxPrice,
                                                       int page, int size) {
        ProductSearch.Criteria criteria = ProductSearch.forSale(query, categoryId, minPrice, maxPrice);
        int pageNo = Math.max(page, 0);
        int pageSize = PageResponse.clampSize(size);
        ProductSearch.Sql matching = ProductSearch.from(ProductSearch.Scope.SELLABLE, context.storeId(), criteria);
        Long total = jdbc.queryForObject("select count(*)\n" + matching.text(), Long.class,
                matching.args().toArray());
        if (total == null || total == 0) {
            return new PageResponse<>(List.of(), 0, pageNo, pageSize);
        }
        List<Object> args = new ArrayList<>(matching.args());
        args.add(pageSize);
        args.add((long) pageNo * pageSize);
        List<SellableVariant> items = jdbc.query(COLUMNS + matching.text() + ORDER + "limit ? offset ?", ROW,
                args.toArray());
        return new PageResponse<>(items, total, pageNo, pageSize);
    }
}
