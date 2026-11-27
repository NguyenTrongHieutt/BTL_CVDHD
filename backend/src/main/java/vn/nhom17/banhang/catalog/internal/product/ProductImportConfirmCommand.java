package vn.nhom17.banhang.catalog.internal.product;

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
class ProductImportConfirmCommand implements BackofficeCommandHandler<ProductImportConfirmCommand.Payload> {
    static final String ACTION = "PRODUCT_IMPORT_CONFIRM";
    record Payload(UUID idempotencyKey, Reference file, String previewToken) {}
    private final ProductImportService imports;
    private final BackofficeFileStore files;

    ProductImportConfirmCommand(ProductImportService imports, BackofficeFileStore files) {
        this.imports = imports;
        this.files = files;
    }

    @Override public String action() { return ACTION; }
    @Override public Class<Payload> payloadType() { return Payload.class; }
    @Override public BackofficeCommandLane lane() { return BackofficeCommandLane.FILE; }
    @Override public Object fingerprint(Payload payload) { return payload.file().sha256(); }
    @Override public List<Reference> fileReferences(Payload payload) { return List.of(payload.file()); }

    @Override
    public BackofficeCommandResult execute(Payload payload) {
        var file = files.read(ACTION, payload.file());
        var result = imports.importFile(payload.idempotencyKey(), imports.read(file.bytes(), file.filename()),
                payload.previewToken());
        return new BackofficeCommandResult(200, result.result(),
                result.replayed() ? Map.of("Idempotent-Replay", "true") : Map.of());
    }
}
