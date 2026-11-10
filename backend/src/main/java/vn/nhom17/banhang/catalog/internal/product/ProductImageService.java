package vn.nhom17.banhang.catalog.internal.product;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.nhom17.banhang.catalog.internal.FieldErrors;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/**
 * Ảnh Sản phẩm (FR-1, SP-01b): một ảnh mỗi Sản phẩm, lưu ở bảng {@code app.product_image} (xem
 * {@link ProductImageEntity}).
 *
 * <ul>
 *   <li>Nhận JPEG, PNG, WebP tối đa {@value #MAX_BYTES} byte (1 MB). Kiểu lấy từ byte đầu tệp (magic number,
 *       {@link #sniff}), không tin {@code Content-Type} hay tên tệp của client. Quá cỡ: {@code 413
 *       /errors/catalog/image-too-large}; không phải một trong ba kiểu: {@code 415 /errors/catalog/image-type}. Lỗi
 *       tệp báo trước {@code 404}, {@code 409}, như dữ liệu sai khi sửa Sản phẩm.</li>
 *   <li>Tải lên hoặc thay ảnh tăng {@code version} và ghi {@code product.image_url} =
 *       {@code /api/v1/catalog/products/{id}/image?v=<version>}, nên đổi ảnh là đổi URL (trình duyệt không giữ ảnh cũ).
 *       Gỡ ảnh đặt {@code image_url} về {@code null}, giữ {@code version} (dòng "đã gỡ").</li>
 *   <li>Đổi, gỡ ảnh lấy giờ mới cho {@code product.updated_at} nhưng KHÔNG đổi {@code changed_at}: ảnh không thuộc
 *       {@code searchText} của change-feed (AD-12).</li>
 *   <li>Khóa dòng {@code product} trước (cùng thứ tự với sửa và ngừng kinh doanh), nên hai lần tải ảnh cùng lúc chạy
 *       lần lượt và mỗi lần ra một phiên bản. Sản phẩm đã ngừng kinh doanh không đổi được ảnh:
 *       {@code 409 /errors/catalog/product-inactive}, như sửa (AD-14). Id không có, đã xóa mềm hoặc của Cửa hàng
 *       khác: {@code 404}.</li>
 * </ul>
 */
@Service
class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    /** 1 MB; trùng với CHECK của cột {@code content}. */
    static final int MAX_BYTES = 1_048_576;
    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String WEBP = "image/webp";

    static final String IMAGE_TOO_LARGE = "/errors/catalog/image-too-large";
    static final String IMAGE_TYPE = "/errors/catalog/image-type";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    /**
     * Khóa Sản phẩm (chưa xóa mềm) của Cửa hàng tới hết transaction. Chỉ khóa dòng {@code product}, như bước đầu của
     * sửa và ngừng kinh doanh, nên không tạo thứ tự khóa ngược với hai thao tác đó.
     */
    private static final String LOCK_PRODUCT = """
            select status from app.product where store_id = ? and id = ? and deleted_at is null for update
            """;
    /** Ảnh hiện tại của Sản phẩm chưa xóa mềm; dòng đã gỡ ảnh ({@code content_type} {@code null}) coi như không có. */
    private static final String CURRENT_IMAGE = """
            select i.content_type, i.content
            from app.product_image i
            join app.product p on p.store_id = i.store_id and p.id = i.product_id and p.deleted_at is null
            where i.store_id = ? and i.product_id = ? and i.content_type is not null
            """;

    private final ProductImageRepository images;
    private final ProductService products;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    ProductImageService(ProductImageRepository images, ProductService products, EntityManager em, JdbcTemplate jdbc,
                        CurrentContext context, Clock clock) {
        this.images = images;
        this.products = products;
        this.em = em;
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    /** Ảnh đã kiểm: kiểu lấy từ nội dung, không quá {@value #MAX_BYTES} byte. */
    record Image(String contentType, byte[] content) {
    }

    /**
     * Kiểm tệp tải lên, gọi NGOÀI transaction (trước khi khóa Sản phẩm). Thiếu phần {@code file}, hoặc không đọc được
     * tệp: {@code 400 /errors/common/validation} trên trường {@code file}; quá cỡ: {@code 413}; không phải JPEG, PNG,
     * WebP (kể cả tệp rỗng): {@code 415}.
     */
    Image check(MultipartFile file) {
        if (file == null) {
            throw FieldErrors.of("file", "Chọn ảnh để tải lên");
        }
        // Kiểm cỡ trước khi đọc, rồi kiểm lại trên số byte đọc được
        if (file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            // Tệp tạm bị mất, client ngắt giữa chừng…: lỗi của lần gửi này, không phải lỗi máy chủ
            log.warn("Không đọc được tệp ảnh tải lên: {}", unreadable.toString());
            throw FieldErrors.of("file", "Không đọc được tệp gửi lên. Hãy chọn lại ảnh rồi thử lại.");
        }
        if (content.length > MAX_BYTES) {
            throw tooLarge();
        }
        String contentType = sniff(content);
        if (contentType == null) {
            throw wrongType();
        }
        return new Image(contentType, content);
    }

    /** Ảnh hiện tại, cả hai vai trò. Chưa có ảnh, đã gỡ, Sản phẩm không có hoặc của Cửa hàng khác: {@code 404}. */
    @Transactional(readOnly = true)
    Image get(UUID productId) {
        List<Image> found = jdbc.query(CURRENT_IMAGE, (rs, i) -> new Image(rs.getString("content_type"),
                rs.getBytes("content")), context.storeId(), productId);
        if (found.isEmpty()) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy", "Sản phẩm này chưa có ảnh.");
        }
        return found.getFirst();
    }

    /**
     * Tải lên hoặc thay ảnh (đã qua {@link #check}); trả chi tiết Sản phẩm như {@code GET /{id}}, có {@code imageUrl}
     * mới.
     */
    @Transactional
    ProductView upload(UUID productId, Image image) {
        UUID storeId = context.storeId();
        lockActiveProduct(storeId, productId);
        Instant now = clock.instant();
        ProductImageEntity entity = images.findById(productId).orElse(null);
        if (entity == null) {
            entity = new ProductImageEntity(productId, image.contentType(), image.content(), now);
            em.persist(entity);
        } else {
            entity.replace(image.contentType(), image.content(), now);
        }
        em.flush();
        jdbc.update("update app.product set image_url = ?, updated_at = ? where store_id = ? and id = ?",
                imageUrl(productId, entity.version()), utc(now), storeId, productId);
        return products.get(productId);
    }

    /** Gỡ ảnh. Sản phẩm chưa có ảnh (hoặc đã gỡ) thì không đổi gì; vẫn thành công. */
    @Transactional
    void remove(UUID productId) {
        UUID storeId = context.storeId();
        lockActiveProduct(storeId, productId);
        Instant now = clock.instant();
        ProductImageEntity entity = images.findById(productId).orElse(null);
        if (entity == null || !entity.remove(now)) {
            return;
        }
        em.flush();
        jdbc.update("update app.product set image_url = null, updated_at = ? where store_id = ? and id = ?",
                utc(now), storeId, productId);
    }

    /** Đường dẫn {@code GET} của ảnh kèm phiên bản, lưu ở {@code product.image_url}. */
    static String imageUrl(UUID productId, int version) {
        return ProductController.PATH + "/" + productId + "/image?v=" + version;
    }

    /** Kiểu ảnh theo byte đầu tệp; {@code null} khi không phải JPEG, PNG hay WebP. */
    static String sniff(byte[] content) {
        if (startsWith(content, 0, JPEG_MAGIC)) {
            return JPEG;
        }
        if (startsWith(content, 0, PNG_MAGIC)) {
            return PNG;
        }
        // RIFF <4 byte độ dài> WEBP
        if (startsWith(content, 0, RIFF) && startsWith(content, 8, WEBP_MAGIC)) {
            return WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, byte[] magic) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private void lockActiveProduct(UUID storeId, UUID productId) {
        List<String> status = jdbc.queryForList(LOCK_PRODUCT, String.class, storeId, productId);
        if (status.isEmpty()) {
            throw ProductService.notFound();
        }
        if (ProductEntity.INACTIVE.equals(status.getFirst())) {
            throw ProductService.productInactive();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static ProblemException tooLarge() {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, IMAGE_TOO_LARGE, "Ảnh quá lớn",
                "Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn, hoặc giảm kích thước ảnh rồi tải lại.");
    }

    private static ProblemException wrongType() {
        return new ProblemException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, IMAGE_TYPE, "Không phải ảnh JPEG, PNG hay WebP",
                "Chỉ nhận ảnh JPEG, PNG hoặc WebP. Tệp này không phải một trong ba loại đó (kiểm theo nội dung tệp, "
                        + "không theo tên tệp).");
    }
}
