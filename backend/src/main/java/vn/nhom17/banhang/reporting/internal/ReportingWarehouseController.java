package vn.nhom17.banhang.reporting.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reporting/warehouse")
class ReportingWarehouseController {
    private final ReportingWarehouse warehouse;

    ReportingWarehouseController(ReportingWarehouse warehouse) {
        this.warehouse = warehouse;
    }

    @GetMapping("/status")
    ReportingWarehouse.Status status() {
        return warehouse.status();
    }
}
