package vn.nhom17.banhang.platform.internal.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TimeZones;
import vn.nhom17.banhang.platform.UserNameLookup;

/**
 * Tra cứu Nhật ký thao tác của Cửa hàng hiện tại (FR-49, TK-06).
 */
@Repository
public class AuditLogQuery {

    private static final Map<String, String> LABELS = Arrays.stream(AuditAction.values())
            .collect(Collectors.toUnmodifiableMap(AuditAction::code, AuditAction::label));

    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final UserNameLookup users;
    private final JsonMapper json;

    public AuditLogQuery(JdbcTemplate jdbc, CurrentContext context, UserNameLookup users, JsonMapper json) {
        this.jdbc = jdbc;
        this.context = context;
        this.users = users;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AuditLogEntry> find(String action, UUID actorId, LocalDate fromDate, LocalDate toDate,
                                            int page, Integer size) {
        validateRange(fromDate, toDate);
        int pageNo = Math.max(0, page);
        int pageSize = PageResponse.clampSize(size);

        StringBuilder where = new StringBuilder(" FROM app.audit_log WHERE store_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(context.storeId());

        if (action != null) {
            if (!LABELS.containsKey(action)) {
                throw invalid("action", "Loại thao tác không hợp lệ.");
            }
            where.append(" AND action = ?");
            args.add(action);
        }
        if (actorId != null) {
            where.append(" AND actor_id = ?");
            args.add(actorId);
        }
        if (fromDate != null) {
            where.append(" AND created_at >= ?");
            args.add(OffsetDateTime.ofInstant(fromDate.atStartOfDay(TimeZones.VIETNAM).toInstant(), ZoneOffset.UTC));
        }
        if (toDate != null) {
            where.append(" AND created_at < ?");
            args.add(OffsetDateTime.ofInstant(toDate.plusDays(1).atStartOfDay(TimeZones.VIETNAM).toInstant(), ZoneOffset.UTC));
        }

        long totalCount = jdbc.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
        long offset = (long) pageNo * pageSize;
        if (offset >= totalCount) {
            return new PageResponse<>(List.of(), totalCount, pageNo, pageSize);
        }

        args.add(pageSize);
        args.add(offset);
        List<RawRow> rows = jdbc.query("""
                SELECT id, action, entity, entity_id, actor_id, before, after, request_id, created_at
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", (rs, i) -> new RawRow(
                rs.getObject("id", UUID.class),
                rs.getString("action"),
                rs.getString("entity"),
                rs.getString("entity_id"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("before"),
                rs.getString("after"),
                rs.getString("request_id"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), args.toArray());

        Map<UUID, String> actorNames = users.findNames(
                rows.stream().map(RawRow::actorId).filter(Objects::nonNull).distinct().toList());

        List<AuditLogEntry> entries = rows.stream().map(row -> new AuditLogEntry(
                row.id(),
                row.action(),
                LABELS.getOrDefault(row.action(), row.action()),
                row.entity(),
                row.entityId(),
                row.actorId(),
                row.actorId() == null ? null : actorNames.get(row.actorId()),
                row.beforeJson() == null ? null : json.readTree(row.beforeJson()),
                row.afterJson() == null ? null : json.readTree(row.afterJson()),
                row.requestId(),
                row.createdAt()
        )).toList();

        return new PageResponse<>(entries, totalCount, pageNo, pageSize);
    }

    private static void validateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null) {
            return;
        }
        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days < 0 || days >= 366) {
            throw invalid("toDate", "Khoảng ngày phải theo thứ tự và tối đa 366 ngày.");
        }
    }

    private static ProblemException invalid(String field, String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation",
                "Dữ liệu không hợp lệ", message,
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    private record RawRow(
            UUID id,
            String action,
            String entity,
            String entityId,
            UUID actorId,
            String beforeJson,
            String afterJson,
            String requestId,
            OffsetDateTime createdAt) {
    }
}
