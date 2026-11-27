package vn.nhom17.banhang.platform.internal.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeFileStore.Reference;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

class LocalBackofficeFileStoreTest {
    private static final String ACTION = "PRODUCT_IMPORT_PREVIEW";
    private static final UUID STORE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-27T05:00:00Z");
    @TempDir Path directory;
    private final CurrentContext context = mock(CurrentContext.class);

    @Test
    void sameRequestHasStableReferenceAndDifferentBytesNeverReplaceTheFirstFile() throws Exception {
        var store = store(1024 * 1024, NOW);
        UUID key = UUID.randomUUID();
        var first = store.stage(ACTION, key, file("first"));
        var retry = store.stage(ACTION, key, file("first"));
        var changed = store.stage(ACTION, key, file("changed"));

        assertThat(first).isEqualTo(retry);
        assertThat(changed.key()).isNotEqualTo(first.key());
        assertThat(first.key()).matches("[0-9a-f]{64}");
        assertThat(first.filename()).isEqualTo("upload.csv");
        assertThat(store.read(ACTION, first).bytes()).isEqualTo("first".getBytes(StandardCharsets.UTF_8));
        assertThat(store.read(ACTION, changed).bytes()).isEqualTo("changed".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void checksOwnershipActionPathAndDigestBeforeReturningAnyBytes() throws Exception {
        var store = store(1024 * 1024, NOW);
        var reference = store.stage(ACTION, UUID.randomUUID(), file("first"));
        when(context.userId()).thenReturn(UUID.randomUUID());
        assertInvalid(() -> store.read(ACTION, reference));
        when(context.userId()).thenReturn(USER);
        assertInvalid(() -> store.read("PURCHASE_RECEIPT_IMPORT_PREVIEW", reference));
        var unsafe = new Reference("../../outside", reference.storeId(), reference.userId(), reference.action(),
                reference.requestKey(), reference.sha256(), reference.size(), reference.filename());
        assertInvalid(() -> store.read(ACTION, unsafe));
        Files.writeString(directory.resolve(reference.key() + ".data"), "other");
        assertInvalid(() -> store.read(ACTION, reference));
    }

    @Test
    void limitsActualStreamEvenIfMultipartSizeLiesAndDoesNotLeavePartialFiles() throws Exception {
        var store = store(10 * 1024 * 1024, NOW);
        MultipartFile upload = mock(MultipartFile.class);
        when(upload.getOriginalFilename()).thenReturn("large.csv");
        when(upload.getSize()).thenReturn(1L);
        when(upload.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(new byte[2 * 1024 * 1024 + 1]));

        assertThatThrownBy(() -> store.stage(ACTION, UUID.randomUUID(), upload))
                .isInstanceOfSatisfying(ProblemException.class,
                        error -> assertThat(error.status().value()).isEqualTo(413));
        try (var files = Files.list(directory)) {
            assertThat(files.filter(path -> !path.getFileName().toString().equals(".lock"))).isEmpty();
        }
    }

    @Test
    void streamsUploadsWithoutCallingGetBytes() throws Exception {
        var store = store(1024 * 1024, NOW);
        MultipartFile upload = mock(MultipartFile.class);
        when(upload.getOriginalFilename()).thenReturn("..\\..\\source.CSV");
        when(upload.getSize()).thenReturn(3L);
        when(upload.getBytes()).thenThrow(new AssertionError("must stream"));
        when(upload.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var reference = store.stage(ACTION, UUID.randomUUID(), upload);
        assertThat(store.read(ACTION, reference).bytes()).containsExactly(1, 2, 3);
        assertThat(reference.filename()).isEqualTo("upload.csv");
    }

    @Test
    void capacityIsAtomicAcrossPublishersAndDuplicateUploadDoesNotNeedMoreCapacity() throws Exception {
        var first = store(10_000, NOW);
        var second = store(10_000, NOW);
        byte[] content = new byte[6_000];
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<java.util.concurrent.Callable<Boolean>>of(
                    () -> tryStage(first, content), () -> tryStage(second, content));
            var outcomes = executor.invokeAll(tasks);
            assertThat(outcomes.get(0).get(5, TimeUnit.SECONDS))
                    .isNotEqualTo(outcomes.get(1).get(5, TimeUnit.SECONDS));
        }
        try (var paths = Files.list(directory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".data")).count()).isOne();
        }
    }

    @Test
    void exactRetryAtCapacityNeedsNoSecondBlobAndChangedStreamCannotExceedReservedCapacity() throws Exception {
        var store = store(10_000, NOW);
        byte[] content = new byte[6_000];
        var upload = new MockMultipartFile("file", "source.csv", "text/csv", content);
        UUID key = UUID.randomUUID();
        var reference = store.stage(ACTION, key, upload);
        assertThat(store.stage(ACTION, key, upload)).isEqualTo(reference);

        MultipartFile changing = mock(MultipartFile.class);
        when(changing.getOriginalFilename()).thenReturn("changed.csv");
        when(changing.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1}),
                new ByteArrayInputStream(new byte[6_000]));
        assertInvalid(() -> store.stage(ACTION, UUID.randomUUID(), changing));
        try (var paths = Files.list(directory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith(".part-"))).isEmpty();
        }
        assertThat(store.read(ACTION, reference).bytes()).isEqualTo(content);
    }

    @Test
    void expiredFilesAreRemovedAndSameBytesRestoreIdenticalReference() throws Exception {
        UUID key = UUID.randomUUID();
        var firstStore = store(1024 * 1024, NOW);
        var first = firstStore.stage(ACTION, key, file("first"));
        var later = store(1024 * 1024, NOW.plus(Duration.ofDays(8)));
        assertThatThrownBy(() -> later.read(ACTION, first))
                .isInstanceOfSatisfying(ProblemException.class,
                        error -> assertThat(error.type()).isEqualTo("/errors/platform/import-file-expired"));
        later.cleanupExpired();
        assertThat(Files.exists(directory.resolve(first.key() + ".data"))).isFalse();
        assertThat(later.stage(ACTION, key, file("first"))).isEqualTo(first);
    }

    @Test
    void completedFileIsKeptUntilCommitAndRollbackDoesNotDiscardTheBytes() throws Exception {
        var store = store(1024 * 1024, NOW);
        var reference = store.stage(ACTION, UUID.randomUUID(), file("first"));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            store.discardAfterCommit(ACTION, reference);
            assertThat(store.read(ACTION, reference).bytes()).isNotEmpty();
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(store.read(ACTION, reference).bytes()).isNotEmpty();
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertThat(Files.exists(directory.resolve(reference.key() + ".data"))).isFalse();
            assertThat(Files.exists(directory.resolve(reference.key() + ".json"))).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void aPublishWithoutAKnownCommittedResultNeverDiscardsTheFile() throws Exception {
        var store = store(1024 * 1024, NOW);
        var reference = store.stage(ACTION, UUID.randomUUID(), file("pending publish"));
        store.discardAfterCommit(ACTION, reference);
        assertThat(store.read(ACTION, reference).bytes()).isNotEmpty();
    }

    @Test
    void cleanupFailureAfterCommitKeepsTheOffsetRetryableAndDoesNotDiscardUnverifiedData() throws Exception {
        var store = store(1024 * 1024, NOW);
        var reference = store.stage(ACTION, UUID.randomUUID(), file("first"));
        var metadata = directory.resolve(reference.key() + ".json");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            store.discardAfterCommit(ACTION, reference);
            Files.writeString(metadata, "corrupt metadata");
            assertThatThrownBy(() -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Files.exists(directory.resolve(reference.key() + ".data"))).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private boolean tryStage(LocalBackofficeFileStore store, byte[] content) {
        try {
            store.stage(ACTION, UUID.randomUUID(), new MockMultipartFile("file", "source.csv", "text/csv", content));
            return true;
        } catch (ProblemException full) {
            assertThat(full.type()).isEqualTo("/errors/platform/import-files-full");
            return false;
        }
    }

    private LocalBackofficeFileStore store(long capacity, Instant now) {
        when(context.storeId()).thenReturn(STORE);
        when(context.userId()).thenReturn(USER);
        return new LocalBackofficeFileStore(context, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC),
                directory.toString(), capacity, Duration.ofDays(7), true);
    }

    private static MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "source.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable read) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(ProblemException.class,
                error -> assertThat(error.type()).isEqualTo("/errors/platform/import-file-invalid"));
    }
}
