package vn.nhom17.banhang.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/** Giữ nguyên body, mã HTTP và header nghiệp vụ của đường ghi đồng bộ. */
public record BackofficeCommandResult(int responseStatus, Object result, Map<String, String> responseHeaders) {
    public BackofficeCommandResult {
        if (responseStatus < 200 || responseStatus >= 300) throw new IllegalArgumentException("Expected a success status");
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }
    public static BackofficeCommandResult of(int status, Object body) {
        return new BackofficeCommandResult(status, body, Map.of());
    }
    public static BackofficeCommandResult ok(Object body) { return of(200, body); }
    public static BackofficeCommandResult created(Object body) { return of(201, body); }
    public static BackofficeCommandResult from(ResponseEntity<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : new String[] {"Location", "Idempotent-Replay"}) {
            String value = response.getHeaders().getFirst(name);
            if (value != null) headers.put(name, value);
        }
        return new BackofficeCommandResult(response.getStatusCode().value(), response.getBody(), headers);
    }
}
