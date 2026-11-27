package vn.nhom17.banhang.inventory.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog.Edit;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ImportJobs;
import vn.nhom17.banhang.platform.ImportObjectUploads;
import vn.nhom17.banhang.platform.ImportObjectUploads.Kind;
import vn.nhom17.banhang.platform.ImportObjectUploads.VerifiedUpload;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/inventory/purchase-receipts/import-jobs")
class PurchaseReceiptLargeImport {
    private static final Kind KIND = Kind.PURCHASE_RECEIPT;
    private final BackofficeWriteQueue queue;
    private final ImportObjectUploads uploads;
    private final ImportJobs jobs;
    private final IdempotencyService idempotency;

    PurchaseReceiptLargeImport(BackofficeWriteQueue queue, ImportObjectUploads uploads, ImportJobs jobs,
            IdempotencyService idempotency) {
        this.queue = queue;
        this.uploads = uploads;
        this.jobs = jobs;
        this.idempotency = idempotency;
    }

    record Start(UUID uploadId, UUID supplierId, String note) {}
    record Revision(int revision) {}
    record Recheck(int revision, List<Edit> edits) {}

    @PostMapping
    ResponseEntity<?> start(@RequestBody Start request) {
        requireQueue();
        if (request.supplierId() == null) throw ProblemException.unprocessable("/errors/common/validation",
                "Thiếu nhà cung cấp", "Chọn nhà cung cấp để xem trước phiếu nhập.");
        String note = request.note() == null ? "" : request.note().strip();
        if (note.length() > 500) throw ProblemException.unprocessable("/errors/common/validation",
                "Ghi chú quá dài", "Ghi chú tối đa 500 ký tự.");
        String hash = idempotency.hash(List.of(KIND.name(), request.supplierId(), note));
        var file = jobs.prepare(request.uploadId(), KIND, hash, request.supplierId(), note.isBlank() ? null : note);
        return submit(new Command.Payload(file.uploadId(), "preview", 0, file, List.of()));
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
        return submit(new Command.Payload(id, "confirm", revision.revision(), null, List.of()));
    }

    @PatchMapping("/{id}/rows")
    ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody Recheck request) {
        requireQueue();
        jobs.view(id, KIND);
        var edits = request.edits() == null ? List.<Edit>of() : request.edits();
        if (edits.size() > 20 || edits.stream().anyMatch(edit -> edit == null || edit.values() == null
                || edit.values().size() > 30 || edit.values().values().stream()
                        .anyMatch(value -> value == null || value.length() > 1000))) {
            throw ProblemException.unprocessable("/errors/common/validation", "Thay đổi không hợp lệ",
                    "Sửa tối đa 20 dòng trong mỗi lần kiểm tra lại.");
        }
        return submit(new Command.Payload(id, "recheck", request.revision(), null, edits));
    }

    private ResponseEntity<?> submit(Command.Payload payload) {
        var key = UUID.nameUUIDFromBytes((payload.id() + ":" + payload.phase() + ":" + payload.revision()
                + ":" + idempotency.hash(payload.edits())).getBytes(StandardCharsets.UTF_8));
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
        static final String ACTION = "PURCHASE_RECEIPT_IMPORT_JOB";
        record Payload(UUID id, String phase, int revision, VerifiedUpload file, List<Edit> edits) {}
        record Summary(int totalRows, int existingRows, int newProducts, int newVariants, Long totalAmount,
                       int errorCount) {}
        private final ImportObjectUploads uploads;
        private final PurchaseReceiptImportService service;
        private final PurchaseReceiptImportCatalog catalog;
        private final ImportJobs jobs;
        private final JsonMapper json;

        Command(ImportObjectUploads uploads, PurchaseReceiptImportService service, PurchaseReceiptImportCatalog catalog,
                ImportJobs jobs, JsonMapper json) {
            this.uploads = uploads;
            this.service = service;
            this.catalog = catalog;
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
                    catalog.readLarge(stream, payload.file().fileName(), payload.file().sizeBytes(), sink::add);
                } catch (IOException retry) {
                    throw new UncheckedIOException(retry);
                }
                preview(job, "[]");
                uploads.release(payload.file());
            } else if (payload.phase().equals("recheck")) {
                jobs.checkRevision(job, payload.revision());
                preview(job, mergeEdits(job.edits(), payload.edits()));
            } else {
                if (job.state().equals("SAVED")) return BackofficeCommandResult.ok(job.result());
                jobs.checkRevision(job, payload.revision());
                var receipt = service.importFile(job.jobId(), input(job, job.edits()), job.token()).receipt();
                jobs.terminal(job, receipt, true);
                return BackofficeCommandResult.created(receipt);
            }
            return BackofficeCommandResult.ok(jobs.view(job.jobId(), KIND));
        }

        private PurchaseReceiptImportService.ImportInput input(ImportJobs.Job job, String edits) {
            return service.restore(job.jobId().toString(), jobs.raw(job.jobId()), job.supplierId(), job.note(), edits);
        }

        private void preview(ImportJobs.Job job, String edits) {
            var preview = service.preview(input(job, edits));
            var errors = preview.errors().stream().map(error ->
                    new ImportJobs.Error(error.row(), error.column(), error.message())).toList();
            jobs.ready(job, preview.rows(), errors, preview.previewToken(), new Summary(preview.rows().size(),
                    preview.existingRows(), preview.newProducts(), preview.newVariants(), preview.totalAmount(),
                    errors.size()), edits);
        }

        private String mergeEdits(String saved, List<Edit> edits) {
            Map<Integer, Map<String, String>> merged = new LinkedHashMap<>();
            var prior = json.readTree(saved);
            for (var encoded : prior) {
                var edit = json.treeToValue(encoded, Edit.class);
                merged.put(edit.row(), new LinkedHashMap<>(edit.values()));
            }
            for (var edit : edits) {
                merged.computeIfAbsent(edit.row(), row -> new LinkedHashMap<>()).putAll(edit.values());
            }
            var combined = new ArrayList<Edit>();
            merged.forEach((row, values) -> combined.add(new Edit(row, values)));
            return json.writeValueAsString(combined);
        }
    }
}
