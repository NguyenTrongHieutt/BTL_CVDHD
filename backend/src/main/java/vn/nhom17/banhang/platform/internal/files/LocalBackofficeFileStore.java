package vn.nhom17.banhang.platform.internal.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

/** Shared volume spool. A process lock and a filesystem lock make capacity checks atomic across publishers. */
@Service
class LocalBackofficeFileStore implements BackofficeFileStore {
    private static final Logger log = LoggerFactory.getLogger(LocalBackofficeFileStore.class);
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private static final int METADATA_LIMIT = 4096;
    private final CurrentContext context;
    private final JsonMapper json;
    private final Clock clock;
    private final Path root;
    private final long maxTotalBytes;
    private final Duration ttl;
    private final boolean enabled;

    LocalBackofficeFileStore(CurrentContext context, JsonMapper json, Clock clock,
            @Value("${app.backoffice.files.directory:./var/import-files}") String directory,
            @Value("${app.backoffice.files.max-total-bytes:268435456}") long maxTotalBytes,
            @Value("${app.backoffice.files.ttl:P7D}") Duration ttl,
            @Value("#{${app.backoffice.queue.publish-enabled:false} or ${app.backoffice.queue.file-consume-enabled:false}}")
                    boolean enabled) {
        if (maxTotalBytes <= METADATA_LIMIT || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("File spool capacity and lifetime must be positive");
        }
        this.context = context;
        this.json = json;
        this.clock = clock;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.maxTotalBytes = maxTotalBytes;
        this.ttl = ttl;
        this.enabled = enabled;
    }

    private record Digest(String sha256, long size) {}
    private record Metadata(Reference file, long expiresAt) {}

    @Override
    public Reference stage(String action, UUID requestKey, MultipartFile upload) {
        if (upload == null) {
            throw validation("Chọn file .xlsx hoặc .csv để nhập.");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw tooLarge();
        }
        Objects.requireNonNull(action);
        Objects.requireNonNull(requestKey);
        // MultipartFile is repeatable. The first streaming pass lets an exact retry succeed even at capacity;
        // a second bounded pass writes only new content and verifies the upload did not change in between.
        Digest digest;
        try (InputStream input = upload.getInputStream()) {
            digest = digest(input, null);
        } catch (IOException unreadable) {
            throw validation("Không đọc được tệp gửi lên. Hãy chọn lại file rồi thử lại.");
        }
        String filename = upload.getOriginalFilename() != null
                && upload.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")
                ? "upload.csv" : "upload.xlsx";
        UUID storeId = context.storeId();
        UUID userId = context.userId();
        String key = key(storeId, userId, action, requestKey, digest.sha256(), filename);
        Reference reference = new Reference(key, storeId, userId, action, requestKey,
                digest.sha256(), digest.size(), filename);
        return locked(() -> {
            cleanupLocked();
            Path data = path(key, ".data");
            Path metadata = path(key, ".json");
            if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
                Metadata existing = metadata(metadata);
                if (!existing.file().equals(reference)) {
                    throw invalid();
                }
                verifyData(reference);
                return reference;
            }
            byte[] manifest = json.writeValueAsBytes(new Metadata(reference, clock.millis() + ttl.toMillis()));
            if (manifest.length > METADATA_LIMIT) {
                throw invalid();
            }
            boolean orphanData = Files.exists(data, LinkOption.NOFOLLOW_LINKS);
            if (orphanData) {
                verifyData(reference);
            }
            long extra = manifest.length + (orphanData ? 0 : digest.size());
            if (extra > maxTotalBytes - directorySize()) {
                throw full();
            }
            Path temporary = root.resolve(".part-" + UUID.randomUUID());
            try {
                if (!orphanData) {
                    try (InputStream input = upload.getInputStream();
                         FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                                 StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        if (!digest.equals(digest(input, output, digest.size()))) {
                            throw invalid();
                        }
                        output.force(true);
                    }
                    moveNew(temporary, data);
                }
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    write(output, manifest, manifest.length);
                    output.force(true);
                }
                moveNew(temporary, metadata);
                return reference;
            } finally {
                Files.deleteIfExists(temporary);
            }
        });
    }

    @Override
    public Content read(String action, Reference reference) {
        validateReference(action, reference);
        return locked(() -> {
            Path manifest = path(reference.key(), ".json");
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                throw expired();
            }
            Metadata metadata = metadata(manifest);
            if (!metadata.file().equals(reference)) {
                throw invalid();
            }
            if (metadata.expiresAt() <= clock.millis()) {
                throw expired();
            }
            byte[] bytes;
            try (InputStream input = dataInput(reference)) {
                bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            }
            if (bytes.length != reference.size()
                    || !reference.sha256().equals(HexFormat.of().formatHex(sha256().digest(bytes)))) {
                throw invalid();
            }
            return new Content(bytes, reference.filename());
        });
    }

    @Override
    public void discardAfterCommit(String action, Reference reference) {
        validateReference(action, reference);
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            // Retaining until TTL is safe for direct/manual service calls without a durable queue result.
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    locked(() -> {
                        Path manifest = path(reference.key(), ".json");
                        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)
                                && metadata(manifest).file().equals(reference)) {
                            Files.deleteIfExists(path(reference.key(), ".data"));
                            Files.deleteIfExists(manifest);
                        }
                        return null;
                    });
                } catch (RuntimeException cleanupFailure) {
                    // Kết quả đã commit. Giữ offset để retry chỉ bước dọn tệp qua nhánh đọc kết quả cuối.
                    log.warn("Không dọn được file nhập đã xử lý: {}", cleanupFailure.toString());
                    throw new IllegalStateException("Không dọn được file nhập đã có kết quả cuối", cleanupFailure);
                }
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.backoffice.files.cleanup-interval-ms:900000}")
    void cleanupExpired() {
        if (!enabled) {
            return;
        }
        try {
            locked(() -> {
                cleanupLocked();
                return null;
            });
        } catch (ProblemException unavailable) {
            log.warn("Không dọn được file nhập hết hạn: {}", unavailable.getMessage());
        }
    }

    private void cleanupLocked() throws IOException {
        long now = clock.millis();
        long cutoff = now - ttl.toMillis();
        List<Path> entries;
        try (var stream = Files.list(root)) {
            entries = stream.toList();
        }
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (name.matches("[0-9a-f]{64}\\.json")) {
                boolean old;
                try {
                    old = metadata(entry).expiresAt() <= now;
                } catch (ProblemException corrupt) {
                    old = Files.getLastModifiedTime(entry).toMillis() <= cutoff;
                }
                if (old) {
                    Files.deleteIfExists(path(name.substring(0, 64), ".data"));
                    Files.deleteIfExists(entry);
                }
            } else if ((name.startsWith(".part-") || name.matches("[0-9a-f]{64}\\.data"))
                    && Files.getLastModifiedTime(entry).toMillis() <= cutoff
                    && (!name.endsWith(".data")
                        || !Files.exists(path(name.substring(0, 64), ".json"), LinkOption.NOFOLLOW_LINKS))) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private long directorySize() throws IOException {
        long size = 0;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!entry.getFileName().toString().equals(".lock")
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    size = Math.addExact(size, Files.size(entry));
                }
            }
        }
        return size;
    }

    private void validateReference(String action, Reference reference) {
        if (reference == null || reference.key() == null || !reference.key().matches("[0-9a-f]{64}")
                || reference.sha256() == null || !reference.sha256().matches("[0-9a-f]{64}")
                || reference.size() < 0 || reference.size() > MAX_FILE_BYTES
                || !context.storeId().equals(reference.storeId()) || !context.userId().equals(reference.userId())
                || !action.equals(reference.action()) || reference.requestKey() == null
                || !("upload.csv".equals(reference.filename()) || "upload.xlsx".equals(reference.filename()))
                || !reference.key().equals(key(reference.storeId(), reference.userId(), reference.action(),
                        reference.requestKey(), reference.sha256(), reference.filename()))) {
            throw invalid();
        }
    }

    private void verifyData(Reference reference) throws IOException {
        try (InputStream input = dataInput(reference)) {
            Digest actual = digest(input, null);
            if (actual.size() != reference.size() || !actual.sha256().equals(reference.sha256())) {
                throw invalid();
            }
        }
    }

    private InputStream dataInput(Reference reference) throws IOException {
        Path data = path(reference.key(), ".data");
        if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS) || Files.size(data) != reference.size()) {
            throw invalid();
        }
        return Files.newInputStream(data, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private Metadata metadata(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > METADATA_LIMIT) {
            throw invalid();
        }
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Metadata parsed = json.readValue(input.readNBytes(METADATA_LIMIT + 1), Metadata.class);
                if (parsed == null || parsed.file() == null || parsed.expiresAt() <= 0) {
                    throw invalid();
                }
                return parsed;
            } catch (RuntimeException malformed) {
                throw invalid();
            }
        }
    }

    private Path path(String key, String extension) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        return root.resolve(key + extension);
    }

    private <T> T locked(IoSupplier<T> work) {
        JVM_LOCK.lock();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw invalid();
            }
            try (FileChannel lock = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = lock.lock()) {
                return work.get();
            }
        } catch (IOException failure) {
            log.warn("Không truy cập được vùng file nhập dùng chung: {}", failure.toString());
            throw new ProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "/errors/platform/import-file-storage-unavailable", "Chưa nhận được file nhập",
                    "Vùng lưu file nhập chưa sẵn sàng. Hãy thử lại sau.");
        } finally {
            JVM_LOCK.unlock();
        }
    }

    private static Digest digest(InputStream input, FileChannel output) throws IOException {
        return digest(input, output, MAX_FILE_BYTES);
    }

    private static Digest digest(InputStream input, FileChannel output, long copyLimit) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            size += read;
            if (size > MAX_FILE_BYTES) {
                throw tooLarge();
            }
            if (size > copyLimit) {
                throw invalid();
            }
            digest.update(buffer, 0, read);
            if (output != null) {
                write(output, buffer, read);
            }
        }
        return new Digest(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static void write(FileChannel output, byte[] bytes, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);
        while (buffer.hasRemaining()) {
            output.write(buffer);
        }
    }

    private static void moveNew(Path temporary, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid();
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unavailable) {
            Files.move(temporary, destination);
        }
    }

    private static String key(UUID store, UUID user, String action, UUID request, String digest, String filename) {
        String scope = store + "\n" + user + "\n" + action + "\n" + request + "\n" + digest + "\n" + filename;
        return HexFormat.of().formatHex(sha256().digest(scope.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ProblemException validation(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ",
                detail, Map.of("errors", List.of(Map.of("field", "file", "message", detail))));
    }

    private static ProblemException tooLarge() {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large",
                "Dữ liệu gửi lên quá lớn", "File nhập tối đa 2 MB. Hãy tách thành nhiều file nhỏ hơn.");
    }

    private static ProblemException invalid() {
        return ProblemException.conflict("/errors/platform/import-file-invalid", "File nhập không còn hợp lệ",
                "Không xác minh được file nhập của yêu cầu này. Hãy chọn lại file và xem trước lại.");
    }

    private static ProblemException expired() {
        return new ProblemException(HttpStatus.GONE, "/errors/platform/import-file-expired", "File nhập đã hết hạn",
                "File nhập đã hết thời gian lưu. Hãy chọn lại file và xem trước lại.");
    }

    private static ProblemException full() {
        return new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/errors/platform/import-files-full",
                "Vùng file nhập đang đầy", "Chưa nhận thêm file nhập. Hãy thử lại sau.");
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
