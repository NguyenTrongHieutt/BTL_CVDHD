package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.product.ProductSearch.Sort;
import vn.nhom17.banhang.catalog.internal.product.ProductSearch.SortField;

/** Phần không cần database của {@link ProductSearch}; khớp thật nằm ở {@code ProductSearchIntegrationTest}. */
class ProductSearchTest {

    @Test
    void theQueryIsNormalizedLikeStoredTextAndBlankMeansNoQuery() {
        assertThat(ProductSearch.query(Normalizer.normalize("  Sữa tươi ", Normalizer.Form.NFD))).isEqualTo("Sữa tươi");
        assertThat(ProductSearch.query("\u00A0sua\u3000")).isEqualTo("sua");
        assertThat(ProductSearch.query("su\u200Ba")).isEqualTo("sua");
        // PostgreSQL không nhận NUL; tên đã lưu không có ký tự điều khiển
        assertThat(ProductSearch.query("su\u0000a\t")).isEqualTo("sua");
        // Chữ dán từ nơi khác: tab, xuống dòng thành dấu cách; khoảng trắng liền nhau gộp làm một
        assertThat(ProductSearch.query("Sữa\ttươi")).isEqualTo("Sữa tươi");
        assertThat(ProductSearch.query("Sữa\r\n tươi")).isEqualTo("Sữa tươi");
        assertThat(ProductSearch.query("sua   tuoi \u00A0 vinamilk")).isEqualTo("sua tuoi vinamilk");
        assertThat(ProductSearch.query("sua \u0000 tuoi")).isEqualTo("sua tuoi");
        assertThat(ProductSearch.query("   ")).isNull();
        assertThat(ProductSearch.query("\u0000")).isNull();
        assertThat(ProductSearch.query(null)).isNull();
    }

    @Test
    void sortAcceptsOnlyTheListedFieldsAndDirections() {
        assertThat(sort(null)).isEqualTo(Sort.DEFAULT);
        assertThat(sort(" ")).isEqualTo(Sort.DEFAULT);
        assertThat(sort("name")).isEqualTo(new Sort(SortField.NAME, false));
        assertThat(sort("salePrice,desc")).isEqualTo(new Sort(SortField.SALE_PRICE, true));
        assertThat(sort("createdAt,ASC")).isEqualTo(new Sort(SortField.CREATED_AT, false));
        for (String invalid : new String[] {"password,asc", "name,up", "name,asc,id", "name,", "sale_price,asc"}) {
            FieldErrors errors = new FieldErrors();
            assertThat(ProductSearch.sort(invalid, errors)).isEqualTo(Sort.DEFAULT);
            assertThat(errors.isEmpty()).as(invalid).isFalse();
        }
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(ProductSearch.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }

    private static Sort sort(String raw) {
        FieldErrors errors = new FieldErrors();
        Sort sort = ProductSearch.sort(raw, errors);
        assertThat(errors.isEmpty()).isTrue();
        return sort;
    }
}
