package vn.nhom17.banhang.inventory.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;
import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.inventory.internal.stock.JdbcStockQuery;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.VariantLookup;

import vn.nhom17.banhang.inventory.StockQuery.StockView;
import vn.nhom17.banhang.inventory.internal.stock.StockThresholdService;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {

    private final JdbcStockQuery stockQuery;
    private final CurrentContext context;
    private final VariantLookup variantLookup;
    private final StockThresholdService stockThresholdService;

    public StockController(
        JdbcStockQuery stockQuery,
        CurrentContext context,
        VariantLookup variantLookup,
        StockThresholdService stockThresholdService
    ) {
        this.stockQuery = stockQuery;
        this.context = context;
        this.variantLookup = variantLookup;
        this.stockThresholdService = stockThresholdService;
    }

    @GetMapping("/stock")
    @Transactional(readOnly = true)
    public PageResponse<StockItem> stock(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) Boolean belowMin,
        @RequestParam(defaultValue = "false") boolean includeInactive,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Integer size
    ) {
        Map<UUID, SellableVariant> variants = variantLookup.findForInventory(q, categoryId, includeInactive)
            .stream().collect(Collectors.toMap(SellableVariant::variantId, Function.identity()));
        var activeIds = includeInactive
            ? variantLookup.getForSale(variants.keySet()).stream().map(SellableVariant::variantId).toList()
            : variants.keySet();
        var result = stockQuery.search(variants.keySet(), activeIds, belowMin, includeInactive,
            Math.max(page, 0), PageResponse.clampSize(size));
        boolean owner = context.isOwner();
        List<StockItem> items = result.items().stream().map(stock -> {
            SellableVariant variant = variants.get(stock.variantId());
            return new StockItem(stock.variantId(), variant.productName(), variant.variantName(),
                variant.sku(), stock.quantity(), stock.minQuantity(), stock.active(),
                stock.active() && stock.belowMin(), owner ? stock.avgUnitCost() : null);
        }).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.size());
    }

    public record StockItem(
        UUID variantId,
        String productName,
        String variantName,
        String sku,
        int quantity,
        int minQuantity,
        boolean isActive,
        boolean belowMin,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long avgUnitCost
    ) {}

    @GetMapping("/low-stock")
    public List<LowStockResponse> lowStock() {
        List<StockView> stocks = stockQuery.lowStock();
        if (stocks.isEmpty()) {
            return List.of();
        }

        Map<UUID, StockView> stockByVariantId = stocks.stream()
            .collect(Collectors.toMap(StockView::variantId, Function.identity()));

        return variantLookup.getForSale(stockByVariantId.keySet()).stream()
            .map(variant -> {
                StockView stock = stockByVariantId.get(variant.variantId());
                return new LowStockResponse(
                    variant.variantId(),
                    variant.productName(),
                    variant.variantName(),
                    stock.quantity(),
                    stock.minQuantity()
                );
            })
            .toList();
    }

    @PutMapping("/stock/{variantId}/min-quantity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMinQuantity(
        @PathVariable UUID variantId,
        @RequestBody UpdateMinQuantityRequest request
    ) {
        BigDecimal value = request.minQuantity();
        if (value == null || value.signum() < 0) {
            throw invalidMinQuantity();
        }

        int minQuantity;
        try {
            minQuantity = value.intValueExact();
        } catch (ArithmeticException e) {
            throw invalidMinQuantity();
        }

        stockThresholdService.update(variantId, minQuantity);
    }

    private static ProblemException invalidMinQuantity() {
        String message = "Ngưỡng tồn tối thiểu phải là số nguyên từ 0 đến 2147483647.";
        return new ProblemException(
            HttpStatus.BAD_REQUEST,
            "/errors/common/validation",
            "Dữ liệu không hợp lệ",
            message,
            Map.of("errors", List.of(Map.of(
                "field", "minQuantity",
                "message", message
            )))
        );
    }

    public record LowStockResponse(
        UUID variantId,
        String productName,
        String variantName,
        int quantity,
        int minQuantity
    ) {}

    public record UpdateMinQuantityRequest(BigDecimal minQuantity) {}
}