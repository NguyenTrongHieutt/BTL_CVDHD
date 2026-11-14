package vn.nhom17.banhang.sales.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.OrderCodes;
import vn.nhom17.banhang.platform.ProblemException;

@RestController
@RequestMapping("/api/v1/sales/orders")
public class OrderController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final OrderCodes codes;
    private final StockService stock;
    private final QuoteController pricing;
    private final Clock clock;

    public OrderController(JdbcTemplate jdbc, CurrentContext context, IdempotencyService idempotency,
                           OrderCodes codes, StockService stock, QuoteController pricing, Clock clock) {
        this.jdbc = jdbc;
        this.context = context;
        this.idempotency = idempotency;
        this.codes = codes;
        this.stock = stock;
        this.pricing = pricing;
        this.clock = clock;
    }

    public record CreateOrder(java.util.List<QuoteController.LineRequest> lines,
                              QuoteController.Discount orderDiscount, String paymentMethod,
                              java.math.BigDecimal cashReceived, java.math.BigDecimal quotedTotal) {}

    public record Order(UUID orderId, String orderCode, String status, long total, OffsetDateTime createdAt) {}

    @PostMapping
    public ResponseEntity<Order> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                        @RequestBody CreateOrder request) {
        var result = idempotency.execute("SALE", key, idempotency.hash(request),
            () -> save(key, request), this::load);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }

    private UUID save(UUID key, CreateOrder request) {
        var quote = pricing.quote(key, new QuoteController.QuoteRequest(
            request.lines(), request.orderDiscount(), request.paymentMethod(), request.cashReceived()));
        if (request.quotedTotal() == null ||
                request.quotedTotal().compareTo(java.math.BigDecimal.valueOf(quote.total())) != 0) {
            throw ProblemException.conflict("/errors/sales/cart-stale", "Giỏ hàng đã thay đổi",
                "Tổng tiền đã đổi. Hãy kiểm tra lại trước khi thanh toán.");
        }
        boolean transfer = "BANK_TRANSFER".equals(request.paymentMethod());
        if (!transfer && (request.cashReceived() == null ||
                request.cashReceived().compareTo(java.math.BigDecimal.valueOf(quote.total())) < 0)) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation",
                "Tiền khách đưa chưa đủ", "Nhập số tiền đã nhận trước khi xác nhận.");
        }
        if (!quote.warnings().isEmpty()) {
            throw ProblemException.unprocessable("/errors/inventory/insufficient-stock", "Không đủ tồn kho",
                "Kiểm tra lại số lượng hàng trước khi xác nhận.");
        }
        UUID orderId = UUID.randomUUID();
        UUID storeId = context.storeId();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String code = codes.nextOrderCode();
        jdbc.update("""
            INSERT INTO app.sales_order
                (id, store_id, order_code, status, subtotal, line_discount_total, order_discount,
                 total_amount, created_by, created_at)
            VALUES (?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?)
            """, orderId, storeId, code, quote.subtotal(), quote.lineDiscountTotal(),
            quote.orderDiscount(), quote.total(), context.userId(), now);

        var movements = quote.lines().stream().map(line ->
            new StockService.StockLine(line.lineNo(), line.variantId(), -line.quantity(), null))
            .sorted(Comparator.comparing(StockService.StockLine::variantId)).toList();
        Map<Integer, Long> costs = new HashMap<>();
        for (var applied : stock.apply(StockDocumentType.SALE, orderId, movements)) {
            costs.put(applied.lineNo(), applied.unitCost());
        }
        for (var line : quote.lines()) {
            jdbc.update("""
                INSERT INTO app.sales_order_line
                    (store_id, order_id, line_no, variant_id, product_name, variant_name, quantity,
                     unit_price, line_discount, order_discount_share, net_line_amount, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, storeId, orderId, line.lineNo(), line.variantId(), line.productName(), line.variantName(),
                line.quantity(), line.unitPrice(), line.lineDiscount(), line.orderDiscountShare(),
                line.netLineAmount(), costs.get(line.lineNo()));
        }
        jdbc.update("""
            INSERT INTO app.payment (store_id, order_id, method, amount, cash_received, change_amount,
                                     transfer_ref, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, storeId, orderId, transfer ? "BANK_TRANSFER" : "CASH", quote.total(),
            transfer ? null : request.cashReceived().longValueExact(),
            transfer ? null : quote.change(), quote.transferRef(), now);
        return orderId;
    }

    private Order load(UUID orderId) {
        return jdbc.queryForObject("""
            SELECT id, order_code, status, total_amount, created_at FROM app.sales_order
            WHERE store_id = ? AND id = ?
            """, (rs, index) -> new Order(rs.getObject("id", UUID.class), rs.getString("order_code"),
            rs.getString("status"), rs.getLong("total_amount"), rs.getObject("created_at", OffsetDateTime.class)),
            context.storeId(), orderId);
    }
}
