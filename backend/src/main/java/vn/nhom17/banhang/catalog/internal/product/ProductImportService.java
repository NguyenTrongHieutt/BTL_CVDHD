package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.catalog.internal.Constraints;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.catalog.internal.TextFields;
import vn.nhom17.banhang.catalog.internal.category.CategoryEntity;
import vn.nhom17.banhang.catalog.internal.category.CategoryRepository;
import vn.nhom17.banhang.catalog.internal.product.ProductController.CreateProductRequest;
import vn.nhom17.banhang.catalog.internal.product.ProductImportReader.ImportRow;
import vn.nhom17.banhang.catalog.internal.product.ProductService.NewProduct;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Nhập Sản phẩm từ file Excel (FR-6, SP-06), chỉ Chủ cửa hàng.
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
 * <p>Không có dòng hợp lệ nào vẫn là {@code 200} với {@code imported: 0}. Không cập nhật Sản phẩm đã có, không nhập
 * Biến thể, ảnh.
 */
@Service
class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    static final String DOCUMENT_TYPE = "CATALOG_PRODUCT_IMPORT";
    /** File tối đa 2 MB, bằng {@code spring.servlet.multipart.max-file-size}. */
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final TextFields.Rule CATEGORY_NAME = TextFields.Rule.optional("Tên Danh mục",
            CategoryEntity.NAME_MAX);

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

    /** Kiểm và đọc phần {@code file}, gọi NGOÀI transaction. */
    ImportFile read(MultipartFile file) {
        if (file == null) {
            throw FieldErrors.of("file", "Chọn file Excel để nhập");
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
        return new ImportFile(sha256(content), ProductImportReader.read(content));
    }

    /** Gọi từ controller, NGOÀI transaction ({@link IdempotencyService#execute} tự mở transaction). */
    Imported importFile(UUID idempotencyKey, ImportFile file) {
        if (idempotencyKey == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
        }
        IdempotentResult<ImportResult> result = idempotency.execute(DOCUMENT_TYPE, idempotencyKey, file.sha256(),
                () -> importRows(file.rows()), this::loadResult);
        return new Imported(result.value(), result.replayed());
    }

    // --- Trong transaction của lần nhập ------------------------------------------------------------------------

    /** Một dòng hợp lệ chờ ghi. {@code categoryKey} {@code null}: không thuộc Danh mục nào. */
    private record Accepted(int row, NewProduct product, String categoryKey, String categoryName) {
    }

    /** Kết quả kiểm trường của một dòng; {@code product} {@code null} khi có lỗi. */
    private record Checked(ImportRow row, NewProduct product, String categoryName, List<RowError> errors) {
    }

    private UUID importRows(List<ImportRow> rows) {
        UUID storeId = context.storeId();
        List<Checked> checked = rows.stream().map(this::checkRow).toList();
        Set<String> takenSkus = existingCodes(storeId, "sku",
                checked.stream().filter(c -> c.product() != null).map(c -> c.product().sku()).toList());
        Set<String> takenBarcodes = existingCodes(storeId, "barcode",
                checked.stream().filter(c -> c.product() != null).map(c -> c.product().barcode()).toList());

        List<RowError> failed = new ArrayList<>();
        List<Accepted> accepted = new ArrayList<>();
        Map<String, Integer> skuRows = new HashMap<>();
        Map<String, Integer> barcodeRows = new HashMap<>();
        for (Checked c : checked) {
            List<RowError> errors = new ArrayList<>(c.errors());
            NewProduct product = c.product();
            int row = c.row().row();
            if (product != null) {
                String sku = product.sku();
                String barcode = product.barcode();
                if (sku != null && takenSkus.contains(sku)) {
                    errors.add(error(row, ImportColumn.SKU, "SKU " + sku + " đã có ở Sản phẩm khác của Cửa hàng"));
                } else if (sku != null && skuRows.containsKey(sku)) {
                    errors.add(error(row, ImportColumn.SKU, "SKU " + sku + " trùng với dòng " + skuRows.get(sku)));
                }
                if (barcode != null && takenBarcodes.contains(barcode)) {
                    errors.add(error(row, ImportColumn.BARCODE,
                            "Mã vạch " + barcode + " đã có ở Sản phẩm khác của Cửa hàng"));
                } else if (barcode != null && barcodeRows.containsKey(barcode)) {
                    errors.add(error(row, ImportColumn.BARCODE,
                            "Mã vạch " + barcode + " trùng với dòng " + barcodeRows.get(barcode)));
                }
            }
            if (!errors.isEmpty()) {
                failed.addAll(errors);
                continue;
            }
            if (product.sku() != null) {
                skuRows.put(product.sku(), row);
            }
            if (product.barcode() != null) {
                barcodeRows.put(product.barcode(), row);
            }
            String categoryName = c.categoryName();
            accepted.add(new Accepted(row, product, categoryName == null ? null : categoryKey(categoryName),
                    categoryName));
        }

        List<String> createdCategories = insert(storeId, accepted, skuRows.keySet());
        ImportResult result = new ImportResult(accepted.size(), List.copyOf(failed), createdCategories);
        UUID importId = UUID.randomUUID();
        jdbc.update("""
                insert into app.product_import (id, store_id, created_by, imported, result, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?)
                """, importId, storeId, context.userIdIfPresent().orElse(null), result.imported(),
                json.writeValueAsString(result), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return importId;
    }

    /**
     * Kiểm các trường của một dòng bằng đúng quy tắc của thêm Sản phẩm, và tên Danh mục. Lỗi đọc ô (chữ không phải
     * số…) đứng thay mọi lỗi khác của cùng cột. Lỗi sắp theo thứ tự cột của file mẫu.
     */
    private Checked checkRow(ImportRow row) {
        if (ProductImportTemplate.isExample(row)) {
            // Dòng ví dụ của file mẫu còn nguyên: không nhập (Sản phẩm không xóa được, Tồn đầu kỳ đã ghi sổ kho)
            return new Checked(row, null, null,
                    List.of(new RowError(row.row(), null, ProductImportTemplate.EXAMPLE_ROW_MESSAGE)));
        }
        CreateProductRequest request = new CreateProductRequest(row.texts().get(ImportColumn.NAME), null,
                row.texts().get(ImportColumn.UNIT), row.texts().get(ImportColumn.DESCRIPTION),
                number(row, ImportColumn.SALE_PRICE), row.texts().get(ImportColumn.SKU),
                row.texts().get(ImportColumn.BARCODE), number(row, ImportColumn.OPENING_QUANTITY),
                number(row, ImportColumn.OPENING_UNIT_COST));
        FieldErrors fieldErrors = new FieldErrors();
        NewProduct product = products.check(request, fieldErrors);
        String categoryName = TextFields.check(row.texts().get(ImportColumn.CATEGORY), ImportColumn.CATEGORY_FIELD,
                CATEGORY_NAME, fieldErrors);

        Map<ImportColumn, List<String>> byColumn = new LinkedHashMap<>();
        row.cellErrors().forEach((column, message) -> byColumn.computeIfAbsent(column, k -> new ArrayList<>())
                .add(message));
        for (Map<String, String> e : fieldErrors.entries()) {
            ImportColumn column = ImportColumn.ofField(e.get("field"));
            if (column != null && !row.cellErrors().containsKey(column)) {
                byColumn.computeIfAbsent(column, k -> new ArrayList<>()).add(e.get("message"));
            }
        }
        List<RowError> errors = new ArrayList<>();
        byColumn.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Enum::ordinal)))
                .forEach(entry -> entry.getValue().forEach(m -> errors.add(error(row.row(), entry.getKey(), m))));
        if (!errors.isEmpty()) {
            return new Checked(row, null, null, errors);
        }
        String category = categoryName.isEmpty() ? null : categoryName.replaceAll(" {2,}", " ");
        return new Checked(row, product, category, List.of());
    }

    /** Giá trị ô số, {@code null} khi trống hoặc ô không đọc được (lỗi ô đã ghi ở {@code cellErrors}). */
    private static BigDecimal number(ImportRow row, ImportColumn column) {
        return row.numbers().get(column);
    }

    /**
     * Ghi các dòng hợp lệ theo thứ tự dòng; trả tên các Danh mục vừa tạo. {@code reservedSkus}: SKU người dùng nhập
     * ở các dòng hợp lệ, để SKU tự sinh của dòng trước không lấy mất SKU của dòng sau.
     */
    private List<String> insert(UUID storeId, List<Accepted> accepted, Set<String> reservedSkus) {
        Map<String, UUID> categoryIds = existingCategories(storeId);
        List<String> created = new ArrayList<>();
        Set<String> reserved = Set.copyOf(reservedSkus);
        for (Accepted a : accepted) {
            UUID categoryId = null;
            if (a.categoryKey() != null) {
                categoryId = categoryIds.get(a.categoryKey());
                if (categoryId == null) {
                    categoryId = createCategory(a.row(), a.categoryName());
                    categoryIds.put(a.categoryKey(), categoryId);
                    created.add(a.categoryName());
                }
            }
            try {
                products.insertInCurrentTransaction(a.product().withCategory(categoryId), reserved::contains);
            } catch (ProblemException conflict) {
                // Người khác vừa tạo cùng mã sau lúc kiểm: cả lần nhập rollback, nêu dòng nào
                throw new ProblemException(conflict.status(), conflict.type(), conflict.title(),
                        "Dòng " + a.row() + ": " + conflict.getMessage() + " Không dòng nào được nhập; hãy sửa "
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
