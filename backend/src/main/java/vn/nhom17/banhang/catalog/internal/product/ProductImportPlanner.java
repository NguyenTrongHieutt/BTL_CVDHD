package vn.nhom17.banhang.catalog.internal.product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.category.CategoryEntity;
import vn.nhom17.banhang.catalog.internal.product.ProductController.CreateProductRequest;
import vn.nhom17.banhang.catalog.internal.product.ProductController.VariantInput;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;
import vn.nhom17.banhang.catalog.internal.product.ProductImportService.RowError;
import vn.nhom17.banhang.catalog.internal.product.ProductService.NewProduct;
import vn.nhom17.banhang.catalog.internal.product.ProductService.NewVariantProduct;
import vn.nhom17.banhang.catalog.internal.product.VariantFields.NewVariant;

/** Dựng nhóm và kiểm trường bằng quy tắc catalog; không ghi database. */
final class ProductImportPlanner {

    static final List<ImportColumn> ATTRIBUTE_NAMES = List.of(ImportColumn.ATTRIBUTE_NAME_1,
            ImportColumn.ATTRIBUTE_NAME_2, ImportColumn.ATTRIBUTE_NAME_3);
    static final List<ImportColumn> ATTRIBUTE_VALUES = List.of(ImportColumn.ATTRIBUTE_VALUE_1,
            ImportColumn.ATTRIBUTE_VALUE_2, ImportColumn.ATTRIBUTE_VALUE_3);
    private static final List<ImportColumn> COMMON = List.of(ImportColumn.NAME, ImportColumn.CATEGORY,
            ImportColumn.UNIT, ImportColumn.DESCRIPTION);
    private static final TextFields.Rule CATEGORY = TextFields.Rule.optional("Tên Danh mục", CategoryEntity.NAME_MAX);
    private static final TextFields.Rule PARENT = TextFields.Rule.optional("Mã sản phẩm cha", 50);

    private final ProductService products;

    ProductImportPlanner(ProductService products) {
        this.products = products;
    }

    record Candidate(String parentCode, List<ImportRow> rows, Map<ImportColumn, String> common,
                     NewProduct product, NewVariantProduct variantProduct, String categoryName,
                     List<RowError> errors) {

        boolean valid() {
            return product != null || variantProduct != null;
        }

        List<NewVariant> variants() {
            if (variantProduct != null) {
                return variantProduct.variants();
            }
            if (product == null) {
                return List.of();
            }
            return List.of(new NewVariant(null, Map.of(), product.sku(), product.barcode(), product.salePrice(),
                    product.openingQuantity(), product.openingUnitCost()));
        }

        int firstRow() {
            return rows.getFirst().row();
        }
    }

    List<Candidate> check(List<ImportRow> rows) {
        Map<String, List<ImportRow>> groups = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            String code = parentCode(row);
            groups.computeIfAbsent(code == null ? "row:" + row.row() : "parent:" + code, k -> new ArrayList<>())
                    .add(row);
        }
        return groups.values().stream().map(this::checkGroup).toList();
    }

    private Candidate checkGroup(List<ImportRow> rows) {
        ImportRow first = rows.getFirst();
        String code = parentCode(first);
        List<RowError> errors = new ArrayList<>();
        Map<ImportColumn, String> common = common(rows, errors);
        if (code == null && ProductImportTemplate.isExample(first)) {
            return new Candidate(null, List.copyOf(rows), common, null, null, null,
                    List.of(new RowError(first.row(), null, ProductImportTemplate.EXAMPLE_ROW_MESSAGE)));
        }
        for (ImportRow row : rows) {
            row.cellErrors().forEach((column, message) -> errors.add(error(row, column, message)));
            FieldErrors parentErrors = new FieldErrors();
            TextFields.check(TextFields.normalize(row.texts().get(ImportColumn.PARENT_CODE), false)
                    .toUpperCase(Locale.ROOT), "parentCode", PARENT, parentErrors);
            addFieldErrors(parentErrors, row, errors, false);
        }
        FieldErrors fields = new FieldErrors();
        String category = TextFields.check(common.get(ImportColumn.CATEGORY), "category", CATEGORY, fields);
        NewProduct simple = null;
        NewVariantProduct multi = null;
        if (code == null) {
            if (hasVariantFields(first)) {
                errors.add(error(first, ImportColumn.PARENT_CODE,
                        "Nhập Mã sản phẩm cha để nhóm các Biến thể; Sản phẩm một dòng không có tên, thuộc tính Biến thể"));
            }
            simple = products.check(new CreateProductRequest(common.get(ImportColumn.NAME), null,
                    common.get(ImportColumn.UNIT), common.get(ImportColumn.DESCRIPTION),
                    first.numbers().get(ImportColumn.SALE_PRICE), first.texts().get(ImportColumn.SKU),
                    first.texts().get(ImportColumn.BARCODE), first.numbers().get(ImportColumn.OPENING_QUANTITY),
                    first.numbers().get(ImportColumn.OPENING_UNIT_COST)), fields);
            addFieldErrors(fields, first, errors, false);
        } else {
            List<VariantInput> inputs = rows.stream().map(row -> new VariantInput(
                    row.texts().get(ImportColumn.VARIANT_NAME), attributes(row, errors),
                    row.texts().get(ImportColumn.SKU), row.texts().get(ImportColumn.BARCODE),
                    row.numbers().get(ImportColumn.SALE_PRICE), row.numbers().get(ImportColumn.OPENING_QUANTITY),
                    row.numbers().get(ImportColumn.OPENING_UNIT_COST))).toList();
            multi = products.checkWithVariants(new CreateProductRequest(common.get(ImportColumn.NAME), null,
                    common.get(ImportColumn.UNIT), common.get(ImportColumn.DESCRIPTION), null, null, null, null, null,
                    inputs), fields);
            for (Map<String, String> field : fields.entries()) {
                String name = field.get("field");
                if (name.startsWith("variants[")) {
                    int end = name.indexOf(']');
                    int index = Integer.parseInt(name.substring(9, end));
                    FieldErrors own = new FieldErrors().add(name.substring(end + 2), field.get("message"));
                    addFieldErrors(own, rows.get(index), errors, true);
                } else if ("variants".equals(name)) {
                    errors.add(error(first, ImportColumn.PARENT_CODE, field.get("message")));
                } else {
                    ImportColumn column = ImportColumn.ofField(name);
                    ImportRow source = rows.stream().filter(r -> r.texts().containsKey(column)).findFirst()
                            .orElse(first);
                    addFieldErrors(new FieldErrors().add(name, field.get("message")), source, errors, false);
                }
            }
        }
        if (!errors.isEmpty()) {
            simple = null;
            multi = null;
        }
        return new Candidate(code, List.copyOf(rows), common, simple, multi,
                category == null || category.isEmpty() ? null : category.replaceAll(" {2,}", " "), sorted(errors));
    }

    private static Map<ImportColumn, String> common(List<ImportRow> rows, List<RowError> errors) {
        Map<ImportColumn, String> values = new EnumMap<>(ImportColumn.class);
        Map<ImportColumn, String> keys = new EnumMap<>(ImportColumn.class);
        for (ImportRow row : rows) {
            for (ImportColumn column : COMMON) {
                String value = TextFields.normalize(row.texts().get(column), column == ImportColumn.DESCRIPTION);
                if (value.isEmpty()) {
                    continue;
                }
                // Chỉ gộp dấu cách đã chuẩn hóa; tab/xuống dòng trong tên Danh mục vẫn phải bị báo lỗi.
                String key = column == ImportColumn.CATEGORY
                        ? value.replaceAll(" {2,}", " ").toLowerCase(Locale.ROOT) : value;
                if (keys.putIfAbsent(column, key) == null) {
                    values.put(column, value);
                } else if (!keys.get(column).equals(key)) {
                    errors.add(error(row, column, column.header + " không nhất quán trong cùng nhóm Mã sản phẩm cha"));
                }
            }
        }
        return values;
    }

    static Map<String, String> attributes(ImportRow row, List<RowError> errors) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < ATTRIBUTE_NAMES.size(); i++) {
            ImportColumn nameColumn = ATTRIBUTE_NAMES.get(i);
            String name = TextFields.normalize(row.texts().get(nameColumn), false);
            String value = TextFields.normalize(row.texts().get(ATTRIBUTE_VALUES.get(i)), false);
            if (name.isEmpty() && value.isEmpty()) {
                continue;
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(error(row, nameColumn, "Thuộc tính \"" + name + "\" bị lặp"));
            } else {
                attributes.put(name, value);
            }
        }
        return attributes;
    }

    private static void addFieldErrors(FieldErrors fields, ImportRow row, List<RowError> errors, boolean variant) {
        for (Map<String, String> field : fields.entries()) {
            String name = field.get("field");
            ImportColumn column = variant && "name".equals(name) ? ImportColumn.VARIANT_NAME
                    : "attributes".equals(name) ? attributeErrorColumn(row)
                    : ImportColumn.ofField(name);
            // Lỗi đọc ô thay cho lỗi "bắt buộc" do ô đó chưa đọc được thành số.
            if (column == null || !row.cellErrors().containsKey(column)) {
                errors.add(new RowError(row.row(), column == null ? null : column.header, field.get("message")));
            }
        }
    }

    private static ImportColumn attributeErrorColumn(ImportRow row) {
        for (int i = 0; i < ATTRIBUTE_NAMES.size(); i++) {
            FieldErrors nameErrors = new FieldErrors();
            FieldErrors valueErrors = new FieldErrors();
            String name = TextFields.check(row.texts().get(ATTRIBUTE_NAMES.get(i)), "name",
                    VariantFields.ATTRIBUTE_NAME, nameErrors);
            String value = TextFields.check(row.texts().get(ATTRIBUTE_VALUES.get(i)), "value",
                    VariantFields.ATTRIBUTE_VALUE, valueErrors);
            if (!nameErrors.isEmpty() || name != null && name.isEmpty() && value != null && !value.isEmpty()) {
                return ATTRIBUTE_NAMES.get(i);
            }
            if (!valueErrors.isEmpty() || value != null && value.isEmpty() && name != null && !name.isEmpty()) {
                return ATTRIBUTE_VALUES.get(i);
            }
        }
        return ImportColumn.ATTRIBUTE_NAME_1;
    }

    private static boolean hasVariantFields(ImportRow row) {
        return row.texts().containsKey(ImportColumn.VARIANT_NAME)
                || ATTRIBUTE_NAMES.stream().anyMatch(row.texts()::containsKey)
                || ATTRIBUTE_VALUES.stream().anyMatch(row.texts()::containsKey);
    }

    static String parentCode(ImportRow row) {
        String normalized = TextFields.normalize(row.texts().get(ImportColumn.PARENT_CODE), false)
                .replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    static List<RowError> sorted(List<RowError> errors) {
        return errors.stream().sorted(Comparator.comparingInt(RowError::row).thenComparingInt(e -> {
            ImportColumn column = e.column() == null ? null : ImportColumn.ofHeader(e.column());
            return column == null ? -1 : column.ordinal();
        })).toList();
    }

    private static RowError error(ImportRow row, ImportColumn column, String message) {
        return new RowError(row.row(), column.header, message);
    }
}
