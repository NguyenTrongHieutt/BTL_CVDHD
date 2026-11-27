package vn.nhom17.banhang.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Thông tin Biến thể cho sales và inventory (AD-2, AD-8). Hiện thực ở catalog (Hiếu, SP-05). Các phương thức cho sales
 * chỉ trả hàng còn bán: Biến thể {@code ACTIVE}, chưa xóa mềm, của Sản phẩm {@code ACTIVE}, chưa xóa mềm, của Cửa hàng
 * hiện tại. Các phương thức cho inventory có thể gồm hàng đã ngừng kinh doanh. Không có Giá vốn; không đọc Tồn kho
 * (lọc theo Tồn kho là việc của sales). Gọi trong hoặc ngoài transaction đều được.
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

    /** Chỉ lấy mã Biến thể còn bán để module khác lọc và phân trang trước khi tải thông tin chi tiết. */
    List<UUID> findForSaleIds();

    /**
     * Tra đúng toàn bộ SKU hoặc mã vạch của hàng còn bán trong Cửa hàng hiện tại. Chuẩn hóa như mã khi lưu
     * (văn bản một dòng, NFC, cắt khoảng trắng hai đầu, chữ HOA); không bỏ dấu, không gộp khoảng trắng giữa mã,
     * không đổi sang số. Mã trống, không hợp lệ hoặc không có thì trả danh sách rỗng. Một Biến thể khớp cả hai
     * trường chỉ trả một lần; SKU của Biến thể này trùng mã vạch Biến thể khác thì trả cả hai, sắp như tìm kiếm.
     *
     * @throws IllegalStateException không có ngữ cảnh Cửa hàng, kể cả khi {@code code} trống
     */
    List<SellableVariant> findForSaleByExactCode(String code);

    /** Biến thể chưa xóa mềm khớp bộ lọc của màn Tồn kho. */
    List<SellableVariant> findForInventory(String query, UUID categoryId, boolean includeInactive);

    /**
     * Biến thể trong {@code variantIds} chưa xóa mềm của Cửa hàng hiện tại, gồm cả Sản phẩm và Biến thể đã ngừng
     * kinh doanh. Giữ thứ tự id đầu vào, bỏ id trùng, {@code null}, id lạ và id của Cửa hàng khác; bỏ Biến thể hoặc
     * Sản phẩm đã xóa mềm.
     *
     * @throws IllegalStateException không có ngữ cảnh Cửa hàng, kể cả khi {@code variantIds} rỗng
     */
    List<SellableVariant> getForInventory(Collection<UUID> variantIds);

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
