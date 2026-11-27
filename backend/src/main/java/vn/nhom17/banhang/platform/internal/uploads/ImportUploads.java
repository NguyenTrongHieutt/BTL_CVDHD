package vn.nhom17.banhang.platform.internal.uploads;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.Part;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ImportObjectUploads;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.TenantScope;

@Component
class ImportUploads implements ImportObjectUploads {
    private static final Logger LOG = LoggerFactory.getLogger(ImportUploads.class);
    private static final long MIB = 1024L * 1024;
    private static final int PART_SIZE = 8 * (int) MIB;
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final Duration URL_TTL = Duration.ofMinutes(10);
    private static final String TABLE = "app.import_upload";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CurrentContext context;
    private final Clock clock;
    private final ImportObjectStorage storage;
    private final TransactionTemplate tx;
    private final long maxBytes;
    private final long totalBytes;

    ImportUploads(JdbcTemplate jdbc, JsonMapper json, CurrentContext context, Clock clock, ImportObjectStorage storage,
                  org.springframework.transaction.PlatformTransactionManager transactions,
                  @Value("${app.import-uploads.max-file-bytes:134217728}") long maxBytes,
                  @Value("${app.import-uploads.max-reserved-bytes:1073741824}") long totalBytes) {
        if (maxBytes < 1 || maxBytes > 128 * MIB || totalBytes < maxBytes) {
            throw new IllegalArgumentException("Invalid import upload capacity");
        }
        this.jdbc = jdbc;
        this.json = json;
        this.context = context;
        this.clock = clock;
        this.storage = storage;
        this.tx = new TransactionTemplate(transactions);
        this.maxBytes = maxBytes;
        this.totalBytes = totalBytes;
    }

    record Capabilities(boolean enabled, long maxFileBytes, int partSizeBytes, int maxParallelParts) {}
    record Create(Kind kind, String fileName, long sizeBytes) {}
    record View(UUID uploadId, Kind kind, String fileName, long sizeBytes, int partSizeBytes, int partCount,
                Instant expiresAt, String status) {}
    record Digest(int partNumber, String sha256) {}
    record Uploaded(int partNumber, long sizeBytes, String etag) {}
    record Parts(List<Uploaded> uploaded, List<Integer> missing) {}
    record Url(int partNumber, String url, Map<String, String> headers, Instant expiresAt) {}
    record Candidate(UUID storeId, UUID actorId, UUID id) {}

    private record Session(UUID id, UUID actor, Kind kind, String name, String key, long size, int partBytes,
                           int count, Instant expiry, String status, String remote, String manifest, String receipts,
                           String etag, String version, String fingerprint) {
        View view() {
            return new View(id, kind, name, size, partBytes, count, expiry, status);
        }
        long length(int number) {
            return Math.min(partBytes, size - (number - 1L) * partBytes);
        }
        List<Integer> expected() { return IntStream.rangeClosed(1, count).boxed().toList(); }
    }

    @Override public boolean enabled() { return storage.enabled(); }

    Capabilities capabilities() { return new Capabilities(enabled(), maxBytes, PART_SIZE, 2); }

    View create(UUID requestKey, Create request) {
        requireEnabled();
        if (requestKey == null) throw ProblemException.unprocessable("/errors/common/idempotency-key-missing",
                "Thiếu Idempotency-Key", "Cần mã UUID để tạo phiên tải.");
        if (request == null || request.kind() == null) throw invalid("Chọn loại nhập dữ liệu.");
        String filename = validName(request.fileName());
        if (request.sizeBytes() <= 0) throw invalid("File chưa có dữ liệu.");
        if (request.sizeBytes() > maxBytes) throw tooLarge();
        UUID id = tx.execute(status -> {
            // Khóa ngắn theo môi trường, ngoài lượt gọi kho. Cả hai hạn mức được kiểm tra nguyên tử.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(21072026)", (rs, index) -> true);
            var retry = jdbc.query("SELECT * FROM " + TABLE + " WHERE store_id=? AND actor_id=? AND request_key=?",
                    this::map, context.storeId(), context.userId(), requestKey);
            if (!retry.isEmpty()) {
                var old = retry.getFirst();
                if (old.kind != request.kind() || !old.name.equals(filename) || old.size != request.sizeBytes()) {
                    throw conflict("Mã yêu cầu đã dùng cho file khác.");
                }
                return old.id;
            }
            long used = jdbc.queryForObject("SELECT app.import_upload_reserved_bytes()", Long.class);
            var reserved = jdbc.queryForMap("""
                    SELECT count(*) AS sessions, coalesce(sum(size_bytes),0)::bigint AS bytes FROM app.import_upload
                    WHERE store_id=? AND status NOT IN ('CANCELLED','RELEASED')
                    """, context.storeId());
            if (request.sizeBytes() > totalBytes - used || ((Number) reserved.get("sessions")).longValue() >= 2
                    || request.sizeBytes() > 256 * MIB - ((Number) reserved.get("bytes")).longValue()) {
                throw new ProblemException(HttpStatus.TOO_MANY_REQUESTS, "/errors/platform/import-upload-quota",
                        "Hết hạn mức tải file", "Hoàn tất hoặc hủy một lần tải trước khi mở phiên mới.");
            }
            UUID upload = UUID.randomUUID();
            int count = (int) Math.ceilDiv(request.sizeBytes(), PART_SIZE);
            jdbc.update("""
                    INSERT INTO app.import_upload
                        (store_id,id,actor_id,request_key,kind,file_name,object_key,size_bytes,part_bytes,part_count,status,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'CREATING',?)
                    """, context.storeId(), upload, context.userId(), requestKey, request.kind().name(), filename,
                    "imports/" + context.storeId() + "/" + upload, request.sizeBytes(), PART_SIZE, count,
                    Timestamp.from(clock.instant().plus(SESSION_TTL)));
            return upload;
        });
        return tx.execute(status -> {
            var session = get(id, true);
            if (!session.status.equals("CREATING")) return session.view();
            active(session);
            var remote = remote(() -> storage.initiate(session.key, session.id.toString()));
            jdbc.update("UPDATE " + TABLE + " SET remote_id=?,status='OPEN',updated_at=now() WHERE store_id=? AND id=?",
                    remote, context.storeId(), id);
            return get(id, false).view();
        });
    }

    @Transactional(readOnly = true)
    List<View> list(Kind kind) {
        if (!enabled()) return List.of();
        return jdbc.query("""
                SELECT * FROM app.import_upload WHERE store_id=? AND actor_id=? AND kind=? AND expires_at>now()
                    AND status IN ('OPEN','READY','RELEASING','RELEASED')
                    ORDER BY (status='OPEN') DESC,updated_at DESC LIMIT 12
                """, this::map, context.storeId(), context.userId(), kind.name()).stream().map(Session::view).toList();
    }

    @Transactional(readOnly = true)
    View view(UUID id) { requireEnabled(); return get(id, false).view(); }

    @Transactional
    View register(UUID id, List<Digest> manifest) {
        var session = open(id, true);
        if (manifest == null || manifest.size() != session.count) throw invalid("Số phần không khớp với phiên tải.");
        var supplied = new LinkedHashMap<Integer, String>();
        for (var item : manifest) {
            if (item == null || !session.expected().contains(item.partNumber()) || !validDigest(item.sha256())
                    || supplied.putIfAbsent(item.partNumber(), item.sha256()) != null) {
                throw invalid("Mã kiểm tra của các phần không hợp lệ.");
            }
        }
        if (session.manifest != null) {
            if (!supplied.equals(values(session.manifest))) throw conflict("Chọn lại đúng file gốc để tiếp tục tải.");
            return session.view();
        }
        jdbc.update("UPDATE " + TABLE + " SET manifest=?::jsonb,updated_at=now() WHERE store_id=? AND id=?",
                json.writeValueAsString(supplied), context.storeId(), id);
        return session.view();
    }

    @Transactional(readOnly = true)
    Parts parts(UUID id) {
        var session = open(id, false);
        return inspect(session, remote(() -> storage.parts(session.key, session.remote)));
    }

    @Transactional(readOnly = true)
    List<Url> urls(UUID id, List<Integer> numbers) {
        var session = open(id, false);
        var checksums = values(session.manifest);
        if (numbers == null || numbers.isEmpty() || numbers.size() > session.count
                || numbers.stream().anyMatch(number -> number == null || !checksums.containsKey(number))
                || numbers.stream().distinct().count() != numbers.size()) {
            throw invalid("Chọn các phần hợp lệ và đăng ký mã kiểm tra trước khi lấy link.");
        }
        Instant now = clock.instant();
        Duration duration = Duration.between(now, session.expiry);
        if (duration.compareTo(URL_TTL) > 0) duration = URL_TTL;
        Duration validFor = duration;
        if (validFor.toSeconds() < 1) throw expired();
        return numbers.stream().map(number -> {
            var part = remote(() -> storage.sign(session.key, session.remote, number, session.length(number),
                    checksums.get(number), validFor));
            return new Url(number, part.url(), part.headers(), now.plus(validFor));
        }).toList();
    }

    @Transactional
    void acknowledge(UUID id, int number, String etag) {
        var session = open(id, true);
        if (!values(session.manifest).containsKey(number) || etag == null || etag.length() > 160
                || !etag.matches("\"?[A-Za-z0-9-]+\"?")) throw invalid("Xác nhận phần tải lên không hợp lệ.");
        jdbc.update("""
                UPDATE app.import_upload SET receipts=receipts || jsonb_build_object(cast(? AS text),cast(? AS text)),
                    updated_at=now() WHERE store_id=? AND id=?
                """, Integer.toString(number), etag, context.storeId(), id);
    }

    @Transactional(noRollbackFor = ProblemException.class)
    View complete(UUID id) {
        requireEnabled();
        var session = get(id, true);
        if (List.of("READY", "RELEASING", "RELEASED").contains(session.status)) return session.view();
        verifyOpen(session);
        Map<Integer, String> checksums = values(session.manifest);
        if (checksums.size() != session.count) throw invalid("Cần xác nhận danh sách phần trước khi ghép.");
        var head = remote(() -> storage.head(session.key));
        if (head == null) {
            List<Part> landed = remote(() -> storage.parts(session.key, session.remote));
            if (landed.stream().mapToLong(Part::size).sum() > session.size) {
                jdbc.update("UPDATE " + TABLE + " SET status='ABORTING',updated_at=now() WHERE store_id=? AND id=?",
                        context.storeId(), session.id);
                throw tooLarge();
            }
            var checked = inspect(session, landed);
            if (!checked.missing().isEmpty() || landed.size() != session.count) {
                throw new ProblemException(HttpStatus.CONFLICT, "/errors/platform/import-upload-parts",
                        "Chưa đủ phần để ghép", "Tải lại các phần chưa được xác nhận.",
                        Map.of("missing", checked.missing()));
            }
            List<CompletedPart> parts = checked.uploaded().stream().map(part -> CompletedPart.builder()
                    .partNumber(part.partNumber()).eTag(part.etag()).checksumSHA256(checksums.get(part.partNumber()))
                    .build()).toList();
            remote(() -> {
                storage.complete(session.key, session.remote, session.size, parts);
                return Boolean.TRUE;
            });
            head = remote(() -> storage.head(session.key));
        }
        verifyObject(session, checksums, head);
        jdbc.update("""
                UPDATE app.import_upload SET status='READY',object_etag=?,version_id=?,updated_at=now()
                WHERE store_id=? AND id=?
                """, head.eTag(), head.versionId(), context.storeId(), id);
        return get(id, false).view();
    }

    private static void verifyObject(Session session, Map<Integer, String> checksums, HeadObjectResponse head) {
        if (head == null) throw unavailable();
        if (head.contentLength() != session.size
                || !session.id.toString().equals(head.metadata().get("import-upload-id"))
                || !aggregate(session, checksums).equals(head.checksumSHA256())) {
            throw conflict("Kho trả về file không khớp với các phần đã xác thực.");
        }
    }

    private Parts inspect(Session session, List<Part> landed) {
        var checksums = values(session.manifest);
        var receipts = values(session.receipts);
        Map<Integer, Part> byNumber = new LinkedHashMap<>();
        for (var part : landed) byNumber.put(part.partNumber(), part);
        var confirmed = new ArrayList<Uploaded>();
        var missing = new ArrayList<Integer>();
        for (int number : session.expected()) {
            var part = byNumber.get(number);
            var etag = receipts.get(number);
            if (part != null && etag != null && part.size() == session.length(number)
                    && checksums.containsKey(number) && checksums.get(number).equals(part.checksumSHA256())
                    && canonicalEtag(etag).equals(canonicalEtag(part.eTag()))) {
                confirmed.add(new Uploaded(number, part.size(), etag));
            } else missing.add(number);
        }
        return new Parts(confirmed, missing);
    }

    @Override
    @Transactional
    public VerifiedUpload claimPreview(UUID id, Kind kind, String fingerprint) {
        requireEnabled();
        var session = get(id, true);
        if (session.kind != kind || !List.of("READY", "RELEASING", "RELEASED").contains(session.status)) {
            throw conflict("Cần hoàn thành tải file trước khi xem trước.");
        }
        if (session.fingerprint == null) {
            active(session);
            jdbc.update("""
                    UPDATE app.import_upload SET preview_fingerprint=?,expires_at=now()+interval '7 days',updated_at=now()
                    WHERE store_id=? AND id=?
                    """, fingerprint, context.storeId(), id);
        } else if (!session.fingerprint.equals(fingerprint)) {
            throw conflict("Lần tải này đã được dùng cho một yêu cầu xem trước khác.");
        }
        return new VerifiedUpload(id, kind, session.name, session.size, fingerprint);
    }

    @Override
    public InputStream open(VerifiedUpload upload) {
        var s = checked(upload);
        if (!s.status.equals("READY")) throw expired();
        return remote(() -> storage.open(s.key, s.etag, s.version));
    }

    @Override
    public void release(VerifiedUpload upload) {
        var session = checked(upload);
        if (session.status.equals("READY")) {
            jdbc.update("UPDATE " + TABLE + " SET status='RELEASING',expires_at=now()+interval '24 hours',updated_at=now() "
                            + "WHERE store_id=? AND id=?", context.storeId(), upload.uploadId());
        }
    }

    private Session checked(VerifiedUpload upload) {
        requireEnabled();
        var s = get(upload.uploadId(), true);
        if (s.kind != upload.kind() || s.size != upload.sizeBytes() || !Objects.equals(s.fingerprint, upload.fingerprint())
                || !s.name.equals(upload.fileName())) throw notFound();
        return s;
    }

    @Transactional
    void cancel(UUID id) {
        requireEnabled();
        var session = get(id, true);
        if (session.status.equals("CANCELLED") || session.status.equals("ABORTING")) return;
        if (session.fingerprint != null || session.status.equals("RELEASED") || session.status.equals("RELEASING")) {
            throw conflict("File đã được gửi đi kiểm tra.");
        }
        jdbc.update("UPDATE " + TABLE + " SET status='ABORTING',updated_at=now() WHERE store_id=? AND id=?",
                context.storeId(), id);
    }

    @Scheduled(fixedDelayString = "${app.import-uploads.cleanup-interval-ms:30000}")
    void clean() {
        if (!enabled()) return;
        var candidates = jdbc.query("SELECT * FROM app.import_upload_cleanup_candidates()",
                (rs, index) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)));
        for (var candidate : candidates) {
            try {
                TenantScope.runAs(candidate.storeId(), candidate.actorId(), Role.OWNER, () ->
                        tx.execute(status -> {
                            var s = get(candidate.id(), true);
                            if (s.status.equals("CANCELLED") || s.status.equals("RELEASED")) return null;
                            boolean released = s.status.equals("RELEASING");
                            if (released || s.status.equals("ABORTING") || !s.expiry.isAfter(clock.instant())) {
                                storage.discard(s.key, s.remote, s.version);
                                jdbc.update("UPDATE " + TABLE + " SET status=?,updated_at=now() WHERE store_id=? AND id=?",
                                        released ? "RELEASED" : "CANCELLED", context.storeId(), s.id);
                            }
                            return null;
                        }));
            } catch (RuntimeException retryLater) {
                LOG.warn("Chưa dọn được phiên tải {}. Sẽ thử lại.", candidate.id());
            }
        }
    }

    private Session open(UUID id, boolean lock) {
        requireEnabled();
        var s = get(id, lock);
        verifyOpen(s);
        return s;
    }

    private void verifyOpen(Session s) {
        active(s);
        if (!s.status.equals("OPEN")) throw conflict("Phiên không còn nhận phần tải lên.");
    }

    private void active(Session s) {
        if (!s.expiry.isAfter(clock.instant()) || s.status.equals("CANCELLED") || s.status.equals("ABORTING")) {
            throw expired();
        }
    }

    private Session get(UUID id, boolean lock) {
        var result = jdbc.query("SELECT * FROM " + TABLE + " WHERE store_id=? AND actor_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""), this::map, context.storeId(), context.userId(), id);
        if (result.isEmpty()) throw notFound();
        return result.getFirst();
    }

    private Session map(ResultSet row, int ignored) throws SQLException {
        return new Session(row.getObject("id", UUID.class), row.getObject("actor_id", UUID.class),
                Kind.valueOf(row.getString("kind")), row.getString("file_name"), row.getString("object_key"),
                row.getLong("size_bytes"), row.getInt("part_bytes"), row.getInt("part_count"),
                row.getTimestamp("expires_at").toInstant(), row.getString("status"), row.getString("remote_id"),
                row.getString("manifest"), row.getString("receipts"), row.getString("object_etag"),
                row.getString("version_id"), row.getString("preview_fingerprint"));
    }

    private Map<Integer, String> values(String encoded) {
        if (encoded == null) return Map.of();
        var result = new LinkedHashMap<Integer, String>();
        var nodes = json.readTree(encoded).properties();
        for (var item : nodes) result.put(Integer.parseInt(item.getKey()), item.getValue().asString());
        return result;
    }

    private static String aggregate(Session session, Map<Integer, String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int n : session.expected()) digest.update(Base64.getDecoder().decode(parts.get(n)));
            return Base64.getEncoder().encodeToString(digest.digest()) + "-" + session.count;
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static boolean validDigest(String value) {
        try {
            return value != null && value.length() == 44 && Base64.getDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String validName(String value) {
        if (value == null || value.length() > 180 || value.contains("/") || value.contains("\\")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Tên file không hợp lệ.");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".csv") || lower.endsWith(".xlsx"))) throw invalid("Chọn file CSV hoặc XLSX.");
        return value;
    }

    private static String canonicalEtag(String value) { return value == null ? "" : value.replace("\"", ""); }

    private void requireEnabled() {
        if (!enabled()) throw notFound();
    }

    private <T> T remote(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (SdkException | IllegalStateException failure) {
            LOG.warn("Không thực hiện được yêu cầu kho import: {}", failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    static ProblemException invalid(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/platform/import-upload-invalid",
                "Phiên tải file không hợp lệ", detail);
    }
    static ProblemException notFound() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy", "Không tìm thấy phiên tải.");
    }
    static ProblemException conflict(String detail) {
        return ProblemException.conflict("/errors/platform/import-upload-conflict", "Không thể tiếp tục tải", detail);
    }
    private static ProblemException expired() {
        return new ProblemException(HttpStatus.GONE, "/errors/platform/import-upload-expired",
                "Phiên tải đã hết hạn", "Chọn lại file và bắt đầu lần tải mới.");
    }
    private static ProblemException tooLarge() {
        return new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large",
                "File vượt giới hạn dung lượng", "Giảm dung lượng hoặc chia dữ liệu thành các file nhỏ hơn.");
    }
    private static ProblemException unavailable() {
        return new ProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/errors/platform/import-upload-unavailable",
                "Kho lưu file tạm thời gián đoạn", "Giữ phiên tải và thử lại.");
    }
}
