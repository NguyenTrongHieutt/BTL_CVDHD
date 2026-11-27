package vn.nhom17.banhang.catalog.internal.product;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.catalog.internal.product.ProductController.VariantInput;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;

/**
 * Biến thể của một Sản phẩm (FR-7, SP-07): thêm, sửa, ngừng kinh doanh từng Biến thể. Chỉ Chủ cửa hàng
 * ({@code SecurityConfig}: mọi phương thức ghi trên {@code /api/v1/catalog/**}). Mỗi thao tác trả chi tiết Sản phẩm
 * như {@code GET /api/v1/catalog/products/{id}}. Xem {@link ProductVariantService}.
 */
@RestController
@RequestMapping(ProductController.PATH + "/{id}/variants")
class ProductVariantController {

    private final ProductVariantService variants;
    private final BackofficeWriteQueue queue;

    ProductVariantController(ProductVariantService variants, BackofficeWriteQueue queue) {
        this.variants = variants;
        this.queue = queue;
    }

    /**
     * Body thêm một Biến thể: các trường như {@link VariantInput} (kèm Tồn kho đầu kỳ, Giá vốn ban đầu riêng), và
     * {@code existingVariantName}: nhãn cho Biến thể mặc định hiện có, bắt buộc khi Sản phẩm đang chỉ có một Biến thể
     * ({@code hasVariants = false}); Sản phẩm đã có nhiều Biến thể thì bỏ qua.
     */
    record AddVariantRequest(String existingVariantName, String name, Map<String, String> attributes, String sku,
                             String barcode, BigDecimal salePrice, BigDecimal openingQuantity,
                             BigDecimal openingUnitCost) {

        VariantInput variant() {
            return new VariantInput(name, attributes, sku, barcode, salePrice, openingQuantity, openingUnitCost);
        }
    }

    /**
     * Body sửa một Biến thể, <b>thay toàn bộ</b>: {@code name} trống thì ghép giá trị thuộc tính; {@code sku} trống
     * thì giữ SKU hiện tại; {@code barcode} trống thì xóa; {@code salePrice} bắt buộc (đổi thì ghi Nhật ký đổi giá).
     * Không có Tồn kho, Giá vốn (AD-15), không đổi trạng thái.
     */
    record UpdateVariantRequest(String name, Map<String, String> attributes, String sku, String barcode,
                                BigDecimal salePrice) {
    }

    /**
     * Thêm một Biến thể, có {@code Idempotency-Key} (kèm Tồn kho đầu kỳ, AD-7): lần đầu {@code 201}, gửi lại cùng
     * nội dung {@code 200} + {@code Idempotent-Replay: true}.
     */
    @PostMapping
    ResponseEntity<?> add(@PathVariable UUID id,
                                    @RequestHeader(name = ProductController.IDEMPOTENCY_KEY_HEADER, required = false)
                                    String idempotencyKey,
                                    @RequestBody AddVariantRequest request) {
        UUID key = ProductController.parseKey(idempotencyKey);
        if (queue.enabled()) return queue.submit("PRODUCT_VARIANT_ADD", key,
                new ProductWriteCommands.AddVariant(id, key, request)).response();
        return addedResponse(variants.add(id, key, request));
    }

    static ResponseEntity<ProductView> addedResponse(ProductService.Created result) {
        if (result.replayed()) {
            return ResponseEntity.ok().header(ProductController.IDEMPOTENT_REPLAY_HEADER, "true")
                    .body(result.product());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.product());
    }

    @PutMapping("/{variantId}")
    Object update(@PathVariable UUID id, @PathVariable UUID variantId,
                       @RequestHeader(name = ProductController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                       @RequestBody UpdateVariantRequest request) {
        if (queue.enabled()) return queue.submit("PRODUCT_VARIANT_UPDATE", BackofficeWriteQueue.optionalKey(idempotencyKey),
                new ProductWriteCommands.UpdateVariant(id, variantId, request)).response();
        return variants.update(id, variantId, request);
    }

    /** Ngừng kinh doanh một Biến thể: không có body; một chiều, gọi lại với Biến thể đã ngừng vẫn {@code 200}. */
    @PostMapping("/{variantId}/deactivate")
    Object deactivate(@PathVariable UUID id, @PathVariable UUID variantId,
                       @RequestHeader(name = ProductController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        if (queue.enabled()) return queue.submit("PRODUCT_VARIANT_DEACTIVATE", BackofficeWriteQueue.optionalKey(idempotencyKey),
                new ProductWriteCommands.DeactivateVariant(id, variantId)).response();
        return variants.deactivate(id, variantId);
    }
}
