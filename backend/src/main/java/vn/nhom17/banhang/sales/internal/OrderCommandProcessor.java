package vn.nhom17.banhang.sales.internal;

import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.TenantScope;

@Component
class OrderCommandProcessor {
    private final OrderService orders;
    private final OrderRequestFailures failures;

    OrderCommandProcessor(OrderService orders, OrderRequestFailures failures) {
        this.orders = orders;
        this.failures = failures;
    }

    void process(OrderCommand command) {
        // Không mang SecurityContext/JWT qua Kafka. TenantScope đặt RLS trước mỗi giao dịch và tự dọn thread.
        TenantScope.runAs(command.storeId(), command.actorId(), command.actorRole(), () -> {
            if (failures.contains(command.operationId())) return null;
            try {
                orders.createQueued(command.operationId(), command.request());
            } catch (ProblemException rejection) {
                if (!rejection.status().is4xxClientError()) throw rejection;
                if ("/errors/common/idempotency-key-reuse".equals(rejection.type())) {
                    // Khóa đã có kết quả bền; thông báo nội dung mới bị từ chối, không sửa đơn cũ.
                    failures.record(command.operationId(), rejection);
                } else {
                    orders.rejectQueued(command.operationId(), command.request(), rejection);
                }
            }
            return null;
        });
    }
}
