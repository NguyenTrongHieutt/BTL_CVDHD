package vn.nhom17.banhang.platform;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.ImportObjectUploads.Kind;
import vn.nhom17.banhang.platform.ImportObjectUploads.VerifiedUpload;

/** Store of raw and checked rows. The module that owns the import still owns all business decisions. */
@Service
public class ImportJobs {
    private static final int BATCH_SIZE = 250;
    private static final long RAW_BUDGET = 16L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CurrentContext context;
    private final Clock clock;
    private final ImportObjectUploads uploads;

    public ImportJobs(JdbcTemplate jdbc, JsonMapper json, CurrentContext context, Clock clock,
            ImportObjectUploads uploads) {
        this.jdbc = jdbc;
        this.json = json;
        this.context = context;
        this.clock = clock;
        this.uploads = uploads;
    }

    public record Job(UUID jobId, Kind kind, UUID supplierId, String note, int revision, String state,
                      String token, JsonNode summary, String edits, JsonNode result, UUID operationId,
                      UUID saveOperationId, Instant expiresAt) {}
    public record View(UUID jobId, Kind kind, UUID supplierId, String note, int revision, String state,
                       JsonNode summary, JsonNode result, String statusUrl, String saveStatusUrl,
                       Instant expiresAt) {}
    public record Error(int row, String column, String message) {}
    public record Page(List<JsonNode> items, List<JsonNode> errors, long total, int page, int size) {}
    private record Row(int number, String json) {}
    private record Checked(int number, String row, String errors) {}
    private record PageRow(JsonNode item, JsonNode errors) {}

    @Transactional
    public VerifiedUpload prepare(UUID id, Kind kind, String fingerprint, UUID supplierId, String note) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(21072126)", (rs, index) -> true);
        var upload = uploads.claimPreview(id, kind, fingerprint);
        if (!jobs(id, kind, false).isEmpty()) return upload;
        int tenantCount = jdbc.queryForObject("""
                SELECT count(*)::integer FROM app.import_job
                WHERE store_id=? AND expires_at>now() AND state IN ('QUEUED','READY')
                """, Integer.class, context.storeId());
        int total = jdbc.queryForObject("SELECT app.active_import_jobs()", Integer.class);
        if (tenantCount >= 2 || total >= 16) {
            throw new ProblemException(HttpStatus.TOO_MANY_REQUESTS, "/errors/platform/import-upload-quota",
                    "Đang có quá nhiều lần nhập", "Hoàn tất lần nhập đang mở rồi thử lại.");
        }
        jdbc.update("""
                INSERT INTO app.import_job (store_id,id,actor_id,kind,supplier_id,note,state)
                VALUES (?,?,?,?,?,?,'QUEUED') ON CONFLICT (store_id,id) DO NOTHING
                """, context.storeId(), id, context.userId(), kind.name(), supplierId, note);
        return upload;
    }

    @Transactional(readOnly = true)
    public View view(UUID id, Kind kind) {
        var job = find(id, kind, false);
        return new View(id, kind, job.supplierId(), job.note(), job.revision(), job.state(), job.summary(), job.result(),
                statusUrl(job.operationId()), statusUrl(job.saveOperationId()), job.expiresAt());
    }

    @Transactional
    public void setOperation(UUID id, Kind kind, UUID operation, boolean confirm) {
        find(id, kind, true);
        String column = confirm ? "save_operation_id" : "operation_id";
        jdbc.update("UPDATE app.import_job SET " + column + "=? WHERE store_id=? AND id=?",
                operation, context.storeId(), id);
    }

    public Job find(UUID id, Kind kind, boolean lock) {
        var entry = jobs(id, kind, lock);
        if (entry.isEmpty()) throw missing();
        return entry.getFirst();
    }

    private List<Job> jobs(UUID id, Kind kind, boolean lock) {
        if (!context.isOwner()) throw missing();
        return jdbc.query("""
                SELECT * FROM app.import_job WHERE store_id=? AND actor_id=? AND id=? AND kind=?
                """ + (lock ? " FOR UPDATE" : ""), this::map, context.storeId(), context.userId(), id, kind.name());
    }

    public void checkRevision(Job job, int expected) {
        if (!job.state().equals("READY") || job.revision() != expected || !job.expiresAt().isAfter(clock.instant())) {
            throw ProblemException.conflict("/errors/platform/import-job-stale", "Bản xem trước đã thay đổi",
                    "Mở lại bản xem trước còn hiệu lực rồi thử lại.");
        }
    }

    /** A caller already holding the worker transaction feeds parsed rows here. */
    public class Rows implements AutoCloseable {
        private final UUID id;
        private final List<Row> buffer = new ArrayList<>(BATCH_SIZE);
        private long bytes;

        private Rows(UUID id) { this.id = id; }

        public void add(int number, Object value) {
            String encoded = json.writeValueAsString(value);
            bytes += encoded.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > RAW_BUDGET) {
                throw new ProblemException(HttpStatus.CONTENT_TOO_LARGE, "/errors/common/payload-too-large",
                        "Dữ liệu nhiều hơn giới hạn xử lý", "Giảm số cột hoặc chia thành nhiều file nhỏ hơn.");
            }
            buffer.add(new Row(number, encoded));
            if (buffer.size() == BATCH_SIZE) flush();
        }

        private void flush() {
            if (buffer.isEmpty()) return;
            jdbc.batchUpdate("""
                    INSERT INTO app.import_job_row (store_id,job_id,row_number,raw) VALUES (?,?,?,?::jsonb)
                    ON CONFLICT (store_id,job_id,row_number) DO UPDATE SET raw=excluded.raw
                    """, buffer, BATCH_SIZE, (statement, row) -> {
                statement.setObject(1, context.storeId());
                statement.setObject(2, id);
                statement.setInt(3, row.number());
                statement.setString(4, row.json());
            });
            buffer.clear();
        }

        @Override public void close() { flush(); }
    }

    public Rows buffer(UUID id) { return new Rows(id); }

    public List<JsonNode> raw(UUID id) {
        return jdbc.query("SELECT raw::text FROM app.import_job_row WHERE store_id=? AND job_id=? ORDER BY row_number",
                (rs, index) -> json.readTree(rs.getString(1)), context.storeId(), id);
    }

    public void ready(Job job, List<?> rows, List<Error> errors, String token, Object summary, String edits) {
        Map<Integer, List<Error>> grouped = new LinkedHashMap<>();
        for (var error : errors) grouped.computeIfAbsent(error.row(), key -> new ArrayList<>()).add(error);
        List<Checked> checked = new ArrayList<>(BATCH_SIZE);
        for (var source : rows) {
            JsonNode row = json.valueToTree(source);
            int number = row.get("row").asInt();
            checked.add(new Checked(number, json.writeValueAsString(row),
                    json.writeValueAsString(grouped.getOrDefault(number, List.of()))));
            if (checked.size() == BATCH_SIZE) writeChecked(job.jobId(), checked);
        }
        writeChecked(job.jobId(), checked);
        jdbc.update("""
                UPDATE app.import_job SET state='READY',revision=revision+1,summary=?::jsonb,preview_token=?,
                    edits=?::jsonb,save_operation_id=NULL,expires_at=now()+interval '24 hours'
                WHERE store_id=? AND id=?
                """, json.writeValueAsString(summary), token, edits, context.storeId(), job.jobId());
    }

    private void writeChecked(UUID id, List<Checked> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                UPDATE app.import_job_row SET preview=?::jsonb,errors=?::jsonb
                WHERE store_id=? AND job_id=? AND row_number=?
                """, rows, BATCH_SIZE, (statement, row) -> {
            statement.setString(1, row.row());
            statement.setString(2, row.errors());
            statement.setObject(3, context.storeId());
            statement.setObject(4, id);
            statement.setInt(5, row.number());
        });
        rows.clear();
    }

    @Transactional(readOnly = true)
    public Page page(UUID id, Kind kind, int page, int size, boolean errorsOnly) {
        var job = find(id, kind, false);
        if (page < 0 || size < 1 || size > 100) throw ProblemException.unprocessable(
                "/errors/common/validation", "Phân trang không hợp lệ", "Chọn trang từ 0 và từ 1 đến 100 dòng.");
        checkRevision(job, job.revision());
        String where = " FROM app.import_job_row WHERE store_id=? AND job_id=? AND preview IS NOT NULL"
                + (errorsOnly ? " AND jsonb_array_length(errors)>0" : "");
        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, context.storeId(), id);
        var rows = jdbc.query("SELECT preview::text,errors::text" + where + " ORDER BY row_number OFFSET ? LIMIT ?",
                (rs, index) -> new PageRow(json.readTree(rs.getString(1)), json.readTree(rs.getString(2))),
                context.storeId(), id, (long) page * size, size);
        List<JsonNode> errors = new ArrayList<>();
        for (var row : rows) row.errors().forEach(errors::add);
        return new Page(rows.stream().map(PageRow::item).toList(), errors, total, page, size);
    }

    public void terminal(Job job, Object data, boolean success) {
        jdbc.update("""
                UPDATE app.import_job SET state=?,result=?::jsonb,expires_at=now()+interval '24 hours'
                WHERE store_id=? AND id=?
                """, success ? "SAVED" : "FAILED", json.writeValueAsString(data), context.storeId(), job.jobId());
    }

    @Scheduled(fixedDelay = 60000)
    void prune() {
        jdbc.queryForObject("SELECT app.prune_import_job_rows()", Integer.class);
    }

    private Job map(ResultSet rs, int ignored) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
                rs.getObject("supplier_id", UUID.class), rs.getString("note"), rs.getInt("revision"),
                rs.getString("state"), rs.getString("preview_token"), tree(rs.getString("summary")),
                rs.getString("edits"), tree(rs.getString("result")), rs.getObject("operation_id", UUID.class),
                rs.getObject("save_operation_id", UUID.class), rs.getTimestamp("expires_at").toInstant());
    }

    private static String statusUrl(UUID id) { return id == null ? null : BackofficeWriteQueue.STATUS_PATH + id; }
    private JsonNode tree(String raw) { return raw == null ? null : json.readTree(raw); }
    private static ProblemException missing() {
        return ProblemException.notFound("/errors/common/not-found", "Không tìm thấy", "Không tìm thấy lần nhập.");
    }
}
