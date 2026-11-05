package vn.nhom17.banhang.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Đường DUY NHẤT thay đổi Tồn kho (AD-6). Gọi trong CÙNG transaction với chứng từ. Hiện thực ở GĐ1 (Anh, KHO-01).
 *
 * <ol>
 *   <li>Khóa các dòng {@code stock} của chứng từ ({@code SELECT … FOR UPDATE}).</li>
 *   <li>Ghi {@code stock_ledger} (unique theo chứng từ + dòng): gọi lại cùng chứng từ thì không làm gì.</li>
 *   <li>Trừ có điều kiện {@code quantity >= :q}; không đủ thì ném lỗi để rollback cả chứng từ.</li>
 *   <li>Nhập hàng tính lại Giá vốn bình quân theo AD-15; trả hàng, kiểm kho không đổi Giá vốn.</li>
 * </ol>
 */
public interface StockService {

    /** {@code delta} âm là xuất, dương là nhập; {@code unitCost} chỉ dùng cho nhập hàng và tồn đầu kỳ. */
    record StockLine(int lineNo, UUID variantId, int delta, Long unitCost) {
    }

    /** Giá vốn đã dùng cho từng dòng: sales lưu vào {@code sales_order_line.unit_cost} (AD-15). */
    record AppliedLine(int lineNo, UUID variantId, long unitCost, int quantityAfter) {
    }

    List<AppliedLine> apply(StockDocumentType documentType, UUID documentId, List<StockLine> lines);
}
