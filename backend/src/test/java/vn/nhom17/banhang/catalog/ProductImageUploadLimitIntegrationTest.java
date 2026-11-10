package vn.nhom17.banhang.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.TestcontainersConfiguration;

/**
 * Ảnh Sản phẩm (SP-01b) qua Tomcat thật: phần multipart được Tomcat đọc theo {@code spring.servlet.multipart.*} (2 MB
 * mỗi tệp, 3 MB mỗi yêu cầu, cao hơn giới hạn 1 MB của ảnh) và {@code server.tomcat.max-swallow-size}, điều MockMvc bỏ
 * qua. Ảnh lố 1 MB mà dưới giới hạn đó tới được controller ({@code 413 /errors/catalog/image-too-large}); lố cả giới hạn
 * đó thì bị chặn trước controller ({@code 413 /errors/common/payload-too-large}), kể cả body lớn hơn nhiều mà vẫn nhận
 * được phản hồi thay vì bị đóng kết nối. Body multipart hỏng là lỗi dữ liệu ({@code 400}), không phải lỗi máy chủ. Ma
 * trận đầy đủ ở {@link ProductImageIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ProductImageUploadLimitIntegrationTest {

    static final int ONE_MB = 1_048_576;
    static final String BOUNDARY = "----anh-san-pham-" + UUID.randomUUID();

    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void oversizeImagesGetTheSameProblemWhetherTomcatOrTheAppRejectsThem() throws Exception {
        String cookie = newStore();
        UUID product = createProduct(cookie);

        // Lố giới hạn mỗi tệp của Tomcat (2 MB): chặn trước controller, mã chung
        byte[] overLimit = ProductImageIntegrationTest.png(2 * ONE_MB + ONE_MB / 2, 1);
        HttpResponse<String> overTomcat = upload(cookie, product, overLimit);
        assertThat(overTomcat.statusCode()).isEqualTo(413);
        assertThat(problemType(overTomcat)).isEqualTo("/errors/common/payload-too-large");

        // Lố cả giới hạn mỗi yêu cầu (3 MB), bị từ chối trước khi đọc body: Tomcat vẫn đọc bỏ phần còn lại
        // (max-swallow-size) nên client nhận được 413 chứ không bị đóng kết nối
        HttpResponse<String> overRequest = upload(cookie, product, ProductImageIntegrationTest.png(5 * ONE_MB, 4));
        assertThat(overRequest.statusCode()).isEqualTo(413);
        assertThat(problemType(overRequest)).isEqualTo("/errors/common/payload-too-large");

        // Đúng 2 MB (spec): tới controller, bị giới hạn 1 MB của ảnh chặn
        HttpResponse<String> twoMegabytes = upload(cookie, product, ProductImageIntegrationTest.png(2 * ONE_MB, 2));
        assertThat(twoMegabytes.statusCode()).isEqualTo(413);
        assertThat(problemType(twoMegabytes)).isEqualTo("/errors/catalog/image-too-large");

        // Ảnh hợp lệ qua Tomcat thật: tải lên được, GET trả đúng byte và header (bộ lọc header của Spring Security
        // không ghi đè Cache-Control)
        byte[] png = ProductImageIntegrationTest.png(300 * 1024, 3);
        HttpResponse<String> uploaded = upload(cookie, product, png);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        String imageUrl = json.readTree(uploaded.body()).get("imageUrl").asString();
        assertThat(imageUrl).isEqualTo(ProductImageIntegrationTest.imageUrl(product, 1));

        HttpResponse<byte[]> image = http.send(HttpRequest.newBuilder(uri(imageUrl)).header("Cookie", cookie)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(png);
        assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(image.headers().allValues("Cache-Control")).containsExactly(ProductImageIntegrationTest.CACHE_CONTROL);
        assertThat(image.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(image.headers().firstValue("Pragma")).isEmpty();
        assertThat(image.headers().allValues("Content-Security-Policy")).containsExactly("default-src 'none'; sandbox");
        assertThat(image.headers().firstValue("Content-Disposition")).hasValue("inline");
    }

    @Test
    void aTruncatedMultipartBodyIsAValidationErrorNotAServerError() throws Exception {
        String cookie = newStore();
        UUID product = createProduct(cookie);

        // Body bị cắt: thiếu phần cuối và dòng kết thúc boundary (client ngắt giữa chừng)
        HttpResponse<String> truncated = send(cookie, product,
                multipart(ProductImageIntegrationTest.png(50 * 1024, 5), false));
        assertThat(truncated.statusCode()).isEqualTo(400);
        JsonNode problem = json.readTree(truncated.body());
        assertThat(problem.get("type").asString()).isEqualTo("/errors/common/validation");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("file");

        // Sản phẩm vẫn chưa có ảnh
        HttpResponse<String> detail = http.send(HttpRequest.newBuilder(uri("/api/v1/catalog/products/" + product))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(json.readTree(detail.body()).get("imageUrl").isNull()).isTrue();
    }

    private HttpResponse<String> upload(String cookie, UUID productId, byte[] content) throws Exception {
        return send(cookie, productId, multipart(content, true));
    }

    /** Body multipart một phần {@code file}; {@code complete = false} thì bỏ phần cuối và dòng kết thúc boundary. */
    private static byte[] multipart(byte[] content, boolean complete) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"anh.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!complete) {
            body.write(content, 0, content.length / 2);
            return body.toByteArray();
        }
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    private HttpResponse<String> send(String cookie, UUID productId, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/catalog/products/" + productId + "/image"))
                .header("Cookie", cookie)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String problemType(HttpResponse<String> response) {
        return json.readTree(response.body()).get("type").asString();
    }

    /** Đăng ký Cửa hàng mới, trả header {@code Cookie} của phiên Chủ cửa hàng. */
    private String newStore() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "storeName", "Cửa hàng thử giới hạn ảnh",
                "loginKey", "gioihan." + UUID.randomUUID().toString().replace("-", "") + "@test.vn",
                "password", "matkhau123",
                "displayName", "Chủ thử"));
        HttpResponse<String> response = postJson("/api/v1/platform/stores", body, null);
        assertThat(response.statusCode()).isEqualTo(201);
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("access_token="))
                .findFirst().orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private UUID createProduct(String cookie) throws Exception {
        HttpResponse<String> response = postJson("/api/v1/catalog/products",
                json.writeValueAsString(Map.of("name", "Sản phẩm thử ảnh", "salePrice", 10000)), cookie);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode created = json.readTree(response.body());
        return UUID.fromString(created.get("id").asString());
    }

    private HttpResponse<String> postJson(String path, String body, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
