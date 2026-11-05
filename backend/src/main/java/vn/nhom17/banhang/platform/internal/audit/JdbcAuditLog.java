package vn.nhom17.banhang.platform.internal.audit;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.AuditLog;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.internal.web.RequestIdFilter;

@Component
class JdbcAuditLog implements AuditLog {

    static final int MAX_BYTES = 8 * 1024;

    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final JsonMapper json;

    JdbcAuditLog(JdbcTemplate jdbc, CurrentContext context, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.json = json;
    }

    /** MANDATORY: phải nằm trong transaction của chính thao tác được ghi. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String entityId, Map<String, ?> before, Map<String, ?> after) {
        jdbc.update("""
                insert into app.audit_log (store_id, actor_id, action, entity, entity_id, before, after, request_id)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                context.storeId(), context.userIdIfPresent().orElse(null), action.code(), action.entity(), entityId,
                toJson(before), toJson(after), MDC.get(RequestIdFilter.MDC_KEY));
    }

    private String toJson(Map<String, ?> value) {
        if (value == null) {
            return null;
        }
        String s = json.writeValueAsString(value);
        if (s.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Nhật ký thao tác vượt 8 KB; hãy lọc bớt trường");
        }
        return s;
    }
}
