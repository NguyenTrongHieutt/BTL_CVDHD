package vn.nhom17.banhang.catalog.internal.product;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.nhom17.banhang.catalog.internal.product.ProductImportService.ImportResult;

/**
 * Nhập Sản phẩm từ XLSX/CSV (FR-6, SP-06), chỉ Chủ cửa hàng (cả tải file mẫu; quyền khai báo ở {@code SecurityConfig}
 * của platform, AD-16). Xem {@link ProductImportService}.
 */
@RestController
@RequestMapping(ProductController.PATH)
class ProductImportController {

    /** Phần multipart chứa file XLSX hoặc CSV. */
    static final String FILE_PART = "file";
    static final String PREVIEW_HEADER = "X-Import-Preview";

    private final ProductImportService imports;

    ProductImportController(ProductImportService imports) {
        this.imports = imports;
    }

    /** File mẫu {@code .xlsx} ({@link ProductImportTemplate}), tải về với tên {@value ProductImportTemplate#FILENAME}. */
    @GetMapping("/import-template")
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) {
        boolean csv = "csv".equalsIgnoreCase(format);
        if (!csv && !"xlsx".equalsIgnoreCase(format)) {
            throw ProductImportReader.invalid("Chọn định dạng file mẫu .xlsx hoặc .csv.");
        }
        byte[] content = csv ? ProductImportTemplate.buildCsv() : ProductImportTemplate.build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(csv ? "text/csv;charset=UTF-8" : ProductImportTemplate.CONTENT_TYPE))
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(csv ? ProductImportTemplate.CSV_FILENAME
                                : ProductImportTemplate.FILENAME).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }

    /** Chỉ đọc và kiểm tra; không tạo Danh mục, Sản phẩm, tồn kho hay chứng từ idempotency. */
    @PostMapping(path = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ProductImportService.PreviewResult preview(@RequestParam(name = FILE_PART, required = false) MultipartFile file) {
        return imports.preview(imports.read(file));
    }

    /**
     * Nhập: {@code multipart/form-data}, file ở phần {@value #FILE_PART}, header {@code Idempotency-Key}. Luôn
     * {@code 200} với kết quả (kể cả khi không dòng nào hợp lệ); gửi lại cùng khóa, cùng file: {@code 200} +
     * {@code Idempotent-Replay: true} với đúng kết quả lần đầu.
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportResult> importProducts(@RequestHeader(name = ProductController.IDEMPOTENCY_KEY_HEADER,
                                                        required = false) String idempotencyKey,
                                                @RequestHeader(name = PREVIEW_HEADER, required = false) String preview,
                                                @RequestParam(name = FILE_PART, required = false) MultipartFile file) {
        ProductImportService.ImportFile read = imports.read(file);
        ProductImportService.Imported result = imports.importFile(ProductController.parseKey(idempotencyKey), read,
                preview);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (result.replayed()) {
            ok.header(ProductController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return ok.body(result.result());
    }
}
