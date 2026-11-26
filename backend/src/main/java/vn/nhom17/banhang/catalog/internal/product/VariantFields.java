package vn.nhom17.banhang.catalog.internal.product;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.product.ProductController.VariantInput;
import vn.nhom17.banhang.catalog.internal.product.ProductVariantController.UpdateVariantRequest;

/**
 * Kiểm, chuẩn hóa các trường của một Biến thể (FR-7, SP-07), dùng chung cho tạo Sản phẩm kèm Biến thể, thêm và sửa
 * Biến thể. SKU, mã vạch, giá bán, Tồn kho đầu kỳ, Giá vốn ban đầu dùng đúng quy tắc của SP-01 ({@link ProductService});
 * ở đây thêm nhãn và thuộc tính:
 *
 * <ul>
 *   <li>{@code attributes}: 0–{@value #MAX_ATTRIBUTES} cặp tên → giá trị, mỗi chuỗi tối đa
 *       {@value #ATTRIBUTE_MAX} ký tự, chuẩn hóa như trường một dòng của catalog ({@link TextFields}). Cặp mà cả tên lẫn
 *       giá trị đều trống thì bỏ qua; thiếu một bên, trùng tên (không phân biệt hoa thường) là lỗi.</li>
 *   <li>{@code name} (nhãn): tối đa {@value #LABEL_MAX} ký tự; trống thì ghép giá trị thuộc tính theo thứ tự gửi lên
 *       bằng {@value #LABEL_SEPARATOR}. Sản phẩm nhiều Biến thể: mỗi Biến thể phải có nhãn (nhập hoặc ghép được).
 *       Sản phẩm một Biến thể: Biến thể mặc định không có nhãn, không có thuộc tính.</li>
 *   <li>Trùng nhãn trong cùng Sản phẩm không kiểm ở đây: unique {@code product_variant_name_uq} bắt
 *       ({@code 409 /errors/catalog/variant-name-taken}).</li>
 * </ul>
 */
final class VariantFields {

    static final int MIN_VARIANTS = 2;
    static final int MAX_VARIANTS = 50;
    static final int MAX_ATTRIBUTES = 3;
    static final int LABEL_MAX = 100;
    static final int ATTRIBUTE_MAX = 50;
    static final String LABEL_SEPARATOR = " / ";

    static final TextFields.Rule LABEL = TextFields.Rule.optional("Nhãn Biến thể", LABEL_MAX);
    static final TextFields.Rule ATTRIBUTE_NAME = TextFields.Rule.optional("Tên thuộc tính", ATTRIBUTE_MAX);
    static final TextFields.Rule ATTRIBUTE_VALUE = TextFields.Rule.optional("Giá trị thuộc tính", ATTRIBUTE_MAX);

    static final String LABEL_REQUIRED = "Nhập nhãn hoặc thuộc tính của Biến thể";
    static final String SINGLE_HAS_NO_LABEL =
            "Sản phẩm chỉ có một Biến thể nên Biến thể không có nhãn, thuộc tính. Hãy thêm Biến thể trước.";

    private VariantFields() {
    }

    /** Nhãn và thuộc tính đã chuẩn hóa; {@code name} là {@code null} khi không có nhãn (Sản phẩm một Biến thể). */
    record Label(String name, Map<String, String> attributes) {

        static final Label NONE = new Label(null, Map.of());
    }

    /**
     * Biến thể mới đã kiểm và chuẩn hóa: {@code sku}, {@code barcode} là {@code null} khi bỏ trống ({@code sku} trống
     * thì sinh khi tạo); SKU, mã vạch chữ HOA.
     */
    record NewVariant(String name, Map<String, String> attributes, String sku, String barcode, long salePrice,
                      int openingQuantity, long openingUnitCost) {
    }

    /**
     * Sửa một Biến thể, đã kiểm: {@code sku} {@code null} là giữ SKU hiện tại, {@code barcode} {@code null} là xóa mã
     * vạch.
     */
    record VariantEdit(Label label, String sku, String barcode, long salePrice) {
    }

    /**
     * Kiểm một Biến thể mới của Sản phẩm nhiều Biến thể (phải có nhãn), ghi lỗi vào {@code errors} với tên trường
     * {@code prefix + "<trường>"} (ví dụ {@code variants[1].sku}). Trả {@code null} khi Biến thể này có lỗi.
     */
    static NewVariant check(VariantInput input, String prefix, FieldErrors errors) {
        if (input == null) {
            errors.add(prefix.isEmpty() ? "variants" : prefix.substring(0, prefix.length() - 1),
                    "Nhập thông tin Biến thể");
            return null;
        }
        int before = errors.entries().size();
        Label label = label(input.name(), input.attributes(), prefix, true, errors);
        String sku = ProductService.checkSku(input.sku(), prefix + "sku", errors);
        String barcode = ProductService.checkBarcode(input.barcode(), prefix + "barcode", errors);
        long salePrice = ProductService.checkSalePrice(input.salePrice(), prefix + "salePrice", errors);
        ProductService.Opening opening = ProductService.checkOpening(input.openingQuantity(),
                input.openingUnitCost(), prefix, errors);
        if (errors.entries().size() > before) {
            return null;
        }
        return new NewVariant(label.name(), label.attributes(), emptyToNull(sku), emptyToNull(barcode), salePrice,
                opening.quantity(), opening.unitCost());
    }

    /**
     * Kiểm body sửa Biến thể ({@code PUT /{id}/variants/{variantId}}); {@code multi}: Sản phẩm có nhiều Biến thể (phải
     * có nhãn) hay một Biến thể (không được có nhãn, thuộc tính). Có lỗi thì ném {@code 400}, mọi trường trong một lần.
     */
    static VariantEdit checkEdit(UpdateVariantRequest request, boolean multi) {
        FieldErrors errors = new FieldErrors();
        Label label = label(request.name(), request.attributes(), "", multi, errors);
        String sku = ProductService.checkSku(request.sku(), "sku", errors);
        String barcode = ProductService.checkBarcode(request.barcode(), "barcode", errors);
        long salePrice = ProductService.checkSalePrice(request.salePrice(), "salePrice", errors);
        errors.throwIfAny();
        return new VariantEdit(label, emptyToNull(sku), emptyToNull(barcode), salePrice);
    }

    /**
     * Nhãn và thuộc tính. {@code multi}: nhãn bắt buộc (trống thì ghép giá trị thuộc tính); không {@code multi}: nhãn,
     * thuộc tính phải trống, trả {@link Label#NONE}. Trả {@code null} sau khi ghi lỗi.
     */
    static Label label(String rawName, Map<String, String> rawAttributes, String prefix, boolean multi,
                       FieldErrors errors) {
        String name = TextFields.check(rawName, prefix + "name", LABEL, errors);
        Map<String, String> attributes = attributes(rawAttributes, prefix + "attributes", errors);
        if (name == null || attributes == null) {
            return null;
        }
        if (!multi) {
            if (!name.isEmpty()) {
                errors.add(prefix + "name", SINGLE_HAS_NO_LABEL);
                return null;
            }
            if (!attributes.isEmpty()) {
                errors.add(prefix + "attributes", SINGLE_HAS_NO_LABEL);
                return null;
            }
            return Label.NONE;
        }
        if (name.isEmpty()) {
            if (attributes.isEmpty()) {
                errors.add(prefix + "name", LABEL_REQUIRED);
                return null;
            }
            name = String.join(LABEL_SEPARATOR, attributes.values());
            if (TextFields.length(name) > LABEL_MAX) {
                errors.add(prefix + "name", "Nhãn ghép từ giá trị thuộc tính quá " + LABEL_MAX
                        + " ký tự. Hãy nhập nhãn ngắn hơn");
                return null;
            }
        }
        return new Label(name, attributes);
    }

    /** Thuộc tính đã chuẩn hóa, giữ thứ tự gửi lên; {@code null} sau khi ghi lỗi (chỉ lỗi đầu tiên). */
    private static Map<String, String> attributes(Map<String, String> raw, String field, FieldErrors errors) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return Collections.unmodifiableMap(result);
        }
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> pair : raw.entrySet()) {
            FieldErrors own = new FieldErrors();
            String key = TextFields.check(pair.getKey(), field, ATTRIBUTE_NAME, own);
            String value = TextFields.check(pair.getValue(), field, ATTRIBUTE_VALUE, own);
            if (!own.isEmpty()) {
                errors.add(field, own.entries().getFirst().get("message"));
                return null;
            }
            if (key.isEmpty() && value.isEmpty()) {
                continue;
            }
            if (key.isEmpty()) {
                errors.add(field, "Nhập tên thuộc tính cho giá trị \"" + value + "\"");
                return null;
            }
            if (value.isEmpty()) {
                errors.add(field, "Nhập giá trị của thuộc tính \"" + key + "\"");
                return null;
            }
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                errors.add(field, "Thuộc tính \"" + key + "\" bị lặp");
                return null;
            }
            result.put(key, value);
        }
        if (result.size() > MAX_ATTRIBUTES) {
            errors.add(field, "Tối đa " + MAX_ATTRIBUTES + " thuộc tính");
            return null;
        }
        return Collections.unmodifiableMap(result);
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }
}
