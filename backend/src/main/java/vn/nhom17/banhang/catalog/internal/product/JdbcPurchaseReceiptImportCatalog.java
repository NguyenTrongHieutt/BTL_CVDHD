package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.category.CategoryEntity;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductController.VariantInput;
import vn.nhom17.banhang.catalog.internal.product.ProductImportPlanner.Candidate;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;
import vn.nhom17.banhang.catalog.internal.product.VariantFields.NewVariant;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

/** Catalog implementation of the mixed receipt boundary; never reads or writes inventory tables. */
@Service
class JdbcPurchaseReceiptImportCatalog implements PurchaseReceiptImportCatalog {
    private final ProductService products;
    private final ProductVariantService variants;
    private final CategoryRepository categories;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;
    private final IdempotencyService hashes;

    JdbcPurchaseReceiptImportCatalog(ProductService products, ProductVariantService variants,
            CategoryRepository categories, EntityManager em, JdbcTemplate jdbc, CurrentContext context,
            Clock clock, IdempotencyService hashes) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.em = em;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
        this.hashes = hashes;
    }

    private record Parsed(List<ImportRow> rawRows, List<InputRow> inputs) implements FileData {}
    private record Existing(UUID variantId, UUID productId, String sku, String barcode, String productName,
            String variantName, String unit, String description, String categoryName, long salePrice,
            String status, String productStatus, boolean hasVariants, OffsetDateTime variantUpdated,
            OffsetDateTime productUpdated, OffsetDateTime productDeleted) {
        boolean active() {
            return "ACTIVE".equals(status) && "ACTIVE".equals(productStatus) && productDeleted == null;
        }
    }
    private record Sibling(UUID variantId, UUID productId, String name, String sku, String barcode,
            long salePrice, String status, String attributes, OffsetDateTime updated) {}
    private record Category(UUID id, String name) {}
    private record Group(List<ImportRow> rows, Candidate candidate, Existing anchor,
            Map<Integer, NewVariant> additions, String existingLabel) {}
    private record Plan(List<Row> rows, List<RowError> errors, int existingRows, int newProducts,
            int newVariants, String fingerprint, List<Group> groups) implements Prepared {}
    private record Snapshot(List<Existing> matches, List<Sibling> siblings, List<Category> categories) {}

    @Override
    public FileData read(byte[] content, String filename, List<Edit> edits) {
        return withEdits(ProductImportReader.read(content, filename, ProductImportReader.Profile.RECEIPT), edits);
    }

    @Override
    public void readLarge(java.io.InputStream content, String filename, long bytes,
            java.util.function.BiConsumer<Integer, Object> rows) {
        LargeImportReader.read(content, filename, bytes, ProductImportReader.Profile.RECEIPT, 1000,
                row -> rows.accept(row.row(), row));
    }

    @Override
    public FileData restore(List<tools.jackson.databind.JsonNode> source, List<Edit> edits,
            tools.jackson.databind.json.JsonMapper json) {
        return withEdits(source.stream().map(row -> json.treeToValue(row, ImportRow.class)).toList(), edits);
    }

    private FileData withEdits(List<ImportRow> read, List<Edit> edits) {
        Map<Integer, Edit> patches = new HashMap<>();
        Set<Integer> rowNumbers = new HashSet<>();
        read.forEach(row -> rowNumbers.add(row.row()));
        for (Edit edit : edits == null ? List.<Edit>of() : edits) {
            if (edit == null || !rowNumbers.contains(edit.row()) || edit.values() == null
                    || patches.putIfAbsent(edit.row(), edit) != null) {
                throw FieldErrors.of("edits", "Dòng sửa phải có trong file và chỉ xuất hiện một lần.");
            }
            for (String field : edit.values().keySet()) {
                ImportColumn column = columnOfField(field);
                if (column == null || !ImportColumn.receiptColumns().contains(column)
                        || "name".equals(field) || "category".equals(field)) {
                    throw FieldErrors.of("edits", "Trường sửa không hợp lệ: " + field);
                }
            }
        }
        List<ImportRow> rows = read.stream().map(row -> apply(row, patches.get(row.row()))).toList();
        return new Parsed(rows, rows.stream().map(row -> new InputRow(row.row(), values(row))).toList());
    }

    private static ImportRow apply(ImportRow row, Edit edit) {
        if (edit == null) {
            return row;
        }
        Map<ImportColumn, String> texts = new EnumMap<>(ImportColumn.class);
        texts.putAll(row.texts());
        Map<ImportColumn, BigDecimal> numbers = new EnumMap<>(ImportColumn.class);
        numbers.putAll(row.numbers());
        Map<ImportColumn, String> errors = new EnumMap<>(ImportColumn.class);
        errors.putAll(row.cellErrors());
        edit.values().forEach((field, value) -> {
            ImportColumn column = columnOfField(field);
            texts.remove(column);
            numbers.remove(column);
            errors.remove(column);
            if (!TextFields.normalize(value, true).isEmpty()) {
                if (column.kind == ImportColumn.Kind.TEXT) {
                    texts.put(column, value);
                } else {
                    BigDecimal number = ProductImportReader.parseNumber(value);
                    if (number == null) {
                        texts.put(column, value);
                        errors.put(column, column.header + " phải là số, ví dụ 15000 hoặc 15.000");
                    } else {
                        numbers.put(column, number);
                    }
                }
            }
        });
        return new ImportRow(row.row(), Collections.unmodifiableMap(texts), Collections.unmodifiableMap(numbers),
                Collections.unmodifiableMap(errors));
    }

    @Override
    public Template template(String format) {
        return PurchaseReceiptImportTemplate.build(format);
    }

    @Override
    @Transactional(readOnly = true)
    public Prepared prepare(FileData file) {
        return plan(parsed(file));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared revalidateForSave(FileData file) {
        Parsed parsed = parsed(file);
        List<UUID> productIds = lookup(parsed.rawRows()).stream().map(Existing::productId).distinct().sorted().toList();
        // Match catalog edit/deactivation lock order: product first, then all its variants.
        for (UUID productId : productIds) {
            jdbc.queryForList("select id from app.product where store_id = ? and id = ? for update",
                    UUID.class, context.storeId(), productId);
        }
        for (UUID productId : productIds) {
            jdbc.queryForList("""
                    select id from app.product_variant where store_id = ? and product_id = ?
                    and deleted_at is null order by id for update
                    """, UUID.class, context.storeId(), productId);
        }
        return plan(parsed);
    }

    private Plan plan(Parsed file) {
        List<RowError> errors = new ArrayList<>();
        List<Existing> found = lookup(file.rawRows());
        Map<String, List<Existing>> bySku = new HashMap<>();
        found.forEach(existing -> bySku.computeIfAbsent(existing.sku().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                .add(existing));
        Map<String, List<ImportRow>> skuRows = new HashMap<>();
        Map<String, List<ImportRow>> groups = new LinkedHashMap<>();
        for (ImportRow row : file.rawRows()) {
            FieldErrors fields = new FieldErrors();
            String sku = ProductService.checkSku(row.texts().get(ImportColumn.SKU), "sku", fields);
            if (sku != null && sku.isEmpty()) {
                fields.add("sku", "Nhập SKU để xác định hàng nhập kho");
            }
            TextFields.check(upper(row, ImportColumn.PARENT_CODE), "parentCode",
                    TextFields.Rule.optional("Mã sản phẩm cha", 50), fields);
            addFields(fields, row, false, errors);
            String code = ProductImportPlanner.parentCode(row);
            groups.computeIfAbsent(code == null ? "row:" + row.row() : "parent:" + code, k -> new ArrayList<>()).add(row);
            if (sku != null && !sku.isEmpty()) {
                skuRows.computeIfAbsent(sku, k -> new ArrayList<>()).add(row);
            }
            row.cellErrors().forEach((column, message) -> {
                if (column == ImportColumn.RECEIPT_QUANTITY || column == ImportColumn.RECEIPT_UNIT_COST) {
                    errors.add(error(row, column, message));
                }
            });
        }
        skuRows.forEach((sku, rows) -> {
            if (rows.size() > 1) {
                rows.forEach(row -> errors.add(error(row, ImportColumn.SKU, "SKU " + sku + " bị lặp trong file")));
            }
            if (bySku.getOrDefault(sku, List.of()).size() > 1) {
                rows.forEach(row -> errors.add(error(row, ImportColumn.SKU,
                        "SKU " + sku + " khớp nhiều Biến thể; hãy sửa danh mục trước khi nhập")));
            }
        });
        List<Sibling> siblings = siblings(found.stream().map(Existing::productId).distinct().toList());
        Map<UUID, Map<String, Integer>> namesByProduct = new HashMap<>();
        Map<UUID, Integer> additionsByProduct = new HashMap<>();
        for (Sibling sibling : siblings) {
            if (sibling.name() != null) {
                namesByProduct.computeIfAbsent(sibling.productId(), k -> new HashMap<>())
                        .put(sibling.name().toLowerCase(Locale.ROOT), 0);
            }
        }
        List<Group> planned = new ArrayList<>();
        List<Row> preview = new ArrayList<>();
        List<Category> categorySnapshot = categorySnapshot();
        for (List<ImportRow> rows : groups.values()) {
            Set<UUID> anchors = new HashSet<>();
            rows.forEach(row -> bySku.getOrDefault(upper(row, ImportColumn.SKU), List.of())
                    .forEach(match -> anchors.add(match.productId())));
            if (anchors.size() > 1) {
                rows.forEach(row -> errors.add(error(row, ImportColumn.PARENT_CODE,
                        "Nhóm Mã sản phẩm cha chứa SKU thuộc nhiều Sản phẩm khác nhau")));
            }
            Existing anchor = rows.stream().map(row -> match(row, bySku)).filter(Objects::nonNull).findFirst().orElse(null);
            Candidate candidate = null;
            Map<Integer, NewVariant> additions = new LinkedHashMap<>();
            String existingLabel = null;
            if (anchor == null && anchors.isEmpty()) {
                candidate = new ProductImportPlanner(products).check(rows.stream().map(this::productRow).toList())
                        .getFirst();
                candidate.errors().forEach(e -> errors.add(new RowError(e.row(), e.column(), e.message())));
                Map<String, Integer> newNames = new HashMap<>();
                for (int i = 0; i < candidate.variants().size(); i++) {
                    NewVariant variant = candidate.variants().get(i);
                    if (variant.name() != null) {
                        checkName(rows.get(i), variant.name(), newNames, errors);
                    }
                }
                if (candidate.categoryName() != null) {
                    long matches = categorySnapshot.stream().filter(c -> ProductImportService.categoryKey(c.name())
                            .equals(ProductImportService.categoryKey(candidateCategory(rows)))).count();
                    if (matches > 1) {
                        errors.add(error(rows.getFirst(), ImportColumn.CATEGORY,
                                "Tên Danh mục khớp nhiều Danh mục; hãy sửa danh mục trước khi nhập"));
                    }
                }
            } else if (anchor != null) {
                Map<String, Integer> names = namesByProduct.computeIfAbsent(anchor.productId(), k -> new HashMap<>());
                boolean adding = rows.stream().anyMatch(row -> match(row, bySku) == null);
                if (adding && !anchor.hasVariants()) {
                    ImportRow existingRow = rows.stream().filter(row -> match(row, bySku) != null).findFirst().orElseThrow();
                    FieldErrors fields = new FieldErrors();
                    List<ProductImportService.RowError> attributeErrors = new ArrayList<>();
                    VariantFields.Label label = VariantFields.label(existingRow.texts().get(ImportColumn.VARIANT_NAME),
                            ProductImportPlanner.attributes(existingRow, attributeErrors), "", true, fields);
                    attributeErrors.forEach(e -> errors.add(new RowError(e.row(), e.column(), e.message())));
                    addFields(fields, existingRow, true, errors);
                    if (label != null) {
                        existingLabel = label.name();
                        checkName(existingRow, existingLabel, names, errors);
                    }
                }
                for (ImportRow row : rows) {
                    Existing existing = match(row, bySku);
                    if (existing != null) {
                        if (!existing.active()) {
                            errors.add(error(row, ImportColumn.SKU, "Sản phẩm hoặc Biến thể đã ngừng kinh doanh"));
                        }
                    } else {
                        row.cellErrors().forEach((column, message) -> errors.add(error(row, column, message)));
                        FieldErrors fields = new FieldErrors();
                        List<ProductImportService.RowError> attributeErrors = new ArrayList<>();
                        NewVariant variant = VariantFields.check(new VariantInput(row.texts().get(ImportColumn.VARIANT_NAME),
                                ProductImportPlanner.attributes(row, attributeErrors), row.texts().get(ImportColumn.SKU),
                                row.texts().get(ImportColumn.BARCODE), row.numbers().get(ImportColumn.SALE_PRICE),
                                BigDecimal.ZERO, BigDecimal.ZERO), "", fields);
                        attributeErrors.forEach(e -> errors.add(new RowError(e.row(), e.column(), e.message())));
                        addFields(fields, row, true, errors);
                        if (variant != null) {
                            additions.put(row.row(), variant);
                            checkName(row, variant.name(), names, errors);
                        }
                    }
                }
                long existingCount = siblings.stream().filter(s -> s.productId().equals(anchor.productId())).count();
                int addingCount = (int) rows.stream().filter(row -> match(row, bySku) == null).count();
                int totalAdditions = additionsByProduct.merge(anchor.productId(), addingCount, Integer::sum);
                if (addingCount > 0 && existingCount + totalAdditions > VariantFields.MAX_VARIANTS) {
                    errors.add(error(rows.getFirst(), ImportColumn.PARENT_CODE,
                            "Sản phẩm có tối đa " + VariantFields.MAX_VARIANTS + " Biến thể"));
                }
            }
            Group group = new Group(List.copyOf(rows), candidate, anchor, Collections.unmodifiableMap(additions), existingLabel);
            planned.add(group);
            for (int i = 0; i < rows.size(); i++) {
                ImportRow row = rows.get(i);
                Existing existing = match(row, bySku);
                NewVariant variant = candidate != null && i < candidate.variants().size()
                        ? candidate.variants().get(i) : additions.get(row.row());
                preview.add(previewRow(row, group, existing, variant));
            }
        }
        checkBarcodes(preview, errors);
        // A product group is indivisible and the whole receipt is blocked by any error.
        for (Group group : planned) {
            Set<Integer> invalid = new HashSet<>();
            errors.forEach(e -> invalid.add(e.row()));
            if (group.rows().size() > 1 && group.rows().stream().anyMatch(row -> invalid.contains(row.row()))) {
                group.rows().stream().filter(row -> !invalid.contains(row.row())).forEach(row -> errors.add(error(row,
                        ImportColumn.PARENT_CODE, "Nhóm có dòng lỗi; sửa toàn bộ nhóm trước khi nhập kho")));
            }
        }
        List<RowError> sorted = errors.stream().distinct().sorted(Comparator.comparingInt(RowError::row)
                .thenComparing(e -> e.column() == null ? "" : e.column()).thenComparing(RowError::message)).toList();
        return new Plan(preview.stream().sorted(Comparator.comparingInt(Row::row)).toList(), sorted,
                (int) preview.stream().filter(row -> row.action() == Action.EXISTING).count(),
                (int) planned.stream().filter(group -> group.anchor() == null).count(),
                (int) preview.stream().filter(row -> row.action() != Action.EXISTING).count(),
                hashes.hash(new Snapshot(found, siblings, categorySnapshot)), List.copyOf(planned));
    }

    private static String candidateCategory(List<ImportRow> rows) {
        return rows.stream().map(row -> normalized(row, ImportColumn.CATEGORY)).filter(Objects::nonNull)
                .findFirst().orElse("");
    }

    private ImportRow productRow(ImportRow row) {
        Map<ImportColumn, BigDecimal> numbers = new EnumMap<>(ImportColumn.class);
        numbers.putAll(row.numbers());
        numbers.remove(ImportColumn.RECEIPT_QUANTITY);
        numbers.remove(ImportColumn.RECEIPT_UNIT_COST);
        numbers.put(ImportColumn.OPENING_QUANTITY, BigDecimal.ZERO);
        numbers.put(ImportColumn.OPENING_UNIT_COST, BigDecimal.ZERO);
        Map<ImportColumn, String> errors = new EnumMap<>(ImportColumn.class);
        errors.putAll(row.cellErrors());
        errors.remove(ImportColumn.RECEIPT_QUANTITY);
        errors.remove(ImportColumn.RECEIPT_UNIT_COST);
        return new ImportRow(row.row(), row.texts(), numbers, errors);
    }

    private Row previewRow(ImportRow row, Group group, Existing existing, NewVariant variant) {
        Map<String, String> values = new LinkedHashMap<>(values(row));
        Existing metadata = existing == null ? group.anchor() : existing;
        String productName = metadata != null ? metadata.productName()
                : group.candidate() == null ? normalized(row, ImportColumn.NAME)
                : group.candidate().common().get(ImportColumn.NAME);
        String variantName = existing != null ? existing.variantName()
                : variant != null ? variant.name() : normalized(row, ImportColumn.VARIANT_NAME);
        if (existing != null && group.existingLabel() != null && !existing.hasVariants()
                && existing.productId().equals(group.anchor().productId())) {
            variantName = group.existingLabel();
        }
        if (metadata != null) {
            values.put("productName", metadata.productName());
            values.put("categoryName", metadata.categoryName());
            values.put("unit", metadata.unit());
            values.put("description", metadata.description());
        }
        if (existing != null) {
            values.put("salePrice", Long.toString(existing.salePrice()));
            values.put("barcode", existing.barcode());
        }
        values.put("productName", productName);
        values.put("variantName", variantName);
        values.put("sku", upper(row, ImportColumn.SKU));
        values.put("parentCode", ProductImportPlanner.parentCode(row));
        return new Row(row.row(), upper(row, ImportColumn.SKU), ProductImportPlanner.parentCode(row), productName,
                variantName, row.numbers().get(ImportColumn.RECEIPT_QUANTITY),
                row.numbers().get(ImportColumn.RECEIPT_UNIT_COST), existing != null ? Action.EXISTING
                : group.anchor() == null ? Action.NEW_PRODUCT : Action.NEW_VARIANT,
                Collections.unmodifiableMap(values), existing == null ? null : existing.variantId(),
                metadata == null ? null : metadata.productId());
    }

    private void checkBarcodes(List<Row> rows, List<RowError> errors) {
        Map<String, Integer> fileCodes = new HashMap<>();
        for (Row row : rows) {
            if (row.action() == Action.EXISTING) {
                continue;
            }
            String code = TextFields.normalize(row.values().get("barcode"), false).toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            Integer prior = fileCodes.putIfAbsent(code, row.row());
            if (prior != null) {
                errors.add(new RowError(row.row(), ImportColumn.BARCODE.header, "Mã vạch trùng với dòng " + prior));
                errors.add(new RowError(prior, ImportColumn.BARCODE.header, "Mã vạch trùng với dòng " + row.row()));
            }
            Integer count = jdbc.queryForObject("""
                    select count(*) from app.product_variant where store_id = ? and deleted_at is null
                    and upper(barcode) = ?
                    """, Integer.class, context.storeId(), code);
            if (count != null && count > 0) {
                errors.add(new RowError(row.row(), ImportColumn.BARCODE.header, "Mã vạch đã có trong danh mục"));
            }
        }
    }

    private static void checkName(ImportRow row, String name, Map<String, Integer> names, List<RowError> errors) {
        Integer prior = names.putIfAbsent(name.toLowerCase(Locale.ROOT), row.row());
        if (prior != null) {
            errors.add(error(row, ImportColumn.VARIANT_NAME, prior == 0 ? "Tên Biến thể đã có trong Sản phẩm"
                    : "Tên Biến thể trùng với dòng " + prior));
        }
    }

    private List<Existing> lookup(List<ImportRow> rows) {
        Object[] skus = rows.stream().map(row -> upper(row, ImportColumn.SKU)).filter(Objects::nonNull).distinct().toArray();
        if (skus.length == 0) {
            return List.of();
        }
        return jdbc.query("""
                select v.id, v.product_id, v.sku, v.barcode, v.name as variant_name, v.sale_price, v.status,
                       v.updated_at as variant_updated, p.name as product_name, p.unit, p.description,
                       p.status as product_status, p.has_variants, p.updated_at as product_updated,
                       p.deleted_at as product_deleted, c.name as category_name
                from app.product_variant v join app.product p on p.store_id = v.store_id and p.id = v.product_id
                left join app.category c on c.store_id = p.store_id and c.id = p.category_id
                where v.store_id = ? and v.deleted_at is null and upper(v.sku) = any(?) order by v.id
                """, (PreparedStatement ps) -> {
                    ps.setObject(1, context.storeId());
                    ps.setArray(2, ps.getConnection().createArrayOf("text", skus));
                }, (rs, i) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("sku"), rs.getString("barcode"), rs.getString("product_name"),
                        rs.getString("variant_name"), rs.getString("unit"), rs.getString("description"),
                        rs.getString("category_name"), rs.getLong("sale_price"), rs.getString("status"),
                        rs.getString("product_status"), rs.getBoolean("has_variants"),
                        rs.getObject("variant_updated", OffsetDateTime.class),
                        rs.getObject("product_updated", OffsetDateTime.class),
                        rs.getObject("product_deleted", OffsetDateTime.class)));
    }

    private List<Sibling> siblings(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select id, product_id, name, sku, barcode, sale_price, status, attributes::text, updated_at
                from app.product_variant where store_id = ? and product_id = any(?) and deleted_at is null order by id
                """, (PreparedStatement ps) -> {
                    ps.setObject(1, context.storeId());
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", productIds.toArray()));
                }, (rs, i) -> new Sibling(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("name"), rs.getString("sku"), rs.getString("barcode"), rs.getLong("sale_price"),
                        rs.getString("status"), rs.getString("attributes"), rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private List<Category> categorySnapshot() {
        return jdbc.query("select id, name from app.category where store_id = ? order by id",
                (rs, i) -> new Category(rs.getObject("id", UUID.class), rs.getString("name")), context.storeId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Integer, UUID> create(Prepared prepared) {
        if (!(prepared instanceof Plan plan) || !plan.errors().isEmpty()) {
            throw new IllegalArgumentException("A valid catalog import plan is required");
        }
        Map<Integer, UUID> ids = new LinkedHashMap<>();
        plan.rows().stream().filter(row -> row.variantId() != null).forEach(row -> ids.put(row.row(), row.variantId()));
        Map<String, UUID> categoryIds = new HashMap<>();
        categorySnapshot().forEach(c -> categoryIds.put(ProductImportService.categoryKey(c.name()), c.id()));
        Set<String> reserved = new HashSet<>();
        plan.rows().forEach(row -> reserved.add(row.sku()));
        for (Group group : plan.groups()) {
            if (group.candidate() != null) {
                Candidate candidate = group.candidate();
                UUID categoryId = null;
                if (candidate.categoryName() != null) {
                    String key = ProductImportService.categoryKey(candidate.categoryName());
                    categoryId = categoryIds.get(key);
                    if (categoryId == null) {
                        categoryId = createCategory(candidate.categoryName());
                        categoryIds.put(key, categoryId);
                    }
                }
                if (candidate.variantProduct() != null) {
                    products.insertWithVariantsInCurrentTransaction(candidate.variantProduct().withCategory(categoryId),
                            reserved::contains);
                } else {
                    products.insertInCurrentTransaction(candidate.product().withCategory(categoryId), reserved::contains);
                }
                em.clear();
                for (ImportRow row : group.rows()) {
                    UUID id = jdbc.queryForObject("""
                            select id from app.product_variant where store_id = ? and sku = ? and deleted_at is null
                            """, UUID.class, context.storeId(), upper(row, ImportColumn.SKU));
                    ids.put(row.row(), id);
                }
            } else if (group.anchor() != null) {
                String label = group.existingLabel();
                for (Map.Entry<Integer, NewVariant> addition : group.additions().entrySet()) {
                    UUID id = variants.insertInCurrentTransaction(new ProductVariantService.NewAddition(
                            group.anchor().productId(), label, addition.getValue()));
                    ids.put(addition.getKey(), id);
                    label = null;
                }
            }
        }
        return Map.copyOf(ids);
    }

    private UUID createCategory(String name) {
        CategoryEntity category = new CategoryEntity(name, clock.instant());
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, CategoryEntity.NAME_UNIQUE)) {
                throw ProblemException.conflict("/errors/catalog/category-name-taken", "Tên Danh mục đã có",
                        "Danh mục vừa được tạo ở nơi khác. Không dòng nào được nhập; hãy xem trước lại.");
            }
            throw e;
        }
        return category.id();
    }

    private static Parsed parsed(FileData file) {
        if (!(file instanceof Parsed parsed)) {
            throw new IllegalArgumentException("Use the catalog file reader before preparing an import");
        }
        return parsed;
    }

    private static Existing match(ImportRow row, Map<String, List<Existing>> bySku) {
        List<Existing> matches = bySku.getOrDefault(upper(row, ImportColumn.SKU), List.of());
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static void addFields(FieldErrors fields, ImportRow row, boolean variant, List<RowError> errors) {
        List<ProductImportService.RowError> mapped = new ArrayList<>();
        ProductImportPlanner.addFieldErrors(fields, row, mapped, variant);
        mapped.forEach(e -> errors.add(new RowError(e.row(), e.column(), e.message())));
    }

    private static RowError error(ImportRow row, ImportColumn column, String message) {
        return new RowError(row.row(), column.header, message);
    }

    private static String normalized(ImportRow row, ImportColumn column) {
        String value = TextFields.normalize(row.texts().get(column), column == ImportColumn.DESCRIPTION);
        return value.isEmpty() ? null : value;
    }

    private static String upper(ImportRow row, ImportColumn column) {
        String value = normalized(row, column);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static ImportColumn columnOfField(String field) {
        return "productName".equals(field) ? ImportColumn.NAME
                : "categoryName".equals(field) ? ImportColumn.CATEGORY : ImportColumn.ofField(field);
    }

    private static Map<String, String> values(ImportRow row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ImportColumn column : ImportColumn.receiptColumns()) {
            String field = column == ImportColumn.NAME ? "productName"
                    : column == ImportColumn.CATEGORY ? "categoryName" : column.field;
            BigDecimal number = row.numbers().get(column);
            values.put(field, number == null ? row.texts().get(column) : number.toPlainString());
        }
        return Collections.unmodifiableMap(values);
    }
}
