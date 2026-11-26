package vn.nhom17.banhang.inventory.internal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.platform.IdempotencyService;

@RestController
@RequestMapping("/api/v1/inventory/purchase-receipts")
class PurchaseReceiptController {
    private final PurchaseReceiptService receipts;
    private final IdempotencyService idempotency;

    PurchaseReceiptController(PurchaseReceiptService receipts, IdempotencyService idempotency) {
        this.receipts = receipts;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<Receipt> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
            @RequestBody CreateReceipt request) {
        var input = PurchaseReceiptService.validate(request);
        var result = idempotency.execute(StockDocumentType.PURCHASE_RECEIPT.name(), key, idempotency.hash(input),
                () -> receipts.save(input), receipts::load);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }

    record CreateReceipt(UUID supplierId, OffsetDateTime receivedAt, String note, List<LineRequest> lines) {}
    record LineRequest(UUID variantId, BigDecimal quantity, BigDecimal unitCost) {}
    record Receipt(UUID receiptId, String code, UUID supplierId, OffsetDateTime receivedAt, long totalAmount) {}
}
