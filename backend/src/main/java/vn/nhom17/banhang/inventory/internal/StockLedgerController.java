package vn.nhom17.banhang.inventory.internal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.internal.stock.StockLedgerQuery;
import vn.nhom17.banhang.platform.PageResponse;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockLedgerController {
    private final StockLedgerQuery ledger;

    public StockLedgerController(StockLedgerQuery ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/ledger")
    public PageResponse<StockLedgerQuery.Entry> ledger(
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) StockDocumentType documentType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ledger.find(variantId, documentType, fromDate, toDate, page, size);
    }
}
