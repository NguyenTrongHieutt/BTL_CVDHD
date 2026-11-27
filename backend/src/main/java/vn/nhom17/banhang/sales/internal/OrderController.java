package vn.nhom17.banhang.sales.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales/orders")
public class OrderController {
    private final OrderService orders;
    private final ObjectProvider<OrderCommandPublisher> publisher;

    OrderController(OrderService orders, ObjectProvider<OrderCommandPublisher> publisher) {
        this.orders = orders;
        this.publisher = publisher;
    }

    public record CreateOrder(java.util.List<QuoteController.LineRequest> lines,
                              QuoteController.Discount orderDiscount, String paymentMethod,
                              java.math.BigDecimal cashReceived, java.math.BigDecimal quotedTotal,
                              // Giữ nguyên hash của các đơn khách lẻ đã lưu trước DH-06.
                              @JsonInclude(JsonInclude.Include.NON_NULL) UUID customerId,
                              // 0 được chuẩn hóa thành null trước khi hash để replay được cả đơn trước KH-04.
                              @JsonAlias("pointsToRedeem")
                              @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal pointsRedeemed) {}

    public record Order(UUID orderId, String orderCode, String status, long total, OffsetDateTime createdAt,
                        int pointsEarned, int pointsRedeemed, long pointsDiscount) {}

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) UUID key,
                                    @RequestBody CreateOrder request) {
        OrderCommandPublisher queue = publisher.getIfAvailable();
        if (queue != null) {
            return ResponseEntity.accepted().body(queue.publish(key, request));
        }
        var result = orders.create(key, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.value());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.value());
    }
}
