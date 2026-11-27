package vn.nhom17.banhang.sales.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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
import vn.nhom17.banhang.platform.ReplicaRead;

@RestController
@RequestMapping("/api/v1/sales")
public class SellableController {
    private static final int MAX_PRODUCT_IDS = 50;

    private final VariantLookup variantLookup;
    private final StockQuery stockQuery;

    public SellableController(VariantLookup variantLookup, StockQuery stockQuery) {
        this.variantLookup = variantLookup;
        this.stockQuery = stockQuery;
    }

    @ReplicaRead
    @GetMapping("/sellable")
    @Transactional(readOnly = true)
    public PageResponse<SellableItem> search(
        @RequestParam(required = false) List<String> ids,
        @RequestParam(defaultValue = "false") boolean inStock,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) Long minPrice,
        @RequestParam(required = false) Long maxPrice,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (ids != null) {
            if ((q != null && !q.isBlank()) || categoryId != null || minPrice != null || maxPrice != null) {
                throw invalidIds("Danh sách ID sản phẩm dùng với phân trang và bộ lọc còn hàng.");
            }
            return rankedProducts(productIds(ids), inStock, page, size);
        }
        if (inStock) throw invalidIds("Cần danh sách ID sản phẩm để lọc hàng thay thế.");
        var result = variantLookup.searchForSale(
            q, categoryId, minPrice, maxPrice, page, size
        );
        return withStock(result);
    }

    private PageResponse<SellableItem> rankedProducts(List<UUID> ids, boolean inStock, int page, int size) {
        int pageNo = Math.max(0, page);
        int pageSize = PageResponse.clampSize(size);
        var ranked = variantLookup.findForSaleByProductIds(ids);
        if (inStock) {
            var hydrated = withStock(new PageResponse<>(ranked, ranked.size(), 0, pageSize));
            var usedProducts = new HashSet<UUID>();
            List<SellableItem> available = hydrated.items().stream()
                    .filter(item -> item.quantity() > 0 && usedProducts.add(item.productId())).toList();
            return new PageResponse<>(slice(available, pageNo, pageSize), available.size(), pageNo, pageSize);
        }
        return withStock(new PageResponse<>(slice(ranked, pageNo, pageSize), ranked.size(), pageNo, pageSize));
    }

    private static <T> List<T> slice(List<T> values, int page, int size) {
        int start = (int) Math.min(values.size(), (long) page * size);
        int end = Math.min(values.size(), start + size);
        return values.subList(start, end);
    }

    private static List<UUID> productIds(List<String> values) {
        if (values.isEmpty() || (values.size() == 1 && values.getFirst().isBlank())) return List.of();
        var ids = new LinkedHashSet<UUID>();
        int count = 0;
        for (String value : values) {
            for (String part : value.split(",", -1)) {
                if (++count > MAX_PRODUCT_IDS) throw invalidIds("Chỉ nhận tối đa 50 ID sản phẩm.");
                String text = part.strip();
                try {
                    UUID id = UUID.fromString(text);
                    if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
                    ids.add(id);
                } catch (IllegalArgumentException ex) {
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

    @ReplicaRead
    @GetMapping("/sellable/exact")
    @Transactional(readOnly = true)
    public PageResponse<SellableItem> exact(@RequestParam String code) {
        var variants = variantLookup.findForSaleByExactCode(code);
        return withStock(new PageResponse<>(variants, variants.size(), 0, PageResponse.DEFAULT_SIZE));
    }

    private PageResponse<SellableItem> withStock(PageResponse<SellableVariant> result) {
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
