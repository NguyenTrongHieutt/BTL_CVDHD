package vn.nhom17.banhang.catalog.internal.product;

import java.util.List;
import java.util.Locale;
import vn.nhom17.banhang.catalog.internal.TextFields;

/**
 * Các cột của file nhập Sản phẩm từ Excel (FR-6, SP-06), theo thứ tự của file mẫu. File người dùng gửi được đọc theo
 * <b>tên tiêu đề</b> ở dòng 1, không theo vị trí: cột có thể đổi chỗ, thiếu cột không bắt buộc, có thêm cột lạ (bỏ qua).
 *
 * <p>{@link #field} là tên trường của {@code POST /api/v1/catalog/products} mà cột đổ vào (lỗi của
 * {@link ProductService#check} theo trường được đổi về cột); Danh mục nhập theo tên nên không có trường tương ứng.
 */
enum ImportColumn {

    NAME("Tên Sản phẩm", true, Kind.TEXT, "name",
            "Bắt buộc. Tối đa 200 ký tự."),
    CATEGORY("Danh mục", false, Kind.TEXT, ImportColumn.CATEGORY_FIELD,
            "Tên Danh mục, tối đa 100 ký tự. Không phân biệt chữ hoa, chữ thường; chưa có thì hệ thống tạo mới. "
                    + "Để trống: không thuộc Danh mục nào."),
    UNIT("Đơn vị tính", false, Kind.TEXT, "unit",
            "Tối đa 30 ký tự. Để trống: \"cái\"."),
    SALE_PRICE("Giá bán", true, Kind.NUMBER, "salePrice",
            "Bắt buộc. Số nguyên đồng, không âm, ví dụ 15000 hoặc 15.000."),
    // Form thêm Sản phẩm và câu báo lỗi gọi trường này là "Tồn kho đầu kỳ": nhận cả tiêu đề đó
    OPENING_QUANTITY("Tồn đầu kỳ", false, Kind.NUMBER, "openingQuantity",
            "Số hàng đang có, số nguyên không âm. Để trống: 0.", "Tồn kho đầu kỳ"),
    OPENING_UNIT_COST("Giá vốn ban đầu", false, Kind.NUMBER, "openingUnitCost",
            "Giá vốn của một đơn vị, số nguyên đồng. Bắt buộc lớn hơn 0 khi Tồn đầu kỳ lớn hơn 0."),
    SKU("SKU", false, Kind.TEXT, "sku",
            "Tối đa 50 ký tự, lưu chữ HOA, không trùng Sản phẩm đã có hay dòng khác. Để trống: hệ thống tự sinh."),
    BARCODE("Mã vạch", false, Kind.TEXT, "barcode",
            "Tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số; không trùng Sản phẩm đã có hay dòng khác."),
    DESCRIPTION("Mô tả", false, Kind.TEXT, "description",
            "Tối đa 2000 ký tự, được xuống dòng.");

    /** Ô chữ hay ô số (tiền, số lượng). */
    enum Kind { TEXT, NUMBER }

    /** Trường giả của cột Danh mục khi kiểm tên Danh mục bằng {@link TextFields}. */
    static final String CATEGORY_FIELD = "category";

    /** Tiêu đề trong file mẫu; cột bắt buộc có thêm dấu {@code *} ({@link #templateHeader}). */
    final String header;
    final boolean required;
    final Kind kind;
    final String field;
    /** Ghi chú của ô tiêu đề trong file mẫu. */
    final String note;
    /** Tiêu đề khác cũng nhận là cột này (cùng cách so của {@link #headerKey}); có cả hai trong file là cột lặp. */
    final List<String> aliases;

    ImportColumn(String header, boolean required, Kind kind, String field, String note, String... aliases) {
        this.header = header;
        this.required = required;
        this.kind = kind;
        this.field = field;
        this.note = note;
        this.aliases = List.of(aliases);
    }

    String templateHeader() {
        return required ? header + "*" : header;
    }

    /** Cột ứng với trường {@code field} của lỗi kiểm; {@code null} khi không có. */
    static ImportColumn ofField(String field) {
        for (ImportColumn column : values()) {
            if (column.field.equals(field)) {
                return column;
            }
        }
        return null;
    }

    /** Cột có tiêu đề {@code raw} (bỏ {@code *} ở cuối, khoảng trắng thừa, không phân biệt hoa thường); {@code null}. */
    static ImportColumn ofHeader(String raw) {
        String key = headerKey(raw);
        for (ImportColumn column : values()) {
            if (headerKey(column.header).equals(key)
                    || column.aliases.stream().anyMatch(alias -> headerKey(alias).equals(key))) {
                return column;
            }
        }
        return null;
    }

    /** Chuẩn hóa như trường văn bản ({@link TextFields#normalize}), bỏ các {@code *} ở cuối, gộp khoảng trắng. */
    static String headerKey(String raw) {
        String text = TextFields.normalize(raw, false);
        while (text.endsWith("*")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
