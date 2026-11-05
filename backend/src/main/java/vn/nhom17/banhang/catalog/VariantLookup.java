package vn.nhom17.banhang.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.nhom17.banhang.platform.PageResponse;

/** Thông tin bán hàng của Biến thể cho sales (AD-2, AD-8). Hiện thực ở GĐ1 (Hiếu, SP-01/SP-05). */
public interface VariantLookup {

    /** Chỉ trả Biến thể còn kinh doanh của Cửa hàng hiện tại; id không hợp lệ thì bỏ qua. */
    List<SellableVariant> getForSale(Collection<UUID> variantIds);

    /**
     * Tìm kiếm thường (FR-5) trên Biến thể còn kinh doanh: theo tên (không phân biệt hoa thường, không cần dấu, qua
     * {@code app.f_unaccent}), SKU, mã vạch; lọc theo Danh mục và khoảng giá. {@code page} bắt đầu từ 0.
     */
    PageResponse<SellableVariant> searchForSale(String query, UUID categoryId, Long minPrice, Long maxPrice,
                                                int page, int size);
}
