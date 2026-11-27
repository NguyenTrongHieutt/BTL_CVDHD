package vn.nhom17.banhang.inventory.internal;

import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/inventory/purchase-receipts")
class PurchaseReceiptImportController {
    private final PurchaseReceiptImportCatalog catalog;
    private final PurchaseReceiptImportService imports;
    private final BackofficeWriteQueue queue;
    private final BackofficeFileStore files;

    PurchaseReceiptImportController(PurchaseReceiptImportCatalog catalog, PurchaseReceiptImportService imports,
            BackofficeWriteQueue queue, BackofficeFileStore files) {
        this.catalog = catalog;
        this.imports = imports;
        this.queue = queue;
        this.files = files;
    }

    @GetMapping("/import-template")
    ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) {
        var template = catalog.template(format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(template.contentType()))
                .contentLength(template.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(template.content());
    }

    @PostMapping(path = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> preview(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "supplierId", required = false) String supplierId,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "edits", required = false) String edits) {
        if (queue.enabled()) {
            UUID requestKey = BackofficeWriteQueue.parseKey(key);
            if (requestKey == null) requestKey = UUID.randomUUID();
            var reference = files.stage(PurchaseReceiptImportPreviewCommand.ACTION, requestKey, file);
            return queue.submit(PurchaseReceiptImportPreviewCommand.ACTION, requestKey,
                    new PurchaseReceiptImportPreviewCommand.Payload(reference, supplierId, note, edits)).response();
        }
        return ResponseEntity.ok(imports.preview(imports.read(file, supplierId, note, edits)));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> importFile(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "X-Import-Preview", required = false) String preview,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "supplierId", required = false) String supplierId,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "edits", required = false) String edits) {
        if (queue.enabled()) {
            UUID requestKey = PurchaseReceiptImportService.parseKey(key);
            if (requestKey == null) {
                throw ProblemException.unprocessable("/errors/common/idempotency-key-missing", "Thiếu Idempotency-Key",
                        "Yêu cầu phải có header Idempotency-Key là một UUID hợp lệ.");
            }
            var reference = files.stage(PurchaseReceiptImportConfirmCommand.ACTION, requestKey, file);
            return queue.submit(PurchaseReceiptImportConfirmCommand.ACTION, requestKey,
                    new PurchaseReceiptImportConfirmCommand.Payload(requestKey, reference, supplierId, note, edits,
                            preview)).response();
        }
        var input = imports.read(file, supplierId, note, edits);
        var imported = imports.importFile(PurchaseReceiptImportService.parseKey(key), input, preview);
        if (imported.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(imported.receipt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(imported.receipt());
    }
}
