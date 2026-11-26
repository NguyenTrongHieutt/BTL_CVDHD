package vn.nhom17.banhang.inventory.internal.stock;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import vn.nhom17.banhang.catalog.VariantCreated;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;

@Component
public class VariantCreatedListener {

    private final JdbcTemplate jdbc;
    private final StockService stockService;

    public VariantCreatedListener(
            JdbcTemplate jdbc,
            StockService stockService) {
        this.jdbc = jdbc;
        this.stockService = stockService;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVariantCreated(VariantCreated event) {

        // Tạo dòng tồn kho cho sản phẩm mới
        jdbc.update("""
            INSERT INTO app.stock (store_id, variant_id)
            VALUES (?, ?)
            ON CONFLICT (store_id, variant_id) DO NOTHING
            """,
            event.storeId(),
            event.variantId()
        );

        // Nếu có tồn đầu kỳ thì gọi hàm apply()
        if (event.openingQuantity() > 0) {
            stockService.apply(
                StockDocumentType.OPENING,
                event.variantId(),
                List.of(new StockService.StockLine(
                    1,
                    event.variantId(),
                    event.openingQuantity(),
                    event.openingUnitCost()
                ))
            );
        }
    }
}