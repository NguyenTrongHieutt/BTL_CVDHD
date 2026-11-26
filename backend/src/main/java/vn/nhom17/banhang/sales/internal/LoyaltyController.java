package vn.nhom17.banhang.sales.internal;

import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.nhom17.banhang.catalog.CustomerLookup;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.StoreSettings;

@RestController
@RequestMapping("/api/v1/sales/customers")
class LoyaltyController {
    private final CustomerLookup customers;
    private final LoyaltyService loyalty;
    private final StoreSettings settings;

    LoyaltyController(CustomerLookup customers, LoyaltyService loyalty, StoreSettings settings) {
        this.customers = customers;
        this.loyalty = loyalty;
        this.settings = settings;
    }

    @GetMapping("/{customerId}/loyalty")
    @Transactional(readOnly = true)
    public Balance balance(@PathVariable UUID customerId) {
        if (customers.findById(customerId).isEmpty()) {
            throw ProblemException.notFound("/errors/common/not-found", "Không tìm thấy khách hàng",
                    "Khách hàng không tồn tại trong cửa hàng hiện tại.");
        }
        var store = settings.current();
        return new Balance(loyalty.balanceOf(customerId), store.loyaltyEarnAmount(), store.loyaltyPointValue());
    }

    record Balance(int balance, long earnAmountPerPoint, long pointValue) {}
}
