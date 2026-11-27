package vn.nhom17.banhang.platform.internal.write;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

@Component
class BackofficeWriteRequests {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final JsonMapper json;

    BackofficeWriteRequests(JdbcTemplate jdbc, CurrentContext context, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.json = json;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Status(UUID operationId, String status, Object result, Object problem, Integer responseStatus,
                  Object responseHeaders) {}

    @Transactional(readOnly = true)
    Status status(UUID operation) {
        Status result = find(operation);
        // Chưa được worker commit vẫn là PENDING; không ghi dấu vết tiếp nhận tại API trước ACK Kafka.
        return result == null ? new Status(operation, "PENDING", null, null, null, null) : result;
    }

    Status find(UUID operation) {
        List<Status> rows = jdbc.query("""
                SELECT status, result::text, problem::text, response_status, response_headers::text, pruned_at
                FROM app.backoffice_write_request WHERE store_id = ? AND actor_id = ? AND operation_id = ?
                """, (rs, index) -> rs.getObject(6) == null
                        ? new Status(operation, rs.getString(1), tree(rs.getString(2)), tree(rs.getString(3)),
                                rs.getInt(4), tree(rs.getString(5)))
                        : new Status(operation, "FAILED", null, Map.of("type", "/errors/platform/write-result-expired",
                                "title", "Kết quả đã hết thời hạn lưu", "status", 410,
                                "detail", "Thao tác đã được xử lý. Tải lại dữ liệu để xem trạng thái hiện tại; gửi lại mã cũ không thực hiện lại."),
                                410, Map.of()), context.storeId(), context.userId(), operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void lock(BackofficeCommand command) {
        UUID lock = KafkaBackofficeWriteQueue.operationId(command.storeId(), command.actorId(), command.action(),
                command.idempotencyKey(), "lock");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", (rs, index) -> true, lock.getMostSignificantBits());
    }

    boolean reused(BackofficeCommand command) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.backoffice_write_request
                    WHERE store_id = ? AND actor_id = ? AND action = ? AND idempotency_key = ? AND request_hash <> ?)
                """, Boolean.class, context.storeId(), context.userId(), command.action(), command.idempotencyKey(),
                command.requestHash()));
    }

    void succeed(BackofficeCommand command, BackofficeCommandResult result) {
        insert(command, "SUCCEEDED", result.responseStatus(), result.result(), null, result.responseHeaders());
    }

    void fail(BackofficeCommand command, ProblemException failure) {
        Map<String, Object> problem = new LinkedHashMap<>(failure.properties());
        problem.put("type", failure.type());
        problem.put("title", failure.title());
        problem.put("status", failure.status().value());
        problem.put("detail", failure.getMessage());
        insert(command, "FAILED", failure.status().value(), null, problem, Map.of());
    }

    private void insert(BackofficeCommand command, String status, int responseStatus, Object result,
                        Object problem, Map<String, String> headers) {
        jdbc.update("""
                INSERT INTO app.backoffice_write_request
                    (store_id, actor_id, operation_id, action, idempotency_key, request_hash, status,
                     response_status, result, problem, response_headers)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """, context.storeId(), context.userId(), command.operationId(), command.action(), command.idempotencyKey(),
                command.requestHash(), status, responseStatus, result == null ? null : json.writeValueAsString(result),
                problem == null ? null : json.writeValueAsString(problem), json.writeValueAsString(headers));
    }

    private Object tree(String text) {
        return text == null || "null".equals(text) ? null : json.readTree(text);
    }
}
