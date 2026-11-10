package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;
import vn.nhom17.banhang.platform.TenantScope;

/**
 * Ảnh Sản phẩm (FR-1, SP-01b) trên PostgreSQL thật: ma trận I/O của spec. Tải lên, thay, gỡ ảnh; kiểu nhận theo byte đầu
 * tệp (không theo tên hay {@code Content-Type} của client); giới hạn 1 MB; quyền của hai vai trò; Cửa hàng khác thấy
 * {@code 404}. Đổi, gỡ ảnh không đổi {@code changed_at} (change-feed). Mỗi test dùng Cửa hàng mới đăng ký riêng.
 *
 * <p>Giới hạn multipart của Tomcat (tệp lố cả giới hạn đó) cần máy chủ thật: xem
 * {@link ProductImageUploadLimitIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductImageIntegrationTest {

    static final String PRODUCTS = "/api/v1/catalog/products";
    static final int ONE_MB = 1_048_576;
    static final String CACHE_CONTROL = "private, max-age=86400";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;

    record Owner(Cookie cookie, UUID storeId) {
    }

    // --- Tải lên, thay, gỡ -----------------------------------------------------------------------------------------

    @Test
    void uploadingAPngGivesAVersionedUrlAndTheSameBytesBack() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Sữa tươi Vinamilk 1L");
        Map<String, Object> before = productRow(owner.storeId(), product);
        assertThat(before.get("image_url")).isNull();
        byte[] png = png(200 * 1024, 1);

        String url = imageUrl(product, 1);
        String response = upload(owner.cookie(), product, file("sua.png", "image/png", png))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.toString()))
                .andExpect(jsonPath("$.name").value("Sữa tươi Vinamilk 1L"))
                .andExpect(jsonPath("$.imageUrl").value(url))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Như GET chi tiết; danh sách cũng có imageUrl
        assertThat(json.readTree(response)).isEqualTo(json.readTree(detail(owner.cookie(), product)));
        mvc.perform(get(PRODUCTS).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(product.toString()))
                .andExpect(jsonPath("$.items[0].imageUrl").value(url));

        // GET đúng byte, đúng kiểu, cache riêng 1 ngày, không cho trình duyệt đoán kiểu; mở thẳng URL chỉ hiện ảnh
        mvc.perform(get(url).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", CACHE_CONTROL))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().longValue("Content-Length", png.length))
                .andExpect(content().bytes(png));

        // Lưu đúng một dòng ảnh; changed_at giữ nguyên (ảnh không thuộc searchText), updated_at lấy giờ mới
        assertThat(imageRow(owner.storeId(), product))
                .containsEntry("content_type", "image/png")
                .containsEntry("version", 1)
                .containsEntry("store_id", owner.storeId());
        Map<String, Object> after = productRow(owner.storeId(), product);
        assertThat(after.get("image_url")).isEqualTo(url);
        assertThat(instant(after.get("changed_at"))).isEqualTo(instant(before.get("changed_at")));
        assertThat(instant(after.get("updated_at"))).isAfter(instant(before.get("updated_at")));
        assertThat(instant(change(owner.storeId(), product))).isEqualTo(instant(before.get("changed_at")));
    }

    @Test
    void replacingTheImageBumpsTheVersionAndServesTheNewOne() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Bánh mì");
        upload(owner.cookie(), product, file("anh.png", "image/png", png(1000, 1))).andExpect(status().isOk());
        Map<String, Object> before = productRow(owner.storeId(), product);

        byte[] jpeg = jpeg(5000, 2);
        upload(owner.cookie(), product, file("anh-moi.jpg", "image/jpeg", jpeg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(imageUrl(product, 2)));

        // URL nào cũng trả ảnh hiện tại; v chỉ để đổi URL
        for (String url : List.of(imageUrl(product, 2), imageUrl(product, 1), PRODUCTS + "/" + product + "/image")) {
            mvc.perform(get(url).cookie(owner.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(content().bytes(jpeg));
        }
        assertThat(imageRow(owner.storeId(), product)).containsEntry("version", 2);
        assertThat(instant(productRow(owner.storeId(), product).get("changed_at")))
                .isEqualTo(instant(before.get("changed_at")));
    }

    @Test
    void kindIsTakenFromTheBytesNotFromTheClient() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Cà phê");

        // WebP gửi kèm Content-Type sai và tên .bin: vẫn là image/webp
        byte[] webp = webp(3000, 3);
        upload(owner.cookie(), product, file("anh.bin", "application/octet-stream", webp))
                .andExpect(status().isOk());
        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie()))
                .andExpect(header().string("Content-Type", "image/webp"))
                .andExpect(content().bytes(webp));

        // PNG gửi như JPEG: lưu là PNG
        byte[] png = png(3000, 4);
        upload(owner.cookie(), product, file("anh.jpg", "image/jpeg", png)).andExpect(status().isOk());
        mvc.perform(get(imageUrl(product, 2)).cookie(owner.cookie()))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));
    }

    @Test
    void removingTheImageGives204ThenNotFoundAndANullUrl() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Nước suối");
        upload(owner.cookie(), product, file("anh.png", "image/png", png(1000, 5))).andExpect(status().isOk());
        Map<String, Object> before = productRow(owner.storeId(), product);

        mvc.perform(delete(PRODUCTS + "/" + product + "/image").cookie(owner.cookie()))
                .andExpect(status().isNoContent());

        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        mvc.perform(get(PRODUCTS + "/" + product).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").isEmpty());
        Map<String, Object> after = productRow(owner.storeId(), product);
        assertThat(after.get("image_url")).isNull();
        assertThat(instant(after.get("changed_at"))).isEqualTo(instant(before.get("changed_at")));
        assertThat(instant(after.get("updated_at"))).isAfter(instant(before.get("updated_at")));
        // Dòng "đã gỡ": không còn byte nào, giữ số phiên bản
        assertThat(imageRow(owner.storeId(), product))
                .containsEntry("content_type", null)
                .containsEntry("size", 0)
                .containsEntry("version", 1);

        // Gỡ lại (hoặc gỡ khi chưa có ảnh): vẫn 204, không đổi gì
        mvc.perform(delete(PRODUCTS + "/" + product + "/image").cookie(owner.cookie()))
                .andExpect(status().isNoContent());
        assertThat(productRow(owner.storeId(), product)).isEqualTo(after);
        UUID noImage = createProduct(owner, "Chưa có ảnh");
        Map<String, Object> noImageBefore = productRow(owner.storeId(), noImage);
        mvc.perform(delete(PRODUCTS + "/" + noImage + "/image").cookie(owner.cookie()))
                .andExpect(status().isNoContent());
        assertThat(productRow(owner.storeId(), noImage)).isEqualTo(noImageBefore);
        assertThat(imageCount(owner.storeId(), noImage)).isZero();

        // Tải lại sau khi gỡ: phiên bản mới (v=2), không quay về URL ?v=1 mà trình duyệt có thể còn giữ ảnh cũ
        byte[] again = png(800, 6);
        upload(owner.cookie(), product, file("anh.png", "image/png", again))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(imageUrl(product, 2)));
        mvc.perform(get(imageUrl(product, 2)).cookie(owner.cookie())).andExpect(content().bytes(again));
    }

    // --- Tệp sai --------------------------------------------------------------------------------------------------

    @Test
    void aPdfNamedPngIsRejectedAsWrongType() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Sổ tay");
        Map<String, Object> before = productRow(owner.storeId(), product);
        byte[] pdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n".getBytes(StandardCharsets.US_ASCII);

        upload(owner.cookie(), product, file("anh.png", "image/png", pdf))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("/errors/catalog/image-type"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.requestId").exists());

        // Các tệp khác không phải JPEG, PNG, WebP: rỗng, quá ngắn, GIF, SVG, RIFF không phải WebP
        List<byte[]> others = List.of(new byte[0], new byte[] {(byte) 0xFF, (byte) 0xD8},
                "GIF89a....".getBytes(StandardCharsets.US_ASCII),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.US_ASCII),
                "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        for (byte[] other : others) {
            upload(owner.cookie(), product, file("anh.png", "image/png", other))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.type").value("/errors/catalog/image-type"));
        }
        assertThat(productRow(owner.storeId(), product)).isEqualTo(before);
        assertThat(imageCount(owner.storeId(), product)).isZero();
    }

    @Test
    void anImageOverOneMegabyteIsRejectedAsTooLarge() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Áo thun");
        Map<String, Object> before = productRow(owner.storeId(), product);

        upload(owner.cookie(), product, file("anh.png", "image/png", png(2 * ONE_MB, 7)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.type").value("/errors/catalog/image-too-large"))
                .andExpect(jsonPath("$.requestId").exists());
        // Một byte quá giới hạn cũng lố; quá cỡ báo trước sai kiểu
        upload(owner.cookie(), product, file("anh.png", "image/png", png(ONE_MB + 1, 8)))
                .andExpect(status().is(413));
        upload(owner.cookie(), product, file("tai-lieu.pdf", "application/pdf", new byte[ONE_MB + 1]))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.type").value("/errors/catalog/image-too-large"));
        assertThat(productRow(owner.storeId(), product)).isEqualTo(before);
        assertThat(imageCount(owner.storeId(), product)).isZero();

        // Đúng 1 MB thì được
        byte[] exactly = png(ONE_MB, 9);
        upload(owner.cookie(), product, file("anh.png", "image/png", exactly)).andExpect(status().isOk());
        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie())).andExpect(content().bytes(exactly));
    }

    @Test
    void aMissingFilePartOrANonMultipartBodyIsRejected() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Kẹo");

        mvc.perform(multipart(HttpMethod.PUT, PRODUCTS + "/" + product + "/image")
                        .file(file("anh.png", "image/png", png(100, 1), "anh")).cookie(owner.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        mvc.perform(put(PRODUCTS + "/" + product + "/image").cookie(owner.cookie())
                        .contentType(MediaType.IMAGE_PNG).content(png(100, 1)))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(imageCount(owner.storeId(), product)).isZero();
    }

    @Test
    void anUnreadablePartIsAValidationErrorNotAServerError() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Tệp hỏng");
        // Tệp tạm của phần multipart không đọc được (client ngắt giữa chừng, tệp tạm bị xóa…)
        MockMultipartFile unreadable = new MockMultipartFile("file", "anh.png", "image/png", png(100, 1)) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("Tệp tạm đã bị xóa");
            }
        };

        upload(owner.cookie(), product, unreadable)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/common/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
        assertThat(imageCount(owner.storeId(), product)).isZero();
    }

    // --- Quyền, Cửa hàng khác, trạng thái ----------------------------------------------------------------------

    @Test
    void staffCanSeeTheImageButCannotUploadOrRemoveIt() throws Exception {
        Owner owner = newStore();
        Cookie staff = addStaff(owner);
        UUID product = createProduct(owner, "Trà xanh");
        byte[] png = png(2000, 10);
        upload(owner.cookie(), product, file("anh.png", "image/png", png)).andExpect(status().isOk());

        mvc.perform(get(imageUrl(product, 1)).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(png));
        mvc.perform(get(PRODUCTS + "/" + product).cookie(staff))
                .andExpect(jsonPath("$.imageUrl").value(imageUrl(product, 1)));

        upload(staff, product, file("khac.png", "image/png", png(2000, 11)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(delete(PRODUCTS + "/" + product + "/image").cookie(staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/common/forbidden"));
        mvc.perform(get(imageUrl(product, 1))).andExpect(status().isUnauthorized());

        assertThat(imageRow(owner.storeId(), product)).containsEntry("version", 1);
        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie())).andExpect(content().bytes(png));
    }

    @Test
    void anotherStoresProductIsNotFound() throws Exception {
        Owner a = newStore();
        Owner b = newStore();
        UUID ofB = createProduct(b, "Sản phẩm của B");
        byte[] png = png(1500, 12);
        upload(b.cookie(), ofB, file("anh.png", "image/png", png)).andExpect(status().isOk());
        Map<String, Object> before = productRow(b.storeId(), ofB);

        mvc.perform(get(imageUrl(ofB, 1)).cookie(a.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        upload(a.cookie(), ofB, file("anh.png", "image/png", png(1500, 13)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        mvc.perform(delete(PRODUCTS + "/" + ofB + "/image").cookie(a.cookie()))
                .andExpect(status().isNotFound());
        // Id không có cũng vậy; tệp sai báo trước 404
        upload(a.cookie(), UUID.randomUUID(), file("anh.png", "image/png", png(100, 1)))
                .andExpect(status().isNotFound());
        upload(a.cookie(), UUID.randomUUID(), file("anh.png", "image/png", new byte[10]))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(productRow(b.storeId(), ofB)).isEqualTo(before);
        assertThat(imageRow(b.storeId(), ofB)).containsEntry("version", 1);
        mvc.perform(get(imageUrl(ofB, 1)).cookie(b.cookie())).andExpect(content().bytes(png));
        // Cửa hàng A không thấy dòng ảnh nào của B (RLS)
        assertThat(asStore(a.storeId(), () -> jdbc.queryForObject("select count(*) from app.product_image",
                Long.class))).isZero();
    }

    @Test
    void aSoftDeletedProductIsNotFound() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Đã xóa mềm");
        upload(owner.cookie(), product, file("anh.png", "image/png", png(1500, 16))).andExpect(status().isOk());
        // Chưa có API xóa Sản phẩm: đặt deleted_at thẳng trong database
        asStore(owner.storeId(), () -> jdbc.update("update app.product set deleted_at = now() where id = ?", product));
        Map<String, Object> before = productRow(owner.storeId(), product);

        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        upload(owner.cookie(), product, file("anh.png", "image/png", png(1500, 17)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));
        mvc.perform(delete(PRODUCTS + "/" + product + "/image").cookie(owner.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/common/not-found"));

        assertThat(productRow(owner.storeId(), product)).isEqualTo(before);
        assertThat(imageRow(owner.storeId(), product)).containsEntry("version", 1).containsEntry("size", 1500);
    }

    @Test
    void aDeactivatedProductKeepsItsImageButItCannotBeChanged() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Hàng ngừng bán");
        byte[] png = png(1500, 14);
        upload(owner.cookie(), product, file("anh.png", "image/png", png)).andExpect(status().isOk());
        mvc.perform(post(PRODUCTS + "/" + product + "/deactivate").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(imageUrl(product, 1)));
        Map<String, Object> before = productRow(owner.storeId(), product);

        upload(owner.cookie(), product, file("anh.png", "image/png", png(1500, 15)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));
        mvc.perform(delete(PRODUCTS + "/" + product + "/image").cookie(owner.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/errors/catalog/product-inactive"));

        assertThat(productRow(owner.storeId(), product)).isEqualTo(before);
        mvc.perform(get(imageUrl(product, 1)).cookie(owner.cookie())).andExpect(content().bytes(png));
    }

    @Test
    void concurrentUploadsGetOneVersionEach() throws Exception {
        Owner owner = newStore();
        UUID product = createProduct(owner, "Tải cùng lúc");
        int callers = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                byte[] png = png(4000, 100 + i);
                results.add(pool.submit(() -> {
                    start.await();
                    String body = upload(owner.cookie(), product, file("anh.png", "image/png", png))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                    return json.readTree(body).get("imageUrl").asString();
                }));
            }
            start.countDown();
            List<String> urls = new ArrayList<>();
            for (Future<String> result : results) {
                urls.add(result.get());
            }
            assertThat(urls).containsExactlyInAnyOrder(imageUrl(product, 1), imageUrl(product, 2),
                    imageUrl(product, 3), imageUrl(product, 4), imageUrl(product, 5));
        } finally {
            pool.shutdownNow();
        }
        assertThat(imageRow(owner.storeId(), product)).containsEntry("version", 5);
        assertThat(productRow(owner.storeId(), product).get("image_url")).isEqualTo(imageUrl(product, 5));
    }

    // --- Hỗ trợ ---------------------------------------------------------------------------------------------------

    static String imageUrl(UUID productId, int version) {
        return PRODUCTS + "/" + productId + "/image?v=" + version;
    }

    /** {@code size} byte bắt đầu bằng chữ ký PNG, phần sau ngẫu nhiên theo {@code seed}. */
    static byte[] png(int size, long seed) {
        return withMagic(size, seed, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    }

    static byte[] jpeg(int size, long seed) {
        return withMagic(size, seed, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    static byte[] webp(int size, long seed) {
        byte[] bytes = withMagic(size, seed, "RIFF".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy("WEBPVP8 ".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 8);
        return bytes;
    }

    private static byte[] withMagic(int size, long seed, byte[] magic) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        System.arraycopy(magic, 0, bytes, 0, Math.min(magic.length, size));
        return bytes;
    }

    static MockMultipartFile file(String filename, String contentType, byte[] bytes) {
        return file(filename, contentType, bytes, "file");
    }

    static MockMultipartFile file(String filename, String contentType, byte[] bytes, String part) {
        return new MockMultipartFile(part, filename, contentType, bytes);
    }

    ResultActions upload(Cookie cookie, UUID productId, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, PRODUCTS + "/" + productId + "/image").file(file).cookie(cookie));
    }

    String detail(Cookie cookie, UUID productId) throws Exception {
        return mvc.perform(get(PRODUCTS + "/" + productId).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    UUID createProduct(Owner owner, String name) throws Exception {
        String response = mvc.perform(post(PRODUCTS).cookie(owner.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "salePrice", 15000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    Map<String, Object> productRow(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select name, image_url, status, updated_at, changed_at from app.product where id = ?
                """, productId));
    }

    /** Dòng ảnh của Sản phẩm (không kèm byte): kiểu, số byte, phiên bản, Cửa hàng. */
    Map<String, Object> imageRow(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForMap("""
                select store_id, content_type, octet_length(content) as size, version, updated_at
                from app.product_image where product_id = ?
                """, productId));
    }

    long imageCount(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForObject(
                "select count(*) from app.product_image where product_id = ?", Long.class, productId));
    }

    /** {@code changed_at} của Sản phẩm trong view change-feed (AD-12). */
    Object change(UUID storeId, UUID productId) {
        return asStore(storeId, () -> jdbc.queryForObject(
                "select changed_at from app.catalog_product_changes where product_id = ?", Object.class, productId));
    }

    static Instant instant(Object timestamp) {
        return ProductDeactivateIntegrationTest.instant(timestamp);
    }

    /** Đăng ký Cửa hàng mới (FR-43), trả cookie phiên của Chủ cửa hàng. */
    Owner newStore() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("storeName", "Cửa hàng thử ảnh Sản phẩm");
        body.put("loginKey", "anh." + UUID.randomUUID().toString().replace("-", "") + "@test.vn");
        body.put("password", "matkhau123");
        body.put("displayName", "Chủ thử");
        var response = mvc.perform(post("/api/v1/platform/stores").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        JsonNode me = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return new Owner(response.getCookie("access_token"), UUID.fromString(me.get("storeId").asString()));
    }

    /** Thêm một Nhân viên vào Cửa hàng của {@code owner} (chưa có API tạo Nhân viên), đăng nhập và trả cookie. */
    Cookie addStaff(Owner owner) throws Exception {
        String loginKey = "nv." + UUID.randomUUID().toString().replace("-", "") + "@test.vn";
        asStore(owner.storeId(), () -> jdbc.update("""
                insert into app.app_user (store_id, login_key, display_name, password_hash, role)
                values (?, ?, 'Nhân viên thử', ?, 'STAFF')
                """, owner.storeId(), loginKey, passwords.encode("matkhau123")));
        Cookie cookie = mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginKey", loginKey, "password", "matkhau123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andReturn().getResponse().getCookie("access_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    <T> T asStore(UUID storeId, Supplier<T> work) {
        return TenantScope.runAs(storeId, () -> new TransactionTemplate(txManager).execute(s -> work.get()));
    }
}
