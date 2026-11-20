package vn.nhom17.banhang.sales.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TimeZones;
import vn.nhom17.banhang.platform.UserNameLookup;

@RestController
@RequestMapping("/api/v1/sales")
public class OrderHistoryController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final CustomerLookup customers;
    private final UserNameLookup users;

    public OrderHistoryController(JdbcTemplate jdbc, CurrentContext context, CustomerLookup customers,
                                  UserNameLookup users) {
        this.jdbc = jdbc;
        this.context = context;
        this.customers = customers;
        this.users = users;
    }

    @GetMapping("/orders")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<OrderSummary> orders(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String customerPhone,
            @RequestParam(required = false) String orderCode,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        if (fromDate != null && toDate != null
                && (toDate.isBefore(fromDate) || ChronoUnit.DAYS.between(fromDate, toDate) >= 366)) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "/errors/common/validation",
                    "Khoảng ngày không hợp lệ", "Chọn khoảng ngày theo thứ tự và tối đa 366 ngày.");
        }

        int pageNo = Math.max(0, page);
        int pageSize = PageResponse.clampSize(size);
        StringBuilder where = new StringBuilder(" FROM app.sales_order WHERE store_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(context.storeId());
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        if (customerPhone != null && !customerPhone.isBlank()) {
            var customer = customers.findByPhone(customerPhone);
            if (customer.isEmpty()) {
                return new PageResponse<>(List.of(), 0, pageNo, pageSize);
            }
            where.append(" AND customer_id = ?");
            args.add(customer.get().customerId());
        }
        if (orderCode != null && !orderCode.isBlank()) {
            where.append(" AND strpos(order_code, upper(?)) > 0");
            args.add(orderCode.strip());
        }
        if (fromDate != null) {
            where.append(" AND created_at >= ?");
            args.add(startOfDay(fromDate));
        }
        if (toDate != null) {
            where.append(" AND created_at < ?");
            args.add(startOfDay(toDate.plusDays(1)));
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (sellerId != null) {
            where.append(" AND created_by = ?");
            args.add(sellerId);
        }

        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
        long offset = (long) pageNo * pageSize;
        if (offset >= total) {
            return new PageResponse<>(List.of(), total, pageNo, pageSize);
        }

        args.add(pageSize);
        args.add(offset);
        List<OrderRow> rows = jdbc.query("""
                SELECT id, order_code, created_at, status, total_amount, created_by
                """ + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, index) -> new OrderRow(rs.getObject("id", UUID.class), rs.getString("order_code"),
                        rs.getObject("created_at", OffsetDateTime.class), OrderStatus.valueOf(rs.getString("status")),
                        rs.getLong("total_amount"), rs.getObject("created_by", UUID.class)), args.toArray());
        var sellerNames = users.findNames(rows.stream().map(OrderRow::sellerId).distinct().toList());
        return new PageResponse<>(rows.stream().map(row -> new OrderSummary(
                row.orderId(), row.orderCode(), row.createdAt(), row.status(), row.total(),
                sellerNames.get(row.sellerId()))).toList(), total, pageNo, pageSize);
    }

    @GetMapping("/orders/sellers")
    @Transactional(readOnly = true)
    public List<Seller> sellers() {
        var ids = jdbc.query("""
                SELECT DISTINCT created_by FROM app.sales_order WHERE store_id = ?
                """, (rs, index) -> rs.getObject("created_by", UUID.class), context.storeId());
        return users.findNames(ids).entrySet().stream()
                .sorted(Map.Entry.<UUID, String>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new Seller(entry.getKey(), entry.getValue())).toList();
    }

    @GetMapping("/customers/{customerId}/purchase-summary")
    @Transactional(readOnly = true)
    public PurchaseSummary purchaseSummary(@PathVariable UUID customerId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS order_count, coalesce(sum(total_amount), 0) AS total_spent,
                       max(created_at) AS last_purchase_at
                FROM app.sales_order
                WHERE store_id = ? AND customer_id = ? AND status = 'COMPLETED'
                """, (rs, index) -> new PurchaseSummary(rs.getLong("order_count"), rs.getLong("total_spent"),
                rs.getObject("last_purchase_at", OffsetDateTime.class)), context.storeId(), customerId);
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TimeZones.VIETNAM).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    public enum OrderStatus { COMPLETED, CANCELLED, RETURNED }

    private record OrderRow(UUID orderId, String orderCode, OffsetDateTime createdAt,
                            OrderStatus status, long total, UUID sellerId) {}

    public record OrderSummary(UUID orderId, String orderCode, OffsetDateTime createdAt,
                               OrderStatus status, long total, String sellerName) {}

    public record Seller(UUID sellerId, String sellerName) {}

    public record PurchaseSummary(long orderCount, long totalSpent, OffsetDateTime lastPurchaseAt) {}
}
