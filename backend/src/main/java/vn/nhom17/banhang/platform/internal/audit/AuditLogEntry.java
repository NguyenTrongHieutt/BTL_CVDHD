package vn.nhom17.banhang.platform.internal.audit;

import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Một bản ghi Nhật ký thao tác trả ra ngoài qua API (FR-49, TK-06).
 */
public record AuditLogEntry(
        UUID id,
        String action,
        String actionLabel,
        String entity,
        String entityId,
        UUID actorId,
        String actorName,
        JsonNode before,
        JsonNode after,
        String requestId,
        OffsetDateTime createdAt) {
}
