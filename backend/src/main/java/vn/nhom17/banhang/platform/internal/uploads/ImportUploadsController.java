package vn.nhom17.banhang.platform.internal.uploads;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.ImportObjectUploads.Kind;

@RestController
@RequestMapping("/api/v1/platform/import-uploads")
class ImportUploadsController {
    private final ImportUploads uploads;

    ImportUploadsController(ImportUploads uploads) { this.uploads = uploads; }

    record Manifest(List<ImportUploads.Digest> parts) {}
    record PartNumbers(List<Integer> partNumbers) {}
    record PartUrls(List<ImportUploads.Url> parts) {}
    record PartReceipt(String etag) {}

    @GetMapping("/capabilities")
    ImportUploads.Capabilities capabilities() { return uploads.capabilities(); }

    @PostMapping
    ImportUploads.View create(@RequestBody ImportUploads.Create body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return uploads.create(BackofficeWriteQueue.parseKey(key), body);
    }

    @GetMapping
    List<ImportUploads.View> list(@RequestParam Kind kind) { return uploads.list(kind); }

    @GetMapping("/{id}")
    ImportUploads.View session(@PathVariable UUID id) { return uploads.view(id); }

    @PutMapping("/{id}/manifest")
    ImportUploads.View manifest(@PathVariable UUID id, @RequestBody Manifest body) {
        return uploads.register(id, body.parts());
    }

    @GetMapping("/{id}/parts")
    ImportUploads.Parts parts(@PathVariable UUID id) { return uploads.parts(id); }

    @PostMapping("/{id}/parts/urls")
    PartUrls urls(@PathVariable UUID id, @RequestBody PartNumbers body) {
        return new PartUrls(uploads.urls(id, body.partNumbers()));
    }

    @PutMapping("/{id}/parts/{number}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(@PathVariable UUID id, @PathVariable int number, @RequestBody PartReceipt body) {
        uploads.acknowledge(id, number, body.etag());
    }

    @PostMapping("/{id}/complete")
    ImportUploads.View complete(@PathVariable UUID id) { return uploads.complete(id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id) { uploads.cancel(id); }
}
