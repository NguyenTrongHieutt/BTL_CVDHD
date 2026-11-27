package vn.nhom17.banhang.inventory.internal;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeFileStore.Reference;

@Component
class PurchaseReceiptImportPreviewCommand implements BackofficeCommandHandler<PurchaseReceiptImportPreviewCommand.Payload> {
    static final String ACTION = "PURCHASE_RECEIPT_IMPORT_PREVIEW";
    record Payload(Reference file, String supplierId, String note, String edits) {}
    private final PurchaseReceiptImportService imports;
    private final BackofficeFileStore files;

    PurchaseReceiptImportPreviewCommand(PurchaseReceiptImportService imports, BackofficeFileStore files) {
        this.imports = imports;
        this.files = files;
    }

    @Override public String action() { return ACTION; }
    @Override public Class<Payload> payloadType() { return Payload.class; }
    @Override public BackofficeCommandLane lane() { return BackofficeCommandLane.FILE; }
    @Override public List<Reference> fileReferences(Payload payload) { return List.of(payload.file()); }

    @Override
    public BackofficeCommandResult execute(Payload payload) {
        var file = files.read(ACTION, payload.file());
        var input = imports.read(file.bytes(), file.filename(), payload.supplierId(), payload.note(), payload.edits());
        var result = imports.preview(input);
        return BackofficeCommandResult.ok(result);
    }
}
