package vn.nhom17.banhang.inventory.internal.stock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import vn.nhom17.banhang.catalog.VariantDeactivated;

@Component
class VariantDeactivatedListener {

    private final JdbcTemplate jdbc;

    VariantDeactivatedListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVariantDeactivated(VariantDeactivated event) {
        jdbc.update("""
            UPDATE app.stock
            SET is_active = false
            WHERE store_id = ? AND variant_id = ?
            """, event.storeId(), event.variantId());
    }
}
