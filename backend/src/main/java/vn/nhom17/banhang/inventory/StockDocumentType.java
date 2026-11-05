package vn.nhom17.banhang.inventory;

/** Loại chứng từ làm thay đổi Tồn kho; khớp CHECK của {@code stock_ledger.document_type}. */
public enum StockDocumentType {
    OPENING,
    SALE,
    SALE_CANCEL,
    SALE_RETURN,
    PURCHASE_RECEIPT,
    STOCKTAKE
}
