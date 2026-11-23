package vn.nhom17.banhang.platform.internal.audit;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.AuditAction;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Tra cứu Nhật ký thao tác (FR-49, TK-06). Chỉ Chủ cửa hàng (AD-16, SecurityConfig).
 */
@RestController
@RequestMapping(AuditLogController.PATH)
class AuditLogController {

    static final String PATH = "/api/v1/platform/audit-log";

    private final AuditLogQuery query;

    AuditLogController(AuditLogQuery query) {
        this.query = query;
    }

    record ActionOption(String action, String label) {}

    @GetMapping("/actions")
    List<ActionOption> actions() {
        return Arrays.stream(AuditAction.values())
                .map(action -> new ActionOption(action.code(), action.label()))
                .toList();
    }

    @GetMapping
    PageResponse<AuditLogEntry> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return query.find(action, actorId, fromDate, toDate, page, size);
    }
}
