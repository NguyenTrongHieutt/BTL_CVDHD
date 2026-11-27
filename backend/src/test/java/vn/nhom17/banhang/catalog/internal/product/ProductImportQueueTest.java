package vn.nhom17.banhang.catalog.internal.product;

import static org.assertj.core.api.Assertions.assertThat;
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
import vn.nhom17.banhang.platform.BackofficeCommandLane;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;

class ProductImportQueueTest {
    private final ProductImportService imports = mock(ProductImportService.class);
    private final BackofficeFileStore files = mock(BackofficeFileStore.class);
    private final BackofficeWriteQueue queue = mock(BackofficeWriteQueue.class);
    private final MockMultipartFile upload = new MockMultipartFile("file", "products.csv", "text/csv", new byte[] {1});

    @Test
    void previewOnlyStagesFileAndPublishesWhenQueueIsEnabled() {
        when(queue.enabled()).thenReturn(true);
        UUID key = UUID.randomUUID();
        var reference = reference(ProductImportPreviewCommand.ACTION, key);
        when(files.stage(ProductImportPreviewCommand.ACTION, key, upload)).thenReturn(reference);
        var accepted = new BackofficeWriteQueue.Accepted(UUID.randomUUID(), "ACCEPTED", "/status/one");
        when(queue.submit(ProductImportPreviewCommand.ACTION, key, reference)).thenReturn(accepted);

        var response = new ProductImportController(imports, queue, files).preview(key.toString(), upload);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/status/one");
        assertThat(response.getBody()).isEqualTo(accepted);
        verifyNoInteractions(imports);
    }

    @Test
    void confirmationKeepsKeyAndPreviewTokenInTheFileCommand() {
        when(queue.enabled()).thenReturn(true);
        UUID key = UUID.randomUUID();
        var reference = reference(ProductImportConfirmCommand.ACTION, key);
        when(files.stage(ProductImportConfirmCommand.ACTION, key, upload)).thenReturn(reference);
        var accepted = new BackofficeWriteQueue.Accepted(UUID.randomUUID(), "ACCEPTED", "/status/two");
        when(queue.submit(eq(ProductImportConfirmCommand.ACTION), eq(key), any())).thenReturn(accepted);

        var response = new ProductImportController(imports, queue, files)
                .importProducts(key.toString(), "preview-token", upload);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(queue).submit(ProductImportConfirmCommand.ACTION, key,
                new ProductImportConfirmCommand.Payload(key, reference, "preview-token"));
        verifyNoInteractions(imports);
    }

    @Test
    void workerReadsOnlyTheScopedReferenceAndPreservesBusinessReplayResult() {
        var key = UUID.randomUUID();
        var reference = reference(ProductImportConfirmCommand.ACTION, key);
        byte[] bytes = {1};
        when(files.read(ProductImportConfirmCommand.ACTION, reference))
                .thenReturn(new BackofficeFileStore.Content(bytes, "upload.csv"));
        var read = new ProductImportService.ImportFile(reference.sha256(), List.of());
        when(imports.read(bytes, "upload.csv")).thenReturn(read);
        var body = new ProductImportService.ImportResult(1, List.of(), List.of());
        when(imports.importFile(key, read, "preview-token")).thenReturn(new ProductImportService.Imported(body, true));
        var handler = new ProductImportConfirmCommand(imports, files);
        var payload = new ProductImportConfirmCommand.Payload(key, reference, "preview-token");

        var result = handler.execute(payload);

        assertThat(handler.lane()).isEqualTo(BackofficeCommandLane.FILE);
        assertThat(result.responseStatus()).isEqualTo(200);
        assertThat(result.result()).isSameAs(body);
        assertThat(result.responseHeaders()).containsEntry("Idempotent-Replay", "true");
        assertThat(handler.fingerprint(payload)).isEqualTo(handler.fingerprint(
                new ProductImportConfirmCommand.Payload(key, reference, "changed-token")));
    }

    @Test
    void disabledQueueUsesExistingSynchronousPreview() {
        var read = new ProductImportService.ImportFile("hash", List.of());
        when(imports.read(upload)).thenReturn(read);
        var preview = new ProductImportService.PreviewResult(0, 0, 0, List.of(), List.of(), "token");
        when(imports.preview(read)).thenReturn(preview);

        var response = new ProductImportController(imports, queue, files).preview(null, upload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(preview);
        verifyNoInteractions(files);
    }

    private static BackofficeFileStore.Reference reference(String action, UUID key) {
        return new BackofficeFileStore.Reference("a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), action, key,
                "b".repeat(64), 1, "upload.csv");
    }
}
