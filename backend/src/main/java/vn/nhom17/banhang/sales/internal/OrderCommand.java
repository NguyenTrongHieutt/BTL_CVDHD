package vn.nhom17.banhang.sales.internal;

import java.util.Objects;
import java.util.UUID;
import vn.nhom17.banhang.platform.Role;

/** Hợp đồng sales.order-commands.v1; chỉ producer nội bộ được ghi topic này. Không lưu JWT hay tên khách. */
record OrderCommand(UUID operationId, UUID storeId, UUID actorId, Role actorRole,
                    OrderController.CreateOrder request) {
    OrderCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(request, "request");
    }
}
