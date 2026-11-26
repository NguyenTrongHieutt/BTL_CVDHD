package vn.nhom17.banhang.sales.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.inventory.StockQuery;
import vn.nhom17.banhang.inventory.StockQuery.StockView;
import vn.nhom17.banhang.platform.PageResponse;

@RestController
@RequestMapping("/api/v1/sales")
public class SellableController {

    private final VariantLookup variantLookup;
    private final StockQuery stockQuery;

    public SellableController(VariantLookup variantLookup, StockQuery stockQuery) {
        this.variantLookup = variantLookup;
        this.stockQuery = stockQuery;
    }

    @GetMapping("/sellable")
    public PageResponse<SellableItem> search(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) Long minPrice,
        @RequestParam(required = false) Long maxPrice,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var result = variantLookup.searchForSale(
            q, categoryId, minPrice, maxPrice, page, size
        );

        var variantIds = result.items().stream()
            .map(SellableVariant::variantId)
            .toList();

        Map<UUID, StockView> stockById = new HashMap<>();
        for (StockView stock : stockQuery.find(variantIds)) {
            stockById.put(stock.variantId(), stock);
        }

        var items = result.items().stream().map(variant -> {
            StockView stock = stockById.get(variant.variantId());
            int quantity = stock != null && stock.active() ? stock.quantity() : 0;
            boolean belowMin = stock != null && stock.active() && stock.belowMin();

            return new SellableItem(
                variant.variantId(),
                variant.productId(),
                variant.productName(),
                variant.variantName(),
                variant.sku(),
                variant.barcode(),
                variant.unit(),
                variant.salePrice(),
                quantity,
                belowMin
            );
        }).toList();

        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    public record SellableItem(
        UUID variantId,
        UUID productId,
        String productName,
        String variantName,
        String sku,
        String barcode,
        String unit,
        long salePrice,
        int quantity,
        boolean belowMin
    ) {}
}