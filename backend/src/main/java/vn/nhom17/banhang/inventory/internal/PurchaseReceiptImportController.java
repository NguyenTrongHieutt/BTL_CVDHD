package vn.nhom17.banhang.inventory.internal;

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
import vn.nhom17.banhang.inventory.internal.PurchaseReceiptController.Receipt;

@RestController
@RequestMapping("/api/v1/inventory/purchase-receipts")
class PurchaseReceiptImportController {
    private final PurchaseReceiptImportCatalog catalog;
    private final PurchaseReceiptImportService imports;

    PurchaseReceiptImportController(PurchaseReceiptImportCatalog catalog, PurchaseReceiptImportService imports) {
        this.catalog = catalog;
        this.imports = imports;
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
    PurchaseReceiptImportService.PreviewResult preview(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "supplierId", required = false) String supplierId,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "edits", required = false) String edits) {
        return imports.preview(imports.read(file, supplierId, note, edits));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Receipt> importFile(
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestHeader(name = "X-Import-Preview", required = false) String preview,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "supplierId", required = false) String supplierId,
            @RequestParam(name = "note", required = false) String note,
            @RequestParam(name = "edits", required = false) String edits) {
        var input = imports.read(file, supplierId, note, edits);
        var imported = imports.importFile(PurchaseReceiptImportService.parseKey(key), input, preview);
        if (imported.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(imported.receipt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(imported.receipt());
    }
}
