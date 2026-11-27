package vn.nhom17.banhang.catalog.internal.product;

import java.util.List;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeFileStore.Reference;

@Component
class ProductImportPreviewCommand implements BackofficeCommandHandler<Reference> {
    static final String ACTION = "PRODUCT_IMPORT_PREVIEW";
    private final ProductImportService imports;
    private final BackofficeFileStore files;

    ProductImportPreviewCommand(ProductImportService imports, BackofficeFileStore files) {
        this.imports = imports;
        this.files = files;
    }

    @Override public String action() { return ACTION; }
    @Override public Class<Reference> payloadType() { return Reference.class; }
    @Override public BackofficeCommandLane lane() { return BackofficeCommandLane.FILE; }
    @Override public List<Reference> fileReferences(Reference reference) { return List.of(reference); }

    @Override
    public BackofficeCommandResult execute(Reference reference) {
        var file = files.read(ACTION, reference);
        var result = imports.preview(imports.read(file.bytes(), file.filename()));
        return BackofficeCommandResult.ok(result);
    }
}
