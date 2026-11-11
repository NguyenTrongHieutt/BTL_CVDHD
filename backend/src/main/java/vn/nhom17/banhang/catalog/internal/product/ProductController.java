package vn.nhom17.banhang.catalog.internal.product;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Sản phẩm (FR-1, SP-01; FR-2, SP-02; FR-3, SP-03; FR-5, SP-05). Xem chi tiết và danh sách (có tìm kiếm, lọc): cả hai
 * vai trò; thêm, sửa, ngừng kinh doanh: chỉ Chủ cửa hàng. Quyền khai báo ở {@code SecurityConfig} của platform
 * (AD-16). Thêm có {@code Idempotency-Key} (AD-7): lần đầu {@code 201}, gửi lại cùng nội dung {@code 200} +
 * {@code Idempotent-Replay: true}.
 */
@RestController
@RequestMapping(ProductController.PATH)
class ProductController {

    static final String PATH = "/api/v1/catalog/products";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replay";

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    /**
     * Các trường thêm và sửa cùng có, kiểm chung một chỗ ({@link ProductService}), để SP-01 và SP-02 không viết hai
     * lần. Record của từng request hiện thực interface này bằng chính các thành phần cùng tên.
     */
    interface ProductFieldsInput {

        String name();

        UUID categoryId();

        String unit();

        String description();

        BigDecimal salePrice();

        String sku();

        String barcode();
    }

    /**
     * Body thêm Sản phẩm; kiểm ở {@link ProductService#validate}, trả lỗi theo trường. Tiền là số nguyên đồng, số
     * lượng là số nguyên; nhận {@link BigDecimal} để số lẻ bị báo lỗi thay vì bị cắt.
     *
     * @param name            bắt buộc, tối đa 200 ký tự
     * @param categoryId      tùy chọn; phải là Danh mục của Cửa hàng
     * @param unit            tối đa 30 ký tự; trống thì "cái"
     * @param description     tối đa 2000 ký tự, được xuống dòng
     * @param salePrice       bắt buộc, ≥ 0
     * @param sku             tối đa 50 ký tự; trống thì sinh {@code SP-} + 8 ký tự đầu của id Biến thể
     * @param barcode         tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số
     * @param openingQuantity Tồn kho đầu kỳ, ≥ 0, mặc định 0
     * @param openingUnitCost Giá vốn ban đầu, ≥ 0, mặc định 0; phải lớn hơn 0 khi có Tồn kho đầu kỳ. Chỉ chuyển sang
     *                        {@code inventory} qua sự kiện, không lưu ở catalog, không có trong phản hồi (AD-15)
     */
    record CreateProductRequest(String name, UUID categoryId, String unit, String description, BigDecimal salePrice,
                                String sku, String barcode, BigDecimal openingQuantity, BigDecimal openingUnitCost)
            implements ProductFieldsInput {
    }

    /**
     * Body sửa Sản phẩm (FR-2, SP-02), <b>thay toàn bộ</b>; cùng quy tắc trường với {@link CreateProductRequest}
     * (kiểm chung ở {@link ProductService}). {@code salePrice}, {@code sku}, {@code barcode} là của Biến thể mặc
     * định. Không có Tồn kho, Giá vốn: Giá vốn chỉ đổi qua nhập hàng (AD-15).
     *
     * @param name        bắt buộc, tối đa 200 ký tự
     * @param categoryId  phải là Danh mục của Cửa hàng; bỏ trống thì Sản phẩm không thuộc Danh mục nào
     * @param unit        tối đa 30 ký tự; trống thì "cái"
     * @param description tối đa 2000 ký tự, được xuống dòng; trống thì xóa
     * @param salePrice   bắt buộc, ≥ 0; đổi thì ghi Nhật ký {@code catalog.variant.price_change}
     * @param sku         tối đa 50 ký tự; trống thì giữ SKU hiện tại
     * @param barcode     tối đa 50 ký tự, chỉ chữ cái không dấu và chữ số; trống thì xóa
     */
    record UpdateProductRequest(String name, UUID categoryId, String unit, String description, BigDecimal salePrice,
                                String sku, String barcode) implements ProductFieldsInput {
    }

    /**
     * Danh sách có Tìm kiếm thường và lọc (FR-5, SP-05), cả hai vai trò. Tham số nhận dạng chuỗi và kiểm ở
     * {@link ProductService#list}, để giá trị sai trả {@code 400 /errors/common/validation} theo trường. Xem
     * {@link ProductService.ListQuery}.
     */
    @GetMapping
    PageResponse<ProductView> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String categoryId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String minPrice,
                                   @RequestParam(required = false) String maxPrice,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size,
                                   @RequestParam(required = false) String sort) {
        return products.list(new ProductService.ListQuery(q, categoryId, status, minPrice, maxPrice, page, size,
                sort));
    }

    @GetMapping("/{id}")
    ProductView get(@PathVariable UUID id) {
        return products.get(id);
    }

    @PostMapping
    ResponseEntity<ProductView> create(@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
                                       String idempotencyKey,
                                       @RequestBody CreateProductRequest request) {
        ProductService.Created result = products.create(parseKey(idempotencyKey), request);
        if (result.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAY_HEADER, "true").body(result.product());
        }
        return ResponseEntity.created(URI.create(PATH + "/" + result.product().id())).body(result.product());
    }

    /**
     * Sửa (FR-2, SP-02): thay toàn bộ, trả chi tiết như {@code GET /{id}}. Không có {@code Idempotency-Key}: gửi lại
     * cùng nội dung ra cùng kết quả và không ghi gì lần hai. Xem {@link ProductService#update}.
     */
    @PutMapping("/{id}")
    ProductView update(@PathVariable UUID id, @RequestBody UpdateProductRequest request) {
        return products.update(id, request);
    }

    /**
     * Ngừng kinh doanh (FR-3): không có body, trả chi tiết như {@code GET /{id}}. Một chiều; gọi lại với Sản phẩm đã
     * ngừng vẫn {@code 200} với trạng thái hiện tại, nên không cần {@code Idempotency-Key}.
     */
    @PostMapping("/{id}/deactivate")
    ProductView deactivate(@PathVariable UUID id) {
        return products.deactivate(id);
    }

    /**
     * Thiếu hoặc không phải UUID đều coi là thiếu khóa: {@code 422 idempotency-key-missing}, báo sau lỗi dữ liệu
     * ({@code 400}) như đăng ký Cửa hàng.
     */
    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            UUID key = UUID.fromString(text);
            // UUID.fromString nhận cả dạng rút gọn như "1-1-1-1-1"
            return key.toString().equalsIgnoreCase(text) ? key : null;
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
