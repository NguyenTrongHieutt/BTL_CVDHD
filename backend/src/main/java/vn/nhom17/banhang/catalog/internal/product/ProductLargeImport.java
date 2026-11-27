package vn.nhom17.banhang.catalog.internal.product;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.ImportJobs;
import vn.nhom17.banhang.platform.ImportObjectUploads;
import vn.nhom17.banhang.platform.ImportObjectUploads.Kind;
import vn.nhom17.banhang.platform.ImportObjectUploads.VerifiedUpload;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/catalog/products/import-jobs")
class ProductLargeImport {
    private static final Kind KIND = Kind.PRODUCTS;
    private final BackofficeWriteQueue queue;
    private final ImportObjectUploads uploads;
    private final ImportJobs jobs;

    ProductLargeImport(BackofficeWriteQueue queue, ImportObjectUploads uploads, ImportJobs jobs) {
        this.queue = queue;
        this.uploads = uploads;
        this.jobs = jobs;
    }

    record Start(UUID uploadId) {}
    record Revision(int revision) {}

    @PostMapping
    ResponseEntity<?> start(@RequestBody Start request) {
        requireQueue();
        var file = jobs.prepare(request.uploadId(), KIND, KIND.name(), null, null);
        return submit(new Command.Payload(file.uploadId(), "preview", 0, file));
    }

    @GetMapping("/{id}")
    ImportJobs.View status(@PathVariable UUID id) { return jobs.view(id, KIND); }

    @GetMapping("/{id}/rows")
    ImportJobs.Page rows(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @RequestParam(defaultValue = "false") boolean errorsOnly) {
        return jobs.page(id, KIND, page, size, errorsOnly);
    }

    @PostMapping("/{id}/confirm")
    ResponseEntity<?> save(@PathVariable UUID id, @RequestBody Revision revision) {
        requireQueue();
        jobs.view(id, KIND);
        return submit(new Command.Payload(id, "confirm", revision.revision(), null));
    }

    @PostMapping("/{id}/recheck")
    ResponseEntity<?> recheck(@PathVariable UUID id, @RequestBody Revision revision) {
        requireQueue();
        jobs.view(id, KIND);
        return submit(new Command.Payload(id, "recheck", revision.revision(), null));
    }

    private ResponseEntity<?> submit(Command.Payload payload) {
        var key = UUID.nameUUIDFromBytes((payload.id() + ":" + payload.phase() + ":" + payload.revision())
                .getBytes(StandardCharsets.UTF_8));
        if (!payload.phase().equals("recheck")) {
            jobs.setOperation(payload.id(), KIND, queue.operation(Command.ACTION, key, payload),
                    payload.phase().equals("confirm"));
        }
        var accepted = queue.submit(Command.ACTION, key, payload);
        return ResponseEntity.accepted().body(Map.of("jobId", payload.id(), "operationId", accepted.operationId(),
                "statusUrl", accepted.statusUrl(), "state", payload.phase().equals("confirm") ? "SAVING" : "QUEUED"));
    }

    private void requireQueue() {
        if (!uploads.enabled() || !queue.enabled()) throw ProblemException.notFound("/errors/common/not-found",
                "Không tìm thấy", "Nhập file lớn chưa được bật.");
    }

    @Component
    static class Command implements BackofficeCommandHandler<Command.Payload> {
        static final String ACTION = "PRODUCT_IMPORT_JOB";
        record Payload(UUID id, String phase, int revision, VerifiedUpload file) {}
        record Summary(int totalRows, int validProducts, int validRows, int errorCount) {}
        private final ImportObjectUploads uploads;
        private final ProductImportService service;
        private final ImportJobs jobs;
        private final JsonMapper json;

        Command(ImportObjectUploads uploads, ProductImportService service, ImportJobs jobs, JsonMapper json) {
            this.uploads = uploads;
            this.service = service;
            this.jobs = jobs;
            this.json = json;
        }

        @Override public String action() { return ACTION; }
        @Override public Class<Payload> payloadType() { return Payload.class; }
        @Override public BackofficeCommandLane lane() { return BackofficeCommandLane.FILE; }

        @Override
        public BackofficeCommandResult execute(Payload payload) {
            var job = jobs.find(payload.id(), KIND, true);
            if (payload.phase().equals("preview")) {
                if (!job.state().equals("QUEUED")) return BackofficeCommandResult.ok(jobs.view(job.jobId(), KIND));
                try (var stream = uploads.open(payload.file()); var sink = jobs.buffer(job.jobId())) {
                    LargeImportReader.read(stream, payload.file().fileName(), payload.file().sizeBytes(),
                            ProductImportReader.Profile.CATALOG, 50_000, row -> sink.add(row.row(), row));
                } catch (IOException retry) {
                    throw new UncheckedIOException(retry);
                }
                preview(job);
                uploads.release(payload.file());
            } else if (payload.phase().equals("recheck")) {
                jobs.checkRevision(job, payload.revision());
                preview(job);
            } else {
                if (job.state().equals("SAVED")) return BackofficeCommandResult.ok(job.result());
                jobs.checkRevision(job, payload.revision());
                var result = service.importFile(job.jobId(), input(job), job.token()).result();
                var summary = Map.of("imported", result.imported(), "failed", java.util.List.of(),
                        "failedRows", result.failed().stream().map(error -> error.row()).distinct().count(),
                        "createdCategories", result.createdCategories().stream().limit(100).toList());
                jobs.terminal(job, summary, true);
                return BackofficeCommandResult.ok(summary);
            }
            return BackofficeCommandResult.ok(jobs.view(job.jobId(), KIND));
        }

        private ProductImportService.ImportFile input(ImportJobs.Job job) {
            var rows = jobs.raw(job.jobId()).stream()
                    .map(row -> json.treeToValue(row, ProductImportReader.ImportRow.class)).toList();
            return new ProductImportService.ImportFile(job.jobId().toString(), rows);
        }

        private void preview(ImportJobs.Job job) {
            var preview = service.preview(input(job));
            var errors = preview.errors().stream().map(error ->
                    new ImportJobs.Error(error.row(), error.column(), error.message())).toList();
            jobs.ready(job, preview.rows(), errors, preview.previewToken(), new Summary(preview.totalRows(),
                    preview.validProducts(), preview.validRows(), errors.size()), "[]");
        }
    }
}
