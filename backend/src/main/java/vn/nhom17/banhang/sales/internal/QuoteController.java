package vn.nhom17.banhang.sales.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.SellableVariant;
import vn.nhom17.banhang.catalog.VariantLookup;
import vn.nhom17.banhang.inventory.StockQuery;
import vn.nhom17.banhang.inventory.StockQuery.StockView;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.StoreSettings;

@RestController
@RequestMapping("/api/v1/sales/orders")
public class QuoteController {

    private static final long MAX_MONEY = 9_007_199_254_740_991L;

    private final VariantLookup variants;
    private final StockQuery stocks;

    private final StoreSettings settings;

    public QuoteController(VariantLookup variants, StockQuery stocks, StoreSettings settings) {
        this.settings = settings;
        this.variants = variants;
        this.stocks = stocks;
    }

    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public QuoteResponse quote(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                               @RequestBody QuoteRequest request) {
        if (request.lines() == null || request.lines().isEmpty()) {
            throw invalid("lines", "Giỏ hàng phải có ít nhất một dòng.");
        }
        boolean transfer = "BANK_TRANSFER".equals(request.paymentMethod());
        if (!transfer && request.paymentMethod() != null && !"CASH".equals(request.paymentMethod())) {
            throw invalid("paymentMethod", "Phương thức thanh toán không hợp lệ.");
        }
        if (transfer && key == null) {
            throw invalid("paymentMethod", "Cần Idempotency-Key để tạo mã chuyển khoản.");
        }
        Long cash = request.cashReceived() == null ? null : money(request.cashReceived(), "cashReceived");

        Map<UUID, Long> quantities = new HashMap<>();
        for (LineRequest line : request.lines()) {
            if (line == null || line.variantId() == null) {
                throw invalid("lines", "Mỗi dòng phải có biến thể.");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw invalid("lines", "Số lượng phải là số nguyên dương.");
            }
            try {
                quantities.merge(line.variantId(), (long) line.quantity().intValueExact(), Long::sum);
            } catch (ArithmeticException exception) {
                throw invalid("lines", "Số lượng phải là số nguyên dương trong phạm vi cho phép.");
            }
        }

        Map<UUID, SellableVariant> byId = new HashMap<>();
        for (SellableVariant variant : variants.getForSale(quantities.keySet())) {
            byId.put(variant.variantId(), variant);
        }
        Map<UUID, StockView> stockById = new HashMap<>();
        for (StockView stock : stocks.find(quantities.keySet())) {
            stockById.put(stock.variantId(), stock);
        }
        List<Warning> warnings = new ArrayList<>();
        List<PricedLine> priced = new ArrayList<>();
        long subtotal = 0;
        long lineDiscountTotal = 0;
        for (LineRequest line : request.lines()) {
            SellableVariant variant = byId.get(line.variantId());
            if (variant == null) {
                throw ProblemException.conflict("/errors/sales/cart-stale", "Giỏ hàng đã thay đổi",
                    "Có sản phẩm không còn bán được. Kiểm tra lại giỏ hàng.");
            }
            int quantity = line.quantity().intValueExact();
            long gross = money(BigDecimal.valueOf(variant.salePrice()).multiply(line.quantity()), "lines");
            long discount = discount(line.discount(), gross, "lines[" + priced.size() + "].discount");
            subtotal = money(BigDecimal.valueOf(subtotal).add(BigDecimal.valueOf(gross)), "lines");
            lineDiscountTotal += discount;
            priced.add(new PricedLine(variant, quantity, discount, gross - discount));
        }
        for (var entry : quantities.entrySet()) {
            StockView stock = stockById.get(entry.getKey());
            int available = stock != null && stock.active() ? stock.quantity() : 0;
            if (entry.getValue() > available) {
                warnings.add(new Warning(entry.getKey(), "INSUFFICIENT_STOCK", available));
            }
        }

        long net = subtotal - lineDiscountTotal;
        long orderDiscount = discount(request.orderDiscount(), net, "orderDiscount");
        long[] shares = allocate(priced, orderDiscount, net);
        List<QuoteLine> lines = new ArrayList<>();
        for (int i = 0; i < priced.size(); i++) {
            PricedLine line = priced.get(i);
            SellableVariant variant = line.variant();
            lines.add(new QuoteLine(i + 1, variant.variantId(), variant.productName(), variant.variantName(),
                line.quantity(), variant.salePrice(), line.discount(), shares[i], line.net() - shares[i]));
        }
        long total = net - orderDiscount;
        Long change = cash == null ? null : Math.max(0, cash - total);
        var store = settings.current();
        String ref = null;
        String qr = null;
        if (transfer) {
            if (!store.hasBankAccount()) {
                throw invalid("paymentMethod", "Cửa hàng chưa cài tài khoản nhận chuyển khoản.");
            }
            ref = "TT" + key.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            if (total > 0) qr = VietQr.encode(store.bankBin(), store.bankAccountNo(), total, ref);
        }
        return new QuoteResponse(lines, subtotal, lineDiscountTotal, orderDiscount, 0,
            total, change, warnings, store.hasBankAccount(), qr, ref, transfer ? store.bankAccountName() : null);
    }

    private static long[] allocate(List<PricedLine> lines, long discount, long base) {
        long[] shares = new long[lines.size()];
        if (discount == 0) return shares;

        long remaining = discount;
        for (int i = 0; i < lines.size() - 1; i++) {
            shares[i] = BigDecimal.valueOf(discount).multiply(BigDecimal.valueOf(lines.get(i).net()))
                .divide(BigDecimal.valueOf(base), 0, RoundingMode.DOWN).longValueExact();
            remaining -= shares[i];
        }
        for (int i = lines.size() - 1; i >= 0 && remaining > 0; i--) {
            // Ưu tiên dòng cuối, mỗi dòng chỉ nhận tối đa phần tiền còn lại của nó.
            long extra = Math.min(remaining, lines.get(i).net() - shares[i]);
            shares[i] += extra;
            remaining -= extra;
        }
        return shares;
    }

    private static long discount(Discount input, long base, String field) {
        if (input == null) return 0;
        BigDecimal value = input.value();
        if (value == null || value.signum() < 0) {
            throw invalid(field, "Mức giảm phải là số không âm.");
        }
        if ("PERCENT".equals(input.type())) {
            if (value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid(field, "Phần trăm giảm tối đa là 100.");
            }
            return value.multiply(BigDecimal.valueOf(base)).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        if ("AMOUNT".equals(input.type())) {
            long amount = money(value, field);
            if (amount > base) throw invalid(field, "Mức giảm không được vượt quá số tiền áp dụng.");
            return amount;
        }
        throw invalid(field, "Loại giảm giá phải là AMOUNT hoặc PERCENT.");
    }

    private static long money(BigDecimal value, String field) {
        try {
            long amount = value.longValueExact();
            if (amount >= 0 && amount <= MAX_MONEY) return amount;
        } catch (ArithmeticException exception) {
            throw invalid(field, "Số tiền phải là số nguyên đồng trong phạm vi cho phép.");
        }
        throw invalid(field, "Số tiền phải là số nguyên đồng trong phạm vi cho phép.");
    }

    private static ProblemException invalid(String field, String message) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation",
            "Dữ liệu không hợp lệ", message,
            Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    public record Discount(String type, BigDecimal value) {}
    public record LineRequest(UUID variantId, BigDecimal quantity, Discount discount) {}
    public record QuoteRequest(List<LineRequest> lines, Discount orderDiscount,
                               String paymentMethod, BigDecimal cashReceived) {}
    private record PricedLine(SellableVariant variant, int quantity, long discount, long net) {}
    public record QuoteLine(int lineNo, UUID variantId, String productName, String variantName,
                            int quantity, long unitPrice, long lineDiscount, long orderDiscountShare,
                            long netLineAmount) {}
    public record Warning(UUID variantId, String code, int available) {}
    public record QuoteResponse(List<QuoteLine> lines, long subtotal, long lineDiscountTotal,
                                long orderDiscount, long pointsDiscount, long total, Long change,
                                List<Warning> warnings, boolean bankTransferAvailable, String vietqrPayload,
                                String transferRef, String bankAccountName) {}
}
