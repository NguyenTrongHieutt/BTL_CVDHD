package vn.nhom17.banhang.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Thông tin bán hàng của Biến thể cho sales (AD-2, AD-8). Hiện thực ở catalog (Hiếu, SP-05). Chỉ trả hàng còn bán:
 * Biến thể {@code ACTIVE}, chưa xóa mềm, của Sản phẩm {@code ACTIVE}, chưa xóa mềm, của Cửa hàng hiện tại. Không có
 * Giá vốn; không đọc Tồn kho (lọc theo Tồn kho là việc của sales). Gọi trong hoặc ngoài transaction đều được.
 *
 * <p>Cửa hàng hiện tại lấy từ {@code CurrentContext}: JWT của request, hoặc {@code TenantScope.runAs} (seed, test).
 * Không có ngữ cảnh nào (ngoài request, không có {@code TenantScope}): mọi phương thức ném
 * {@link IllegalStateException} ("Chưa có ngữ cảnh Cửa hàng"), là lỗi lập trình, transaction của bên gọi bị đánh dấu
 * rollback.
 */
public interface VariantLookup {

    /**
     * Biến thể còn bán trong {@code variantIds}, giữ thứ tự id đầu vào (id trùng chỉ trả một lần). Id {@code null},
     * id lạ, của Cửa hàng khác, đã ngừng kinh doanh hay đã xóa mềm thì bỏ qua.
     *
     * @throws IllegalStateException không có ngữ cảnh Cửa hàng, kể cả khi {@code variantIds} rỗng
     */
    List<SellableVariant> getForSale(Collection<UUID> variantIds);

    /** Biến thể chưa xóa mềm khớp bộ lọc của màn Tồn kho. */
    List<SellableVariant> findForInventory(String query, UUID categoryId, boolean includeInactive);

    /**
     * Tìm kiếm thường (FR-5) trên Biến thể còn bán, cùng quy tắc với {@code GET /api/v1/catalog/products}:
     * {@code query} (chuẩn NFC, cắt khoảng trắng; rỗng hoặc {@code null} thì bỏ qua) khớp khi tên Sản phẩm chứa nó,
     * không phân biệt hoa thường, không cần dấu (qua {@code app.f_unaccent}), hoặc SKU, mã vạch bắt đầu bằng nó, không
     * phân biệt hoa thường. Lọc theo Danh mục và khoảng giá bán của Biến thể (gồm cả hai đầu); tham số {@code null} thì
     * không lọc. Sắp theo tên không dấu. {@code page} bắt đầu từ 0; {@code size} mặc định 20, tối đa 100.
     *
     * @throws vn.nhom17.banhang.platform.ProblemException {@code 400 /errors/common/validation}, {@code errors[]} theo
     *                                                     trường {@code minPrice}/{@code maxPrice}: giá âm, giá quá
     *                                                     lớn (&gt; 9 007 199 254 740 991, {@code MAX_SAFE_INTEGER}),
     *                                                     hoặc {@code minPrice > maxPrice} (trên {@code minPrice}).
     *                                                     Không đánh dấu rollback transaction của bên gọi: bắt lỗi
     *                                                     rồi vẫn commit được
     * @throws IllegalStateException                       không có ngữ cảnh Cửa hàng
     */
    PageResponse<SellableVariant> searchForSale(String query, UUID categoryId, Long minPrice, Long maxPrice,
                                                int page, int size);
}
