package vn.nhom17.banhang.sales.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.nhom17.banhang.inventory.StockDocumentType;
import vn.nhom17.banhang.inventory.StockService;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.IdempotencyService;
import vn.nhom17.banhang.platform.IdempotencyService.IdempotentResult;
import vn.nhom17.banhang.platform.OrderCodes;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.sales.internal.OrderController.CreateOrder;
import vn.nhom17.banhang.sales.internal.OrderController.Order;

/** Một giao dịch nghiệp vụ duy nhất, dùng chung cho HTTP đồng bộ và Kafka worker. */
@Service
class OrderService {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final IdempotencyService idempotency;
    private final OrderCodes codes;
    private final StockService stock;
    private final QuoteController pricing;
    private final LoyaltyService loyalty;
    private final Clock clock;
    private final OrderRequestFailures failures;

    OrderService(JdbcTemplate jdbc, CurrentContext context, IdempotencyService idempotency,
                 OrderCodes codes, StockService stock, QuoteController pricing, LoyaltyService loyalty,
                 Clock clock, OrderRequestFailures failures) {
        this.jdbc = jdbc;
        this.context = context;
        this.idempotency = idempotency;
        this.codes = codes;
        this.stock = stock;
        this.pricing = pricing;
        this.loyalty = loyalty;
        this.clock = clock;
        this.failures = failures;
    }

    IdempotentResult<Order> create(UUID key, CreateOrder request) {
        CreateOrder canonical = canonical(request);
        return idempotency.execute("SALE", key, idempotency.hash(canonical),
                () -> save(key, canonical), this::load);
    }

    IdempotentResult<Order> createQueued(UUID key, CreateOrder request) {
        CreateOrder canonical = canonical(request);
        return idempotency.execute(queuedScope(), key, idempotency.hash(canonical),
                () -> save(key, canonical), id -> failures.contains(key) ? null : load(id));
    }

    void rejectQueued(UUID key, CreateOrder request, ProblemException rejection) {
        // Giao dịch tạo đơn đã rollback. Khóa cùng scope với createQueued: nếu bên thành công thắng,
        // execute chỉ replay và không ghi một kết quả từ chối trái với đơn đã lưu.
        CreateOrder canonical = canonical(request);
        idempotency.execute(queuedScope(), key, idempotency.hash(canonical), () -> {
            failures.record(key, rejection);
            return key;
        }, id -> null);
    }

    private String queuedScope() {
        return "SALE_QUEUE:" + context.userId();
    }

    static CreateOrder canonical(CreateOrder request) {
        int redeemed = LoyaltyService.requestedPoints(request.pointsRedeemed());
        return new CreateOrder(request.lines(), request.orderDiscount(), request.paymentMethod(),
                request.cashReceived(), request.quotedTotal(), request.customerId(),
                redeemed == 0 ? null : BigDecimal.valueOf(redeemed));
    }

    private UUID save(UUID key, CreateOrder request) {
        var quote = pricing.quoteForSale(key, new QuoteController.QuoteRequest(
            request.lines(), request.orderDiscount(), request.paymentMethod(), request.cashReceived(),
            request.customerId(), request.pointsRedeemed()));
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
                (id, store_id, customer_id, order_code, status, subtotal, line_discount_total, order_discount,
                 points_redeemed, points_discount, points_earned, loyalty_earn_amount,
                 total_amount, created_by, created_at)
            VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, orderId, storeId, request.customerId(), code, quote.subtotal(), quote.lineDiscountTotal(),
            quote.orderDiscount(), quote.loyalty() == null ? 0 : quote.loyalty().pointsRedeemed(),
            quote.pointsDiscount(), quote.loyalty() == null ? 0 : quote.loyalty().pointsEarned(),
            quote.loyalty() == null ? 0 : quote.loyalty().earnAmountPerPoint(), quote.total(), context.userId(), now);
        var movements = quote.lines().stream().map(line ->
            new StockService.StockLine(line.lineNo(), line.variantId(), -line.quantity(), null))
            .sorted(Comparator.comparing(StockService.StockLine::variantId)).toList();
        Map<Integer, Long> costs = new HashMap<>();
        for (var applied : stock.apply(StockDocumentType.SALE, orderId, movements)) {
            costs.put(applied.lineNo(), applied.unitCost());
        }
        loyalty.recordSale(request.customerId(), orderId, quote.loyalty());
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
            SELECT id, order_code, status, total_amount, created_at, points_earned, points_redeemed, points_discount
            FROM app.sales_order
            WHERE store_id = ? AND id = ?
            """, (rs, index) -> new Order(rs.getObject("id", UUID.class), rs.getString("order_code"),
            rs.getString("status"), rs.getLong("total_amount"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getInt("points_earned"), rs.getInt("points_redeemed"), rs.getLong("points_discount")),
            context.storeId(), orderId);
    }
}
