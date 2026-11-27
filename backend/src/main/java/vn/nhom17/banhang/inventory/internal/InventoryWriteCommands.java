package vn.nhom17.banhang.inventory.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandResult;

final class InventoryWriteCommands {
    private InventoryWriteCommands() {}
    record MinQuantity(UUID variantId, StockController.UpdateMinQuantityRequest request) {}
    record Receipt(UUID key, PurchaseReceiptController.CreateReceipt request) {}
    record Stocktake(UUID key, StocktakeController.CreateRequest request) {}

    @Component
    static class MinQuantityHandler implements BackofficeCommandHandler<MinQuantity> {
        private final StockController stocks;
        MinQuantityHandler(StockController stocks) { this.stocks = stocks; }
        public String action() { return "STOCK_MIN_QUANTITY_UPDATE"; }
        public Class<MinQuantity> payloadType() { return MinQuantity.class; }
        public BackofficeCommandResult execute(MinQuantity payload) {
            stocks.updateMinQuantityNow(payload.variantId(), payload.request());
            return BackofficeCommandResult.of(204, null);
        }
    }
    @Component
    static class ReceiptHandler implements BackofficeCommandHandler<Receipt> {
        private final PurchaseReceiptController receipts;
        ReceiptHandler(PurchaseReceiptController receipts) { this.receipts = receipts; }
        public String action() { return "PURCHASE_RECEIPT_CREATE"; }
        public Class<Receipt> payloadType() { return Receipt.class; }
        public BackofficeCommandResult execute(Receipt payload) {
            return BackofficeCommandResult.from(receipts.createNow(payload.key(), payload.request()));
        }
    }
    @Component
    static class StocktakeHandler implements BackofficeCommandHandler<Stocktake> {
        private final StocktakeController stocktakes;
        StocktakeHandler(StocktakeController stocktakes) { this.stocktakes = stocktakes; }
        public String action() { return "STOCKTAKE_CREATE"; }
        public Class<Stocktake> payloadType() { return Stocktake.class; }
        public BackofficeCommandResult execute(Stocktake payload) {
            return BackofficeCommandResult.from(stocktakes.createNow(payload.key(), payload.request()));
        }
    }
}
