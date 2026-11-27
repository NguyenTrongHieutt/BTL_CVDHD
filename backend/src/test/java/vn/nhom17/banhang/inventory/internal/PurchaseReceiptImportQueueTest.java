package vn.nhom17.banhang.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.nhom17.banhang.catalog.PurchaseReceiptImportCatalog;
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.catalog.SupplierLookup;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class PurchaseReceiptImportQueueTest {
    private final PurchaseReceiptImportCatalog catalog = mock(PurchaseReceiptImportCatalog.class);
    private final PurchaseReceiptImportService imports = mock(PurchaseReceiptImportService.class);
    private final BackofficeFileStore files = mock(BackofficeFileStore.class);
    private final BackofficeWriteQueue queue = mock(BackofficeWriteQueue.class);
    private final MockMultipartFile upload = new MockMultipartFile("file", "receipt.csv", "text/csv", new byte[] {1});

    @Test
    void previewQueuesSupplierNoteAndEditsWithoutOpeningTheFileOrUsingDatabaseServices() {
        when(queue.enabled()).thenReturn(true);
        UUID key = UUID.randomUUID();
        var reference = reference(PurchaseReceiptImportPreviewCommand.ACTION, key);
        when(files.stage(PurchaseReceiptImportPreviewCommand.ACTION, key, upload)).thenReturn(reference);
        var accepted = new BackofficeWriteQueue.Accepted(UUID.randomUUID(), "ACCEPTED", "/status/receipt");
        when(queue.submit(eq(PurchaseReceiptImportPreviewCommand.ACTION), eq(key), any())).thenReturn(accepted);

        var response = new PurchaseReceiptImportController(catalog, imports, queue, files)
                .preview(key.toString(), upload, "supplier", "note", "[]");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(queue).submit(PurchaseReceiptImportPreviewCommand.ACTION, key,
                new PurchaseReceiptImportPreviewCommand.Payload(reference, "supplier", "note", "[]"));
        verifyNoInteractions(imports, catalog);
    }

    @Test
    void workerRetainsOriginalPreviewErrorsAndTotals() {
        var key = UUID.randomUUID();
        var reference = reference(PurchaseReceiptImportPreviewCommand.ACTION, key);
        byte[] bytes = {1};
        when(files.read(PurchaseReceiptImportPreviewCommand.ACTION, reference))
                .thenReturn(new BackofficeFileStore.Content(bytes, "upload.csv"));
        var input = new PurchaseReceiptImportService.ImportInput(reference.sha256(), null, null, "note");
        when(imports.read(bytes, "upload.csv", "supplier", "note", "[]")).thenReturn(input);
        var body = new PurchaseReceiptImportService.PreviewResult("token", List.of(),
                List.of(new PurchaseReceiptImportCatalog.RowError(2, "SKU", "Dòng lỗi")), 0, 0, 0, null);
        when(imports.preview(input)).thenReturn(body);
        var handler = new PurchaseReceiptImportPreviewCommand(imports, files);

        var result = handler.execute(new PurchaseReceiptImportPreviewCommand.Payload(reference, "supplier", "note", "[]"));

        assertThat(handler.lane()).isEqualTo(BackofficeCommandLane.FILE);
        assertThat(result.responseStatus()).isEqualTo(200);
        assertThat(result.result()).isSameAs(body);
    }

    @Test
    void confirmationStagesAndQueuesTheOriginalReviewInputsAndToken() {
        when(queue.enabled()).thenReturn(true);
        UUID key = UUID.randomUUID();
        var reference = reference(PurchaseReceiptImportConfirmCommand.ACTION, key);
        when(files.stage(PurchaseReceiptImportConfirmCommand.ACTION, key, upload)).thenReturn(reference);
        var accepted = new BackofficeWriteQueue.Accepted(UUID.randomUUID(), "ACCEPTED", "/status/receipt");
        when(queue.submit(eq(PurchaseReceiptImportConfirmCommand.ACTION), eq(key), any())).thenReturn(accepted);

        var response = new PurchaseReceiptImportController(catalog, imports, queue, files)
                .importFile(key.toString(), "preview-token", upload, "supplier", "note", "[]");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(queue).submit(PurchaseReceiptImportConfirmCommand.ACTION, key,
                new PurchaseReceiptImportConfirmCommand.Payload(key, reference, "supplier", "note", "[]", "preview-token"));
        verifyNoInteractions(imports, catalog);
    }

    @Test
    void confirmationWorkerPropagatesStalePreviewWithoutChangingTheDomainError() {
        UUID key = UUID.randomUUID();
        var reference = reference(PurchaseReceiptImportConfirmCommand.ACTION, key);
        byte[] bytes = {1};
        when(files.read(PurchaseReceiptImportConfirmCommand.ACTION, reference))
                .thenReturn(new BackofficeFileStore.Content(bytes, "upload.csv"));
        var input = new PurchaseReceiptImportService.ImportInput(reference.sha256(), null, null, "note");
        when(imports.read(bytes, "upload.csv", "supplier", "note", "[]")).thenReturn(input);
        var stale = ProblemException.conflict("/errors/inventory/receipt-import-preview-stale", "Cũ", "Xem trước lại");
        when(imports.importFile(key, input, "preview-token")).thenThrow(stale);
        var handler = new PurchaseReceiptImportConfirmCommand(imports, files);

        assertThatThrownBy(() -> handler.execute(new PurchaseReceiptImportConfirmCommand.Payload(key, reference,
                "supplier", "note", "[]", "preview-token"))).isSameAs(stale);
    }

    @Test
    void receiptFingerprintIgnoresPreviewTokenAndNormalizesSupplierNoteAndEditOrdering() {
        var service = new PurchaseReceiptImportService(catalog, mock(SupplierLookup.class),
                mock(PurchaseReceiptService.class), mock(IdempotencyService.class), mock(CurrentContext.class),
                mock(JdbcTemplate.class), JsonMapper.builder().build());
        var handler = new PurchaseReceiptImportConfirmCommand(service, files);
        UUID key = UUID.randomUUID();
        String supplier = UUID.randomUUID().toString();
        var reference = reference(PurchaseReceiptImportConfirmCommand.ACTION, key);
        var first = new PurchaseReceiptImportConfirmCommand.Payload(key, reference, supplier, " note ",
                "[{\"row\":3,\"values\":{\"quantity\":\"2\"}},{\"row\":2,\"values\":{\"unitCost\":\"100\"}}]", "first-token");
        var retry = new PurchaseReceiptImportConfirmCommand.Payload(key, reference, supplier.toUpperCase(), "note",
                "[ {\"values\":{\"unitCost\":\"100\"},\"row\":2}, {\"row\":3,\"values\":{\"quantity\":\"2\"}} ]", "new-token");
        var changed = new PurchaseReceiptImportConfirmCommand.Payload(key, reference, supplier, "note", "[]", "first-token");

        assertThat(handler.fingerprint(first)).isEqualTo(handler.fingerprint(retry));
        assertThat(handler.fingerprint(first)).isNotEqualTo(handler.fingerprint(changed));
    }

    private static BackofficeFileStore.Reference reference(String action, UUID key) {
        return new BackofficeFileStore.Reference("a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), action, key,
                "b".repeat(64), 1, "upload.csv");
    }
}
