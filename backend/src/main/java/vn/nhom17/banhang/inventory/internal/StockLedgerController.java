package vn.nhom17.banhang.inventory.internal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.internal.stock.StockLedgerDocumentQuery;
import vn.nhom17.banhang.inventory.internal.stock.StockLedgerQuery;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ReplicaRead;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockLedgerController {
    private final StockLedgerQuery ledger;
    private final StockLedgerDocumentQuery documents;

    public StockLedgerController(StockLedgerQuery ledger, StockLedgerDocumentQuery documents) {
        this.ledger = ledger;
        this.documents = documents;
    }

    @ReplicaRead
    @GetMapping("/ledger")
    public PageResponse<StockLedgerQuery.Entry> ledger(
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) StockDocumentType documentType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ledger.find(variantId, documentType, actorId, fromDate, toDate, page, size);
    }

    @GetMapping("/ledger/documents/{type}/{id}")
    public StockLedgerDocumentQuery.Document document(@PathVariable StockDocumentType type, @PathVariable UUID id) {
        return documents.find(type, id);
    }
}
