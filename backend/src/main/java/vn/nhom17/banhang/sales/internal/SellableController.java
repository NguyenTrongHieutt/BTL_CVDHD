package vn.nhom17.banhang.sales.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.inventory.StockQuery;
import vn.nhom17.banhang.inventory.StockQuery.StockView;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;

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
        @RequestParam(required = false) List<String> ids,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) Long minPrice,
        @RequestParam(required = false) Long maxPrice,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        PageResponse<SellableVariant> result;
        if (ids == null) {
            result = variantLookup.searchForSale(q, categoryId, minPrice, maxPrice, page, size);
        } else {
            if ((q != null && !q.isBlank()) || categoryId != null || minPrice != null || maxPrice != null) {
                throw invalidIds("Chỉ kết hợp danh sách ID sản phẩm với page và size, không kèm bộ lọc tìm kiếm.");
            }
            result = variantLookup.getForSaleByProductIds(productIds(ids), page, size);
        }

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

    private static List<UUID> productIds(List<String> values) {
        // An explicitly empty list must not fall back to an unfiltered catalog search.
        if (values.isEmpty() || (values.size() == 1 && values.getFirst().isBlank())) {
            return List.of();
        }
        var ids = new LinkedHashSet<UUID>();
        for (String value : values) {
            for (String part : value.split(",", -1)) {
                String text = part.strip();
                try {
                    UUID id = UUID.fromString(text);
                    if (!id.toString().equalsIgnoreCase(text)) {
                        throw new IllegalArgumentException();
                    }
                    ids.add(id);
                } catch (IllegalArgumentException invalid) {
                    throw invalidIds("Mỗi ID sản phẩm phải là UUID hợp lệ.");
                }
            }
        }
        return List.copyOf(ids);
    }

    private static ProblemException invalidIds(String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation", "Dữ liệu không hợp lệ", message,
                Map.of("errors", List.of(Map.of("field", "ids", "message", message))));
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
