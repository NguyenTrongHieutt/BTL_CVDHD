package vn.nhom17.banhang.sales.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderCancellationPolicyTest {
    private final UUID seller = UUID.randomUUID();
    private final OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-23T16:00:00Z");

    @Test
    void staffCanCancelTheirOwnOrderUntilMidnightVietnamTime() {
        assertTrue(OrderCancellationPolicy.permits(false, seller, seller, createdAt,
                Instant.parse("2026-11-23T16:59:59Z")));
        assertFalse(OrderCancellationPolicy.permits(false, seller, seller, createdAt,
                Instant.parse("2026-11-23T17:00:00Z")));
    }

    @Test
    void staffCannotCancelAnotherSellersOrderButOwnerCan() {
        UUID other = UUID.randomUUID();
        assertFalse(OrderCancellationPolicy.permits(false, other, seller, createdAt,
                Instant.parse("2026-11-23T16:59:59Z")));
        assertTrue(OrderCancellationPolicy.permits(true, other, seller, createdAt,
                Instant.parse("2026-11-25T00:00:00Z")));
    }
}
