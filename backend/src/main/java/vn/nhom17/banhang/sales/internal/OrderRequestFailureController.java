package vn.nhom17.banhang.sales.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales/order-requests")
class OrderRequestFailureController {
    private final OrderRequestFailures failures;

    OrderRequestFailureController(OrderRequestFailures failures) {
        this.failures = failures;
    }

    @GetMapping("/failures")
    OrderRequestFailures.Page failures(@RequestParam(defaultValue = "10") int size) {
        return failures.list(size);
    }
}
