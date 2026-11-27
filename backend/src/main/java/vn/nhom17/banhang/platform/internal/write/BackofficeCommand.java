package vn.nhom17.banhang.platform.internal.write;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import vn.nhom17.banhang.platform.Role;

/** Danh tính chỉ lấy từ CurrentContext; không nhận store/actor trong body HTTP. */
record BackofficeCommand(UUID operationId, UUID storeId, UUID actorId, Role actorRole, UUID idempotencyKey,
                         String action, String requestHash, JsonNode payload) {}
