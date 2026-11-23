package vn.nhom17.banhang.sales.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.StoreSettings;

@RestController
@RequestMapping("/api/v1/sales/orders")
public class OrderReceiptController {
    private final JdbcTemplate jdbc;
    private final CurrentContext context;
    private final StoreSettings storeSettings;

    public OrderReceiptController(JdbcTemplate jdbc, CurrentContext context, StoreSettings storeSettings) {
        this.jdbc = jdbc;
        this.context = context;
        this.storeSettings = storeSettings;
    }

    @GetMapping("/{id}/receipt")
    @Transactional(readOnly = true)
    public Receipt receipt(@PathVariable UUID id) {
        UUID storeId = context.storeId();
        ReceiptOrder order;
        try {
            order = jdbc.queryForObject("""
                SELECT order_code, created_at, subtotal, line_discount_total + order_discount + points_discount AS discount,
                       total_amount
                FROM app.sales_order
                WHERE store_id = ? AND id = ?
                """, (rs, row) -> new ReceiptOrder(
                rs.getString("order_code"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("subtotal"), rs.getLong("discount"), rs.getLong("total_amount")),
                storeId, id);
        } catch (EmptyResultDataAccessException ex) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy đơn hàng",
                "Đơn hàng không tồn tại trong cửa hàng hiện tại.");
        }

        var lines = jdbc.query("""
            SELECT line_no, product_name, variant_name, quantity, unit_price, net_line_amount
            FROM app.sales_order_line
            WHERE store_id = ? AND order_id = ?
            ORDER BY line_no
            """, (rs, row) -> new ReceiptLine(
            rs.getInt("line_no"), rs.getString("product_name"), rs.getString("variant_name"),
            rs.getInt("quantity"), rs.getLong("unit_price"), rs.getLong("net_line_amount")),
            storeId, id);

        var settings = storeSettings.current();
        return new Receipt(new ReceiptStore(settings.name(), settings.address(), settings.phone(),
            settings.invoiceFooter()), order, lines);
    }

    public record Receipt(ReceiptStore store, ReceiptOrder order, List<ReceiptLine> lines) {}

    public record ReceiptStore(String name, String address, String phone, String invoiceFooter) {}

    public record ReceiptOrder(String orderCode, OffsetDateTime createdAt, long subtotal, long discount, long total) {}

    public record ReceiptLine(int lineNo, String productName, String variantName, int quantity,
                              long unitPrice, long netLineAmount) {}
}
