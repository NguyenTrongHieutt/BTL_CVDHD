package vn.nhom17.banhang.catalog.internal.product;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ảnh Sản phẩm (FR-1, SP-01b), xem {@link ProductImageService}. {@code GET}: cả hai vai trò ({@code <img src>} cùng
 * địa chỉ nên cookie phiên tự gửi kèm); {@code PUT}, {@code DELETE}: chỉ Chủ cửa hàng. Quyền khai báo ở
 * {@code SecurityConfig} của platform (GET {@code /api/v1/catalog/**} cả hai vai trò, phương thức khác chỉ Chủ cửa
 * hàng, AD-16).
 */
@RestController
@RequestMapping(ProductImageController.PATH)
class ProductImageController {

    static final String PATH = ProductController.PATH + "/{id}/image";
    /** Phần multipart chứa tệp ảnh. */
    static final String FILE_PART = "file";
    /**
     * Trình duyệt giữ ảnh 1 ngày: URL có {@code ?v=<phiên bản>} nên đổi ảnh là đổi URL. {@code private}: ảnh của Cửa
     * hàng, proxy dùng chung không được giữ.
     */
    static final String CACHE_CONTROL = "private, max-age=86400";
    static final String NO_SNIFF = "nosniff";
    /** Tệp do người dùng tải lên: không chạy script, không tải gì thêm, kể cả khi mở thẳng URL. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    private final ProductImageService images;

    ProductImageController(ProductImageService images) {
        this.images = images;
    }

    /**
     * Byte của ảnh với đúng {@code Content-Type} đã nhận diện lúc tải lên. Tham số {@code v} trên URL chỉ để đổi URL
     * khi đổi ảnh, luôn trả ảnh hiện tại. Chưa có ảnh, id không có hoặc của Cửa hàng khác: {@code 404}. Mở thẳng URL
     * thì chỉ hiện ảnh: {@code nosniff}, CSP {@value #CONTENT_SECURITY_POLICY}, {@code Content-Disposition: inline}.
     */
    @GetMapping
    ResponseEntity<byte[]> get(@PathVariable UUID id) {
        ProductImageService.Image image = images.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.content().length)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", NO_SNIFF)
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.content());
    }

    /**
     * Tải lên hoặc thay ảnh: {@code multipart/form-data}, tệp ở phần {@value #FILE_PART}. Trả chi tiết Sản phẩm như
     * {@code GET /api/v1/catalog/products/{id}}, {@code imageUrl} có phiên bản mới.
     */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ProductView put(@PathVariable UUID id, @RequestParam(name = FILE_PART, required = false) MultipartFile file) {
        return images.upload(id, images.check(file));
    }

    /** Gỡ ảnh: {@code 204}, kể cả khi Sản phẩm chưa có ảnh. */
    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        images.remove(id);
        return ResponseEntity.noContent().build();
    }
}
