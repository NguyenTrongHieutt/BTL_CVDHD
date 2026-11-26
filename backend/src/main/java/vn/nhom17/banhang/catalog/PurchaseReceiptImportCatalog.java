package vn.nhom17.banhang.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalog boundary for a mixed purchase receipt import. Parsing and catalog validation are shared with product
 * import. Preparing a file only reads data. Creation joins the caller's transaction and always uses zero opening
 * stock; inventory receives all quantities through its purchase receipt.
 */
public interface PurchaseReceiptImportCatalog {
    record Edit(int row, Map<String, String> values) {}
    record InputRow(int row, Map<String, String> values) {}
    record RowError(int row, String column, String message) {}
    enum Action { EXISTING, NEW_PRODUCT, NEW_VARIANT }

    record Row(int row, String sku, String parentCode, String productName, String variantName,
               BigDecimal quantity, BigDecimal unitCost, Action action, Map<String, String> values,
               UUID variantId, UUID productId) {}

    /** Opaque, immutable parsed content, including any corrected cells. */
    interface FileData {
        List<InputRow> inputs();
    }

    /** Opaque catalog plan. The fingerprint includes the catalog records used to resolve and validate the file. */
    interface Prepared {
        List<Row> rows();
        List<RowError> errors();
        int existingRows();
        int newProducts();
        int newVariants();
        String fingerprint();
    }

    record Template(byte[] content, String filename, String contentType) {}

    FileData read(byte[] content, String filename, List<Edit> edits);
    Template template(String format);
    Prepared prepare(FileData file);

    /** Locks existing products and their variants before rechecking. Requires an existing transaction. */
    Prepared revalidateForSave(FileData file);

    /** Creates all catalog additions in a validated plan and returns a variant id for every file row. */
    Map<Integer, UUID> create(Prepared prepared);
}
