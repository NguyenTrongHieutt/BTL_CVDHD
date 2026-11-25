package vn.nhom17.banhang.sales.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import vn.nhom17.banhang.platform.TimeZones;

final class OrderCancellationPolicy {
    private OrderCancellationPolicy() {}

    static boolean permits(boolean owner, UUID actorId, UUID sellerId, OffsetDateTime createdAt, Instant now) {
        return owner || (actorId.equals(sellerId)
                && TimeZones.dateOf(createdAt.toInstant()).equals(TimeZones.dateOf(now)));
    }
}
