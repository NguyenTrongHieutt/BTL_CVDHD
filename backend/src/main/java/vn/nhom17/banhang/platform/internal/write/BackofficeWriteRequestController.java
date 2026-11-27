package vn.nhom17.banhang.platform.internal.write;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/write-requests")
class BackofficeWriteRequestController {
    private final BackofficeWriteRequests requests;
    BackofficeWriteRequestController(BackofficeWriteRequests requests) { this.requests = requests; }

    /** Luôn đọc primary; kết quả không được chuyển qua replica còn trễ WAL. */
    @GetMapping("/{operationId}")
    BackofficeWriteRequests.Status status(@PathVariable UUID operationId) {
        return requests.status(operationId);
    }
}
