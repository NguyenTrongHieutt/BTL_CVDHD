package vn.nhom17.banhang.catalog.internal.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;

/**
 * Tìm kiếm thường (FR-5, SP-05): MỘT truy vấn dùng chung cho {@code GET /api/v1/catalog/products} và
 * {@link vn.nhom17.banhang.catalog.VariantLookup} ({@code searchForSale}, {@code getForSale}). Câu SQL đi trên hai bí
 * danh: {@code p} là Sản phẩm, {@code v} là Biến thể của dòng kết quả ({@link Scope}).
 *
 * <ul>
 *   <li>{@code q}: chuẩn hóa như trường văn bản ({@link TextFields#normalize}: NFC, cắt khoảng trắng…), tab, xuống
 *       dòng thành dấu cách, gộp khoảng trắng liền nhau; rỗng thì bỏ qua. Khớp khi tên Sản phẩm CHỨA {@code q}, không
 *       phân biệt hoa thường, không cần dấu ({@code lower(app.f_unaccent(…))} cả hai phía, gộp khoảng trắng;
 *       {@code %} {@code _} {@code \} trong {@code q} được thoát sau khi bỏ dấu nên chỉ khớp đúng chữ đó), hoặc SKU, mã
 *       vạch BẮT ĐẦU bằng {@code q}, không phân biệt hoa thường: ở {@link Scope#PRODUCTS} là mã của bất kỳ Biến thể
 *       chưa xóa mềm nào của Sản phẩm, ở {@link Scope#SELLABLE} là mã của chính Biến thể {@code v}.</li>
 *   <li>Giá lọc theo {@code v.sale_price}, gồm cả hai đầu; {@code minPrice > maxPrice} là {@code 400} trên
 *       {@code minPrice}.</li>
 *   <li>Không đọc bảng của {@code inventory} (AD-2): lọc theo Tồn kho là việc của {@code sales}
 *       ({@code GET /api/v1/sales/sellable?inStock=}). Không có chỉ mục trigram: dữ liệu demo nhỏ.</li>
 * </ul>
 */
final class ProductSearch {

    /** Thông báo lỗi khi {@code sort} không nằm trong danh sách cho phép. */
    static final String SORT_INVALID = "Chỉ sắp xếp được theo name, salePrice hoặc createdAt, kèm asc hoặc desc";

    private static final String MIN_PRICE = "minPrice";
    private static final String MAX_PRICE = "maxPrice";
    private static final String MIN_LABEL = "Giá thấp nhất";
    private static final String MAX_LABEL = "Giá cao nhất";

    /**
     * Tên bỏ dấu rồi mới chữ thường ({@code unaccent} có thể sinh chữ hoa, ví dụ "Æ" → "AE"); thứ tự mặc định. Dùng
     * chung với {@link JdbcVariantLookup}.
     */
    static final String FOLDED_NAME = "lower(app.f_unaccent(p.name))";

    /** Chữ đem so khớp tên: bỏ dấu, chữ thường, gộp khoảng trắng liền nhau (cả chuỗi {@code unaccent} sinh ra). */
    private static final String MATCH_FOLD = "regexp_replace(lower(app.f_unaccent(%s)), '\\s+', ' ', 'g')";

    /**
     * Tên chứa {@code q} (tham số thứ nhất). Mẫu dựng trong SQL: bỏ dấu, chữ thường, gộp khoảng trắng, RỒI mới thoát
     * {@code \ % _}, để ký tự mà {@code unaccent} sinh ra cũng được thoát.
     */
    private static final String NAME_MATCH = MATCH_FOLD.formatted("p.name")
            + " like ('%' || replace(replace(replace(" + MATCH_FOLD.formatted("?")
            + ", '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%') escape '\\'";

    /**
     * SKU, mã vạch của Biến thể có bí danh {@code %1$s} bắt đầu bằng {@code q} (hai tham số: đã viết hoa, đã thoát,
     * có {@code %} ở cuối).
     */
    private static final String CODE_MATCH =
            "upper(%1$s.sku) like ? escape '\\' or upper(%1$s.barcode) like ? escape '\\'";

    /** Danh sách quản lý: mã của BẤT KỲ Biến thể chưa xóa mềm nào; không join thêm dòng nên không lặp Sản phẩm. */
    private static final String ANY_VARIANT_CODE_MATCH = "exists (select 1 from app.product_variant x"
            + " where x.store_id = p.store_id and x.product_id = p.id and x.deleted_at is null"
            + " and (" + CODE_MATCH.formatted("x") + "))";

    /** Hàng còn bán: mã của chính Biến thể {@code v} (quét mã vạch ra đúng Biến thể đó). */
    private static final String OWN_CODE_MATCH = CODE_MATCH.formatted("v");

    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    private ProductSearch() {
    }

    /** Biến thể nào là {@code v} của dòng kết quả. */
    enum Scope {
        /**
         * Màn quản lý Sản phẩm: mỗi Sản phẩm chưa xóa mềm một dòng, {@code v} là Biến thể mặc định (left join), nên
         * lọc giá và sắp theo giá dùng Biến thể mặc định; {@code q} khớp SKU, mã vạch của bất kỳ Biến thể chưa xóa mềm
         * nào ({@code exists}). Lọc trạng thái theo {@code p.status} nếu có.
         */
        PRODUCTS,
        /** Hàng còn bán: mọi Biến thể {@code ACTIVE} chưa xóa mềm của Sản phẩm {@code ACTIVE} chưa xóa mềm. */
        SELLABLE
    }

    /**
     * Điều kiện đã kiểm. {@code query} đã chuẩn hóa, {@code null} khi bỏ trống; {@code status} chỉ dùng ở
     * {@link Scope#PRODUCTS}.
     */
    record Criteria(String query, UUID categoryId, String status, Long minPrice, Long maxPrice) {

        static final Criteria NONE = new Criteria(null, null, null, null, null);
    }

    /** Cột được sắp xếp ({@code sort=<field>,asc|desc}). */
    enum SortField {
        NAME("name"), SALE_PRICE("salePrice"), CREATED_AT("createdAt");

        final String param;

        SortField(String param) {
            this.param = param;
        }
    }

    /** Thứ tự của danh sách Sản phẩm. Mặc định theo tên không dấu, tăng dần. */
    record Sort(SortField field, boolean descending) {

        static final Sort DEFAULT = new Sort(SortField.NAME, false);

        /** {@code ORDER BY} trên {@code p}, {@code v}; luôn kết thúc bằng {@code p.id} để phân trang ổn định. */
        String orderBy() {
            String dir = descending ? "desc" : "asc";
            return switch (field) {
                case NAME -> byName(dir);
                case SALE_PRICE -> "v.sale_price " + dir + " nulls last, " + byName("asc");
                case CREATED_AT -> "p.created_at " + dir + ", p.id " + dir;
            };
        }

        private static String byName(String dir) {
            return FOLDED_NAME + " " + dir + ", lower(p.name) " + dir + ", p.id " + dir;
        }
    }

    /** Phần {@code from … where …} của truy vấn cùng tham số theo thứ tự. */
    record Sql(String text, List<Object> args) {
    }

    // --- Kiểm tham số ------------------------------------------------------------------------------------------

    /** Tham số của {@code GET} danh sách (chuỗi thô từ URL). Lỗi ghi vào {@code errors}, theo từng trường. */
    static Criteria criteria(String q, String categoryId, String status, String minPrice, String maxPrice,
                             FieldErrors errors) {
        Long min = price(minPrice, MIN_PRICE, MIN_LABEL, errors);
        Long max = price(maxPrice, MAX_PRICE, MAX_LABEL, errors);
        checkRange(min, max, errors);
        return new Criteria(query(q), uuid(categoryId, errors), status(status, errors), min, max);
    }

    /** Tham số của {@code VariantLookup.searchForSale}. Giá âm, giá thấp nhất lớn hơn giá cao nhất: {@code 400}. */
    static Criteria forSale(String q, UUID categoryId, Long minPrice, Long maxPrice) {
        FieldErrors errors = new FieldErrors();
        Long min = price(minPrice, MIN_PRICE, MIN_LABEL, errors);
        Long max = price(maxPrice, MAX_PRICE, MAX_LABEL, errors);
        checkRange(min, max, errors);
        errors.throwIfAny();
        return new Criteria(query(q), categoryId, null, min, max);
    }

    /**
     * {@code q} sau khi chuẩn hóa như trường văn bản (bỏ ký tự định dạng vô hình, khoảng trắng Unicode thành dấu cách,
     * NFC, cắt hai đầu). Tab, xuống dòng (chữ dán từ nơi khác: "Sữa\ttươi") thành dấu cách; ký tự điều khiển khác bị
     * bỏ (tên, SKU, mã vạch đã lưu không có, PostgreSQL không nhận NUL); khoảng trắng liền nhau gộp làm một. Rỗng thì
     * {@code null} (bỏ qua).
     */
    static String query(String raw) {
        StringBuilder kept = new StringBuilder();
        TextFields.normalize(raw, false).codePoints()
                .map(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) ? ' ' : c)
                .filter(c -> !Character.isISOControl(c))
                .forEach(kept::appendCodePoint);
        String text = SPACE_RUNS.matcher(kept).replaceAll(" ").strip();
        return text.isEmpty() ? null : text;
    }

    /** {@code sort=<field>[,asc|desc]}; bỏ trống thì {@link Sort#DEFAULT}, giá trị khác thì lỗi trên {@code sort}. */
    static Sort sort(String raw, FieldErrors errors) {
        if (raw == null || raw.isBlank()) {
            return Sort.DEFAULT;
        }
        String[] parts = raw.split(",", -1);
        SortField field = null;
        for (SortField candidate : SortField.values()) {
            if (candidate.param.equals(parts[0].strip())) {
                field = candidate;
            }
        }
        String direction = parts.length == 2 ? parts[1].strip().toLowerCase(Locale.ROOT) : "asc";
        if (field == null || parts.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
            errors.add("sort", SORT_INVALID);
            return Sort.DEFAULT;
        }
        return new Sort(field, direction.equals("desc"));
    }

    private static UUID uuid(String raw, FieldErrors errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        try {
            UUID id = UUID.fromString(text);
            // UUID.fromString nhận cả dạng rút gọn như "1-1-1-1-1"
            if (id.toString().equalsIgnoreCase(text)) {
                return id;
            }
        } catch (IllegalArgumentException notUuid) {
            // báo lỗi bên dưới
        }
        errors.add("categoryId", "Danh mục không hợp lệ");
        return null;
    }

    private static String status(String raw, FieldErrors errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = raw.strip();
        if (status.equals(ProductEntity.ACTIVE) || status.equals(ProductEntity.INACTIVE)) {
            return status;
        }
        errors.add("status", "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE");
        return null;
    }

    /** Số nguyên đồng 0 … {@link ProductService#MONEY_MAX}, như tiền trong body ({@code 1500.0} được nhận). */
    private static Long price(String raw, String field, String label, FieldErrors errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(raw.strip());
        } catch (NumberFormatException notANumber) {
            errors.add(field, label + " phải là số nguyên");
            return null;
        }
        long value = ProductService.wholeNumber(number, ProductService.INVALID, field, label, ProductService.MONEY_MAX,
                errors);
        return value == ProductService.INVALID ? null : value;
    }

    private static Long price(Long raw, String field, String label, FieldErrors errors) {
        if (raw == null) {
            return null;
        }
        long value = ProductService.wholeNumber(BigDecimal.valueOf(raw), ProductService.INVALID, field, label,
                ProductService.MONEY_MAX, errors);
        return value == ProductService.INVALID ? null : value;
    }

    private static void checkRange(Long min, Long max, FieldErrors errors) {
        if (min != null && max != null && min > max) {
            errors.add(MIN_PRICE, MIN_LABEL + " không được lớn hơn " + MAX_LABEL.toLowerCase(Locale.ROOT));
        }
    }

    // --- SQL ---------------------------------------------------------------------------------------------------

    /** {@code from … where …} của các dòng khớp (Sản phẩm {@code p}, Biến thể {@code v}) trong Cửa hàng. */
    static Sql from(Scope scope, UUID storeId, Criteria criteria) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (scope == Scope.PRODUCTS) {
            sql.append("""
                    from app.product p
                    left join app.product_variant v
                           on v.store_id = p.store_id and v.product_id = p.id and v.is_default and v.deleted_at is null
                    where p.store_id = ? and p.deleted_at is null
                    """);
            args.add(storeId);
            if (criteria.status() != null) {
                sql.append(" and p.status = ?\n");
                args.add(criteria.status());
            }
        } else {
            sql.append("""
                    from app.product p
                    join app.product_variant v
                      on v.store_id = p.store_id and v.product_id = p.id and v.deleted_at is null and v.status = ?
                    where p.store_id = ? and p.deleted_at is null and p.status = ?
                    """);
            args.add(ProductEntity.ACTIVE);
            args.add(storeId);
            args.add(ProductEntity.ACTIVE);
        }
        if (criteria.categoryId() != null) {
            sql.append(" and p.category_id = ?\n");
            args.add(criteria.categoryId());
        }
        if (criteria.minPrice() != null) {
            sql.append(" and v.sale_price >= ?\n");
            args.add(criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            sql.append(" and v.sale_price <= ?\n");
            args.add(criteria.maxPrice());
        }
        if (criteria.query() != null) {
            // SKU, mã vạch lưu chữ HOA bằng Locale.ROOT (ProductService.validate): viết hoa q cùng cách
            String codePrefix = escapeLike(criteria.query().toUpperCase(Locale.ROOT)) + "%";
            String codeMatch = scope == Scope.PRODUCTS ? ANY_VARIANT_CODE_MATCH : OWN_CODE_MATCH;
            sql.append(" and (").append(NAME_MATCH).append("\n      or ").append(codeMatch).append(")\n");
            args.add(criteria.query());
            args.add(codePrefix);
            args.add(codePrefix);
        }
        return new Sql(sql.toString(), List.copyOf(args));
    }

    /** Thoát ký tự đặc biệt của {@code LIKE} (thoát bằng {@code \}). */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
