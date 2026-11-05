package vn.nhom17.banhang.inventory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Đọc Tồn kho, Giá vốn và cảnh báo tồn thấp (AD-15: Giá vốn chỉ đọc qua đây). Hiện thực ở GĐ1 (Anh). */
public interface StockQuery {

    List<StockView> find(Collection<UUID> variantIds);

    List<StockView> lowStock();

    record StockView(UUID variantId, int quantity, int minQuantity, long avgUnitCost, boolean active) {

        public boolean belowMin() {
            return quantity < minQuantity;
        }
    }
}
