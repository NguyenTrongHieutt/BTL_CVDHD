package vn.nhom17.banhang.inventory.internal.stock;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;

@Service
public class StockThresholdService {

    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final Clock clock;

    public StockThresholdService(JdbcTemplate jdbc, CurrentContext context, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.clock = clock;
    }

    @Transactional
    public void update(UUID variantId, int minQuantity) {
        int updated = jdbc.update(
            """
            UPDATE app.stock
            SET min_quantity = ?, updated_at = ?
            WHERE store_id = ? AND variant_id = ?
            """,
            minQuantity,
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            context.storeId(),
            variantId
        );
        if (updated == 0) {
            throw ProblemException.notFound(
                "/errors/common/not-found",
                "Không tìm thấy",
                "Không tìm thấy tồn kho của biến thể."
            );
        }
    }
}
