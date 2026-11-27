package vn.nhom17.banhang.inventory.internal;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeFileStore.Reference;

@Component
class PurchaseReceiptImportConfirmCommand implements BackofficeCommandHandler<PurchaseReceiptImportConfirmCommand.Payload> {
    static final String ACTION = "PURCHASE_RECEIPT_IMPORT_CONFIRM";
    record Payload(UUID idempotencyKey, Reference file, String supplierId, String note, String edits, String previewToken) {}
    private final PurchaseReceiptImportService imports;
    private final BackofficeFileStore files;

    PurchaseReceiptImportConfirmCommand(PurchaseReceiptImportService imports, BackofficeFileStore files) {
        this.imports = imports;
        this.files = files;
    }

    @Override public String action() { return ACTION; }
    @Override public Class<Payload> payloadType() { return Payload.class; }
    @Override public BackofficeCommandLane lane() { return BackofficeCommandLane.FILE; }
    @Override public List<Reference> fileReferences(Payload payload) { return List.of(payload.file()); }

    @Override
    public Object fingerprint(Payload payload) {
        return imports.commandFingerprint(payload.file().sha256(), payload.supplierId(), payload.note(), payload.edits());
    }

    @Override
    public BackofficeCommandResult execute(Payload payload) {
        var file = files.read(ACTION, payload.file());
        var input = imports.read(file.bytes(), file.filename(), payload.supplierId(), payload.note(), payload.edits());
        var result = imports.importFile(payload.idempotencyKey(), input, payload.previewToken());
        return new BackofficeCommandResult(result.replayed() ? 200 : 201, result.receipt(),
                result.replayed() ? Map.of("Idempotent-Replay", "true") : Map.of());
    }
}
