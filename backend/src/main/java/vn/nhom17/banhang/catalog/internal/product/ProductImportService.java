package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.category.CategoryEntity;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;
import vn.nhom17.banhang.catalog.internal.product.ProductImportPlanner.Candidate;
import vn.nhom17.banhang.catalog.internal.product.VariantFields.NewVariant;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Nhập Sản phẩm từ XLSX hoặc CSV UTF-8 (FR-6, SP-06), chỉ Chủ cửa hàng.
 *
 * <ol>
 *   <li><b>Tệp</b> ({@link #read}, ngoài transaction): tối đa {@value #MAX_BYTES} byte ({@code 413
 *       /errors/common/payload-too-large}), rồi {@link ProductImportReader} (định dạng, tiêu đề, số dòng:
 *       {@code 400 /errors/catalog/import-invalid-file}). Lỗi tệp báo trước lỗi thiếu {@code Idempotency-Key}.</li>
 *   <li><b>Chứng từ</b>: {@link IdempotencyService} loại {@value #DOCUMENT_TYPE}, băm theo SHA-256 nội dung file.
 *       Gửi lại cùng khóa, cùng file: trả kết quả lần đầu (lưu ở {@code app.product_import}), không nhập lại; cùng
 *       khóa, file khác: {@code 409 idempotency-key-reuse}.</li>
 *   <li><b>Kiểm mọi dòng trước khi ghi</b>, trong transaction của lần nhập: quy tắc trường của SP-01
 *       ({@link ProductService#check}); tên Danh mục; SKU, mã vạch trùng với Biến thể chưa xóa mềm của Cửa hàng hoặc
 *       với một dòng hợp lệ đứng trước trong file (dòng trước thắng; dòng có lỗi khác không giữ mã). Dòng có lỗi được
 *       báo kèm số dòng Excel, cột, lý do và không nhập. Dòng ví dụ của file mẫu còn nguyên
 *       ({@link ProductImportTemplate#isExample}) cũng không nhập, báo lỗi không có cột.</li>
 *   <li><b>Ghi mọi dòng hợp lệ trong MỘT transaction</b>: Danh mục chưa có được tạo một lần cho mỗi tên (không phân
 *       biệt hoa thường, khoảng trắng thừa; chỉ khi có dòng hợp lệ dùng), rồi từng Sản phẩm qua
 *       {@link ProductService#insertInCurrentTransaction} (Biến thể mặc định, SKU tự sinh, {@link VariantCreated} với
 *       Tồn đầu kỳ, Giá vốn ban đầu như thêm một Sản phẩm). Lỗi bất ngờ khi ghi (trùng mã, trùng tên Danh mục do người
 *       khác vừa tạo): cả lần nhập rollback, {@code 409} nêu số dòng, không nhập dở dang.</li>
 * </ol>
 *
 * <p>Mã sản phẩm cha nhóm các dòng thành Sản phẩm nhiều Biến thể; nhóm có lỗi bị loại toàn bộ. Không có nhóm hợp lệ
 * nào vẫn là {@code 200} với {@code imported: 0}. Không cập nhật hoặc gắn Biến thể vào Sản phẩm đã có; không nhập ảnh.
 */
@Service
class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    static final String DOCUMENT_TYPE = "CATALOG_PRODUCT_IMPORT";
    /** File tối đa 2 MB, bằng {@code spring.servlet.multipart.max-file-size}. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private final ProductService products;
    private final CategoryRepository categories;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;
    private final IdempotencyService idempotency;
    private final JsonMapper json;

    ProductImportService(ProductService products, CategoryRepository categories, EntityManager em, JdbcTemplate jdbc,
                         CurrentContext context, Clock clock, IdempotencyService idempotency, JsonMapper json) {
        this.products = products;
        this.categories = categories;
        this.em = em;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
        this.idempotency = idempotency;
        this.json = json;
    }

    /**
     * Phản hồi của {@code POST …/products/import}, cũng là nội dung lưu để phát lại.
     *
     * @param imported          số Sản phẩm đã tạo
     * @param failed            các dòng lỗi theo thứ tự dòng (một dòng có thể có nhiều lỗi, mỗi lỗi một phần tử)
     * @param createdCategories tên các Danh mục vừa tạo, theo thứ tự dòng dùng đầu tiên
     */
    record ImportResult(int imported, List<RowError> failed, List<String> createdCategories) {
    }

    /**
     * Lỗi của một ô: số dòng Excel (dòng 1 là tiêu đề), tiêu đề cột như file mẫu (không có {@code *}), lý do.
     * {@code column} {@code null}: lỗi của cả dòng (dòng ví dụ của file mẫu còn nguyên).
     */
    record RowError(int row, String column, String message) {
    }

    /** File đã đọc: các dòng thô và SHA-256 (hex) của nội dung, để băm vào {@code request_hash}. */
    record ImportFile(String sha256, List<ImportRow> rows) {
    }

    record Imported(ImportResult result, boolean replayed) {
    }

    record PreviewRow(int row, String parentCode, String productName, String variantName,
                      Map<String, String> attributes, String sku, String barcode, String categoryName,
                      String unit, String description, BigDecimal salePrice, BigDecimal openingQuantity,
                      BigDecimal openingUnitCost, boolean valid) {
    }

    record PreviewResult(int totalRows, int validProducts, int validRows, List<PreviewRow> rows,
                         List<RowError> errors, String previewToken) {
    }

    /** Kiểm và đọc phần {@code file}, gọi NGOÀI transaction. */
    ImportFile read(MultipartFile file) {
        if (file == null) {
            throw FieldErrors.of("file", "Chọn file .xlsx hoặc .csv để nhập");
        }
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            log.warn("Không đọc được file nhập Sản phẩm tải lên: {}", unreadable.toString());
            throw FieldErrors.of("file", "Không đọc được tệp gửi lên. Hãy chọn lại file rồi thử lại.");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
        }
        return new ImportFile(sha256(content), ProductImportReader.read(content, file.getOriginalFilename()));
    }

    @Transactional(readOnly = true)
    PreviewResult preview(ImportFile file) {
        Plan plan = plan(file.rows());
        List<PreviewRow> rows = previewRows(plan);
        return new PreviewResult(file.rows().size(), plan.accepted().size(),
                (int) rows.stream().filter(PreviewRow::valid).count(), rows, plan.errors(),
                previewToken(file, plan, rows));
    }

    /** Gọi từ controller, NGOÀI transaction ({@link IdempotencyService#execute} tự mở transaction). */
    Imported importFile(UUID idempotencyKey, ImportFile file) {
        return importFile(idempotencyKey, file, null);
    }

    Imported importFile(UUID idempotencyKey, ImportFile file, String previewToken) {
        if (idempotencyKey == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
        }
        IdempotentResult<ImportResult> result = idempotency.execute(DOCUMENT_TYPE, idempotencyKey, file.sha256(),
                () -> importRows(file, previewToken), this::loadResult);
        return new Imported(result.value(), result.replayed());
    }

    // --- Trong transaction của lần nhập ------------------------------------------------------------------------

    private record Plan(List<Candidate> candidates, List<Candidate> accepted, List<RowError> errors,
                        Set<String> reservedSkus) {
    }

    /** Chỉ SELECT; preview và commit dùng chính xác cùng cách dựng nhóm và kiểm xung đột. */
    private Plan plan(List<ImportRow> rows) {
        List<Candidate> candidates = new ProductImportPlanner(products).check(rows);
        List<NewVariant> variants = candidates.stream().filter(Candidate::valid)
                .flatMap(c -> c.variants().stream()).toList();
        Set<String> takenSkus = existingCodes(context.storeId(), "sku", variants.stream().map(NewVariant::sku).toList());
        Set<String> takenBarcodes = existingCodes(context.storeId(), "barcode",
                variants.stream().map(NewVariant::barcode).toList());
        List<RowError> errors = new ArrayList<>();
        List<Candidate> accepted = new ArrayList<>();
        Map<String, Integer> skuRows = new HashMap<>();
        Map<String, Integer> barcodeRows = new HashMap<>();
        for (Candidate candidate : candidates) {
            List<RowError> own = new ArrayList<>(candidate.errors());
            Map<String, Integer> groupSkus = new HashMap<>();
            Map<String, Integer> groupBarcodes = new HashMap<>();
            Map<String, Integer> groupNames = new HashMap<>();
            List<NewVariant> checked = candidate.variants();
            for (int i = 0; i < checked.size(); i++) {
                NewVariant variant = checked.get(i);
                int row = candidate.rows().get(i).row();
                checkCode(row, ImportColumn.SKU, variant.sku(), takenSkus, skuRows, groupSkus, own);
                checkCode(row, ImportColumn.BARCODE, variant.barcode(), takenBarcodes, barcodeRows, groupBarcodes, own);
                if (variant.name() != null) {
                    Integer previous = groupNames.putIfAbsent(variant.name().toLowerCase(Locale.ROOT), row);
                    if (previous != null) {
                        own.add(error(row, ImportColumn.VARIANT_NAME,
                                "Tên Biến thể " + variant.name() + " trùng với dòng " + previous));
                    }
                }
            }
            if (!own.isEmpty()) {
                if (candidate.parentCode() != null) {
                    Set<Integer> invalidRows = new HashSet<>();
                    own.forEach(e -> invalidRows.add(e.row()));
                    for (ImportRow row : candidate.rows()) {
                        if (!invalidRows.contains(row.row())) {
                            own.add(error(row.row(), ImportColumn.PARENT_CODE,
                                    "Nhóm " + candidate.parentCode() + " có dòng lỗi nên không nhập toàn bộ nhóm"));
                        }
                    }
                }
                errors.addAll(own);
            } else {
                accepted.add(candidate);
                skuRows.putAll(groupSkus);
                barcodeRows.putAll(groupBarcodes);
            }
        }
        return new Plan(candidates, List.copyOf(accepted), ProductImportPlanner.sorted(errors), Set.copyOf(skuRows.keySet()));
    }

    private static void checkCode(int row, ImportColumn column, String code, Set<String> taken,
                                  Map<String, Integer> accepted, Map<String, Integer> group, List<RowError> errors) {
        if (code == null) {
            return;
        }
        String label = column == ImportColumn.SKU ? "SKU " : "Mã vạch ";
        Integer previous = accepted.get(code);
        if (previous == null) {
            previous = group.get(code);
        }
        if (taken.contains(code)) {
            errors.add(error(row, column, label + code + " đã có ở Sản phẩm khác của Cửa hàng"));
        } else if (previous != null) {
            errors.add(error(row, column, label + code + " trùng với dòng " + previous));
        }
        group.putIfAbsent(code, row);
    }

    private UUID importRows(ImportFile file, String expectedPreview) {
        Plan plan = plan(file.rows());
        if (expectedPreview != null && !Objects.equals(expectedPreview, previewToken(file, plan, previewRows(plan)))) {
            throw ProblemException.conflict("/errors/catalog/import-preview-stale", "Bản xem trước đã thay đổi",
                    "Dữ liệu đã thay đổi sau khi xem trước. Không Sản phẩm nào được nhập; hãy xem trước lại rồi lưu.");
        }
        List<String> createdCategories = insert(context.storeId(), plan.accepted(), plan.reservedSkus());
        ImportResult result = new ImportResult(plan.accepted().size(), plan.errors(), createdCategories);
        UUID importId = UUID.randomUUID();
        jdbc.update("""
                insert into app.product_import (id, store_id, created_by, imported, result, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?)
                """, importId, context.storeId(), context.userIdIfPresent().orElse(null), result.imported(),
                json.writeValueAsString(result), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return importId;
    }

    private List<PreviewRow> previewRows(Plan plan) {
        Set<Integer> acceptedRows = new HashSet<>();
        plan.accepted().forEach(c -> c.rows().forEach(row -> acceptedRows.add(row.row())));
        List<PreviewRow> rows = new ArrayList<>();
        for (Candidate candidate : plan.candidates()) {
            List<NewVariant> checked = candidate.variants();
            String unit = candidate.product() != null ? candidate.product().unit()
                    : candidate.variantProduct() != null ? candidate.variantProduct().unit()
                    : candidate.common().get(ImportColumn.UNIT);
            for (int i = 0; i < candidate.rows().size(); i++) {
                ImportRow row = candidate.rows().get(i);
                NewVariant variant = i < checked.size() ? checked.get(i) : null;
                Map<String, String> attributes = variant == null
                        ? ProductImportPlanner.attributes(row, new ArrayList<>()) : variant.attributes();
                String variantName = variant == null ? normalized(row, ImportColumn.VARIANT_NAME) : variant.name();
                if (variantName == null && !attributes.isEmpty()) {
                    variantName = String.join(VariantFields.LABEL_SEPARATOR, attributes.values());
                }
                rows.add(new PreviewRow(row.row(), candidate.parentCode(), candidate.common().get(ImportColumn.NAME),
                        variantName, attributes,
                        variant == null ? upper(row, ImportColumn.SKU) : variant.sku(),
                        variant == null ? upper(row, ImportColumn.BARCODE) : variant.barcode(),
                        candidate.categoryName() == null ? candidate.common().get(ImportColumn.CATEGORY)
                                : candidate.categoryName(), unit,
                        candidate.common().get(ImportColumn.DESCRIPTION),
                        variant == null ? row.numbers().get(ImportColumn.SALE_PRICE) : BigDecimal.valueOf(variant.salePrice()),
                        variant == null ? row.numbers().get(ImportColumn.OPENING_QUANTITY)
                                : BigDecimal.valueOf(variant.openingQuantity()),
                        variant == null ? row.numbers().get(ImportColumn.OPENING_UNIT_COST)
                                : BigDecimal.valueOf(variant.openingUnitCost()), acceptedRows.contains(row.row())));
            }
        }
        return rows.stream().sorted(Comparator.comparingInt(PreviewRow::row)).toList();
    }

    private record PreviewFingerprint(String fileHash, UUID storeId, List<PreviewRow> rows, List<RowError> errors) {
    }

    private String previewToken(ImportFile file, Plan plan, List<PreviewRow> rows) {
        return sha256(json.writeValueAsString(new PreviewFingerprint(file.sha256(), context.storeId(), rows,
                plan.errors())).getBytes(StandardCharsets.UTF_8));
    }

    private static String normalized(ImportRow row, ImportColumn column) {
        String value = TextFields.normalize(row.texts().get(column), column == ImportColumn.DESCRIPTION);
        return value.isEmpty() ? null : value;
    }

    private static String upper(ImportRow row, ImportColumn column) {
        String value = normalized(row, column);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /**
     * Ghi các dòng hợp lệ theo thứ tự dòng; trả tên các Danh mục vừa tạo. {@code reservedSkus}: SKU người dùng nhập
     * ở các dòng hợp lệ, để SKU tự sinh của dòng trước không lấy mất SKU của dòng sau.
     */
    private List<String> insert(UUID storeId, List<Candidate> accepted, Set<String> reservedSkus) {
        Map<String, UUID> categoryIds = existingCategories(storeId);
        List<String> created = new ArrayList<>();
        Set<String> reserved = Set.copyOf(reservedSkus);
        for (Candidate a : accepted) {
            UUID categoryId = null;
            String categoryKey = a.categoryName() == null ? null : categoryKey(a.categoryName());
            if (categoryKey != null) {
                categoryId = categoryIds.get(categoryKey);
                if (categoryId == null) {
                    categoryId = createCategory(a.firstRow(), a.categoryName());
                    categoryIds.put(categoryKey, categoryId);
                    created.add(a.categoryName());
                }
            }
            try {
                if (a.variantProduct() != null) {
                    products.insertWithVariantsInCurrentTransaction(a.variantProduct().withCategory(categoryId),
                            reserved::contains);
                } else {
                    products.insertInCurrentTransaction(a.product().withCategory(categoryId), reserved::contains);
                }
            } catch (ProblemException conflict) {
                // Người khác vừa tạo cùng mã sau lúc kiểm: cả lần nhập rollback, nêu dòng nào
                throw new ProblemException(conflict.status(), conflict.type(), conflict.title(),
                        "Dòng " + a.firstRow() + ": " + conflict.getMessage() + " Không dòng nào được nhập; hãy sửa "
                                + "file rồi nhập lại.", conflict.properties());
            }
            // Đã flush: bỏ các entity đã ghi khỏi persistence context, để mỗi lần flush sau không phải duyệt lại
            // hàng nghìn entity
            em.clear();
        }
        return List.copyOf(created);
    }

    /** Danh mục hiện có theo khóa so khớp; tên trùng khóa (khác khoảng trắng giữa chữ) thì Danh mục tạo trước thắng. */
    private Map<String, UUID> existingCategories(UUID storeId) {
        Map<String, UUID> found = new HashMap<>();
        jdbc.query("select id, name from app.category where store_id = ? order by created_at, id",
                (RowCallbackHandler) rs -> found.putIfAbsent(categoryKey(rs.getString("name")),
                        rs.getObject("id", UUID.class)), storeId);
        return found;
    }

    private UUID createCategory(int row, String name) {
        CategoryEntity category = new CategoryEntity(name, clock.instant());
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, CategoryEntity.NAME_UNIQUE)) {
                throw ProblemException.conflict("/errors/catalog/category-name-taken", "Tên Danh mục đã có",
                        "Dòng " + row + ": Danh mục \"" + name + "\" vừa được tạo ở nơi khác trong lúc nhập. Không "
                                + "dòng nào được nhập; hãy nhập lại file.");
            }
            throw e;
        }
        return category.id();
    }

    /** Khóa so khớp tên Danh mục: như {@code lower(name)} của {@code category_name_uq}, gộp khoảng trắng. */
    static String categoryKey(String name) {
        return TextFields.normalize(name, false).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Mã ({@code sku} hoặc {@code barcode}, đã viết HOA) đã có ở Biến thể chưa xóa mềm của Cửa hàng. */
    private Set<String> existingCodes(UUID storeId, String column, Collection<String> codes) {
        Object[] wanted = codes.stream().filter(code -> code != null).distinct().toArray();
        if (wanted.length == 0) {
            return Set.of();
        }
        Set<String> found = new HashSet<>();
        // column là hằng của lớp này ("sku" hoặc "barcode"), không phải dữ liệu người dùng
        jdbc.query("select " + column + " from app.product_variant where store_id = ? and deleted_at is null and "
                        + column + " = any(?)",
                (PreparedStatement ps) -> {
                    ps.setObject(1, storeId);
                    ps.setArray(2, ps.getConnection().createArrayOf("text", wanted));
                },
                (RowCallbackHandler) rs -> found.add(rs.getString(1)));
        return found;
    }

    private ImportResult loadResult(UUID importId) {
        String stored = jdbc.queryForObject("select result::text from app.product_import where store_id = ? and id = ?",
                String.class, context.storeId(), importId);
        return json.readValue(stored, ImportResult.class);
    }

    private static RowError error(int row, ImportColumn column, String message) {
        return new RowError(row, column.header, message);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProblemException tooLarge() {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large",
                "Dữ liệu gửi lên quá lớn", "File nhập tối đa 2 MB. Hãy tách thành nhiều file nhỏ hơn.");
    }
}
