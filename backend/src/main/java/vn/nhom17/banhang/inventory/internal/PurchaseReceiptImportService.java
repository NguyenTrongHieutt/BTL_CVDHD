package vn.nhom17.banhang.inventory.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Action;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Edit;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.FileData;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.InputRow;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Prepared;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Row;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.RowError;
import vn.nhom17.banhang.catalog.SupplierLookup;
import vn.nhom17.banhang.catalog.SupplierLookup.SupplierSummary;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.CreateReceipt;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.LineRequest;
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.Receipt;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;

@Service
class PurchaseReceiptImportService {
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final String DOCUMENT_TYPE = "PURCHASE_RECEIPT_IMPORT";
    private static final String QUANTITY_COLUMN = "Số lượng";
    private static final String COST_COLUMN = "Giá nhập";
    private final PurchaseReceiptImportCatalog catalog;
    private final SupplierLookup suppliers;
    private final PurchaseReceiptService receipts;
    private final IdempotencyService idempotency;
    private final CurrentContext context;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    PurchaseReceiptImportService(PurchaseReceiptImportCatalog catalog, SupplierLookup suppliers,
            PurchaseReceiptService receipts, IdempotencyService idempotency, CurrentContext context,
            JdbcTemplate jdbc, JsonMapper json) {
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.receipts = receipts;
        this.idempotency = idempotency;
        this.context = context;
        this.jdbc = jdbc;
        this.json = json;
    }

    record ImportInput(String fileHash, FileData file, UUID supplierId, String note) {}
    record PreviewRow(int row, String sku, String parentCode, String productName, String variantName,
            BigDecimal quantity, BigDecimal unitCost, Action action, Map<String, String> values, boolean valid) {}
    record PreviewResult(String previewToken, List<PreviewRow> rows, List<RowError> errors, int existingRows,
            int newProducts, int newVariants, Long totalAmount) {}
    record Imported(Receipt receipt, boolean replayed) {}
    private record Stock(UUID variantId, int quantity, long avgUnitCost, boolean active) {}
    private record RequestFingerprint(String fileHash, List<InputRow> rows, UUID supplierId, String note) {}
    private record PreviewFingerprint(UUID storeId, RequestFingerprint request, SupplierSummary supplier,
            String catalogFingerprint, List<Stock> stock, List<PreviewRow> rows, List<RowError> errors, Long total) {}

    ImportInput read(MultipartFile file, String supplierId, String note, String edits) {
        if (file == null) {
            throw field("file", "Chọn file .xlsx hoặc .csv để nhập kho.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            throw field("file", "Không đọc được tệp gửi lên. Hãy chọn lại file rồi thử lại.");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
        }
        FileData parsed = catalog.read(content, file.getOriginalFilename(), readEdits(edits));
        UUID supplier = null;
        if (supplierId != null && !supplierId.isBlank()) {
            try {
                supplier = UUID.fromString(supplierId.strip());
                if (!supplier.toString().equalsIgnoreCase(supplierId.strip())) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw field("supplierId", "Nhà cung cấp phải là UUID hợp lệ.");
            }
        }
        return new ImportInput(sha256(content), parsed, supplier,
                note == null || note.isBlank() ? null : note.strip());
    }

    @Transactional(readOnly = true)
    PreviewResult preview(ImportInput input) {
        return evaluate(input, catalog.prepare(input.file()), false);
    }

    /** Idempotency owns the transaction. Replays never reclassify newly created SKUs as a second receipt. */
    Imported importFile(UUID key, ImportInput input, String previewToken) {
        if (key == null) {
            throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                    "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
        }
        if (previewToken == null || previewToken.isBlank()) {
            throw ProblemException.unprocessable("/errors/inventory/receipt-import-preview-required",
                    "Cần xem trước phiếu nhập", "Hãy xem trước và sửa hết lỗi trước khi xác nhận nhập kho.");
        }
        var result = idempotency.execute(DOCUMENT_TYPE, key, idempotency.hash(requestFingerprint(input)),
                () -> save(input, previewToken), receipts::load);
        return new Imported(result.value(), result.replayed());
    }

    private UUID save(ImportInput input, String expectedPreview) {
        Prepared prepared = catalog.revalidateForSave(input.file());
        PreviewResult preview = evaluate(input, prepared, true);
        if (!Objects.equals(expectedPreview, preview.previewToken())) {
            throw ProblemException.conflict("/errors/inventory/receipt-import-preview-stale", "Bản xem trước đã thay đổi",
                    "Dữ liệu đã thay đổi sau khi xem trước. Không dòng nào được nhập; hãy xem trước lại rồi lưu.");
        }
        if (!preview.errors().isEmpty()) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/inventory/receipt-import-invalid",
                    "Phiếu nhập từ file không hợp lệ", "Sửa hết lỗi trước khi xác nhận. Không dòng nào được nhập.",
                    Map.of("errors", preview.errors()));
        }
        Map<Integer, UUID> ids = catalog.create(prepared);
        // Catalog's zero-opening events are delivered BEFORE_COMMIT. These empty rows are needed now, before
        // StockService applies the receipt; the event listener's ON CONFLICT is safe when delivered later.
        for (Row row : prepared.rows().stream().filter(r -> r.action() != Action.EXISTING)
                .sorted(Comparator.comparing(r -> ids.get(r.row()))).toList()) {
            jdbc.update("""
                    insert into app.stock (store_id, variant_id) values (?, ?)
                    on conflict (store_id, variant_id) do nothing
                    """, context.storeId(), ids.get(row.row()));
        }
        List<LineRequest> lines = prepared.rows().stream().map(row ->
                new LineRequest(ids.get(row.row()), row.quantity(), row.unitCost())).toList();
        return receipts.save(PurchaseReceiptService.validate(new CreateReceipt(input.supplierId(), null,
                input.note(), lines)));
    }

    private PreviewResult evaluate(ImportInput input, Prepared prepared, boolean lockStock) {
        List<RowError> errors = new ArrayList<>(prepared.errors());
        SupplierSummary supplier = suppliers.findById(input.supplierId()).orElse(null);
        if (supplier == null) {
            errors.add(new RowError(0, null, "Chọn nhà cung cấp hợp lệ trong cửa hàng hiện tại."));
        }
        if (input.note() != null && input.note().length() > 500) {
            errors.add(new RowError(0, null, "Ghi chú tối đa 500 ký tự."));
        }
        if (prepared.rows().isEmpty()) {
            errors.add(new RowError(0, null, "File phải có ít nhất một dòng hàng."));
        }
        List<Stock> stock = stock(prepared.rows(), lockStock);
        Map<UUID, Stock> byId = new HashMap<>();
        stock.forEach(s -> byId.put(s.variantId(), s));
        long total = 0;
        boolean moneyOverflow = false;
        for (Row row : prepared.rows()) {
            Integer quantity = quantity(row, errors);
            Long unitCost = cost(row, errors);
            if (quantity != null && unitCost != null) {
                if (unitCost > (PurchaseReceiptService.MAX_MONEY - total) / quantity) {
                    errors.add(new RowError(row.row(), COST_COLUMN, "Tổng tiền phiếu nhập vượt giới hạn hỗ trợ."));
                    moneyOverflow = true;
                } else {
                    total += unitCost * quantity;
                }
            }
            if (row.action() == Action.EXISTING) {
                Stock current = byId.get(row.variantId());
                if (current == null || !current.active()) {
                    errors.add(new RowError(row.row(), "SKU", "Biến thể không còn hoạt động trong kho của cửa hàng."));
                } else if (quantity != null && (long) current.quantity() + quantity > Integer.MAX_VALUE) {
                    errors.add(new RowError(row.row(), QUANTITY_COLUMN, "Số lượng tồn sau khi nhập vượt giới hạn."));
                } else {
                    try {
                        Math.multiplyExact(current.quantity(), current.avgUnitCost());
                    } catch (ArithmeticException overflow) {
                        errors.add(new RowError(row.row(), COST_COLUMN, "Tổng giá vốn hiện tại vượt giới hạn hỗ trợ."));
                    }
                }
            }
        }
        List<RowError> sorted = errors.stream().distinct().sorted(Comparator.comparingInt(RowError::row)
                .thenComparing(e -> e.column() == null ? "" : e.column()).thenComparing(RowError::message)).toList();
        Set<Integer> invalidRows = new HashSet<>();
        sorted.forEach(error -> invalidRows.add(error.row()));
        List<PreviewRow> rows = prepared.rows().stream().map(row -> new PreviewRow(row.row(), row.sku(), row.parentCode(),
                row.productName(), row.variantName(), row.quantity(), row.unitCost(), row.action(), row.values(),
                !invalidRows.contains(0) && !invalidRows.contains(row.row()))).toList();
        Long amount = moneyOverflow || !sorted.isEmpty() ? null : total;
        String token = idempotency.hash(new PreviewFingerprint(context.storeId(), requestFingerprint(input), supplier,
                prepared.fingerprint(), stock, rows, sorted, amount));
        return new PreviewResult(token, rows, sorted, prepared.existingRows(), prepared.newProducts(),
                prepared.newVariants(), amount);
    }

    private static Integer quantity(Row row, List<RowError> errors) {
        if (hasError(errors, row.row(), QUANTITY_COLUMN)) {
            return null;
        }
        try {
            int value = row.quantity() == null ? 0 : row.quantity().intValueExact();
            if (value > 0) {
                return value;
            }
        } catch (ArithmeticException ignored) {
            // Report the same rule for missing, fractional and out-of-range quantities.
        }
        errors.add(new RowError(row.row(), QUANTITY_COLUMN, "Số lượng phải là số nguyên lớn hơn 0, tối đa 2.147.483.647."));
        return null;
    }

    private static Long cost(Row row, List<RowError> errors) {
        if (hasError(errors, row.row(), COST_COLUMN)) {
            return null;
        }
        try {
            long value = row.unitCost() == null ? 0 : row.unitCost().longValueExact();
            if (value > 0 && value <= PurchaseReceiptService.MAX_MONEY) {
                return value;
            }
        } catch (ArithmeticException ignored) {
            // Report as a cell error rather than coercing a fractional or oversized amount.
        }
        errors.add(new RowError(row.row(), COST_COLUMN, "Giá nhập phải là số nguyên đồng lớn hơn 0 và trong giới hạn hỗ trợ."));
        return null;
    }

    private static boolean hasError(List<RowError> errors, int row, String column) {
        return errors.stream().anyMatch(e -> e.row() == row && column.equals(e.column()));
    }

    private List<Stock> stock(List<Row> rows, boolean lock) {
        Object[] ids = rows.stream().map(Row::variantId).filter(Objects::nonNull).distinct().sorted().toArray();
        if (ids.length == 0) {
            return List.of();
        }
        return jdbc.query("""
                select variant_id, quantity, avg_unit_cost, is_active from app.stock
                where store_id = ? and variant_id = any(?) order by variant_id
                """ + (lock ? " for update" : ""), (PreparedStatement ps) -> {
                    ps.setObject(1, context.storeId());
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
                }, (rs, i) -> new Stock(rs.getObject("variant_id", UUID.class), rs.getInt("quantity"),
                        rs.getLong("avg_unit_cost"), rs.getBoolean("is_active")));
    }

    private List<Edit> readEdits(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = json.readTree(raw);
            if (!array.isArray() || array.size() > 1000) {
                throw new IllegalArgumentException();
            }
            List<Edit> result = new ArrayList<>();
            for (JsonNode entry : array) {
                JsonNode row = entry.get("row");
                JsonNode values = entry.get("values");
                if (!entry.isObject() || row == null || !row.isIntegralNumber() || !row.canConvertToInt()
                        || values == null || !values.isObject()) {
                    throw new IllegalArgumentException();
                }
                Map<String, String> fields = new LinkedHashMap<>();
                for (var field : values.properties()) {
                    JsonNode value = field.getValue();
                    if (!value.isNull() && !value.isString()) {
                        throw new IllegalArgumentException();
                    }
                    fields.put(field.getKey(), value.isNull() ? null : value.asString());
                }
                result.add(new Edit(row.intValue(), Collections.unmodifiableMap(fields)));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw field("edits", "Dữ liệu sửa phải là danh sách JSON gồm row và values (chuỗi hoặc null).");
        }
    }

    static UUID parseKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String text = key.strip();
        try {
            UUID parsed = UUID.fromString(text);
            if (parsed.toString().equalsIgnoreCase(text)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Catalog convention treats both a missing and an invalid UUID as idempotency-key-missing.
        }
        return null;
    }

    private static RequestFingerprint requestFingerprint(ImportInput input) {
        return new RequestFingerprint(input.fileHash(), input.file().inputs(), input.supplierId(), input.note());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProblemException field(String field, String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                message, Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    private static ProblemException tooLarge() {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large",
                "Dữ liệu gửi lên quá lớn", "File nhập tối đa 2 MB. Hãy tách thành nhiều file nhỏ hơn.");
    }
}
