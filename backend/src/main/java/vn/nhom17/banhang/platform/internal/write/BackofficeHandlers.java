package vn.nhom17.banhang.platform.internal.write;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandHandler;
import vn.nhom17.banhang.platform.BackofficeCommandResult;
import vn.nhom17.banhang.platform.BackofficeFileStore;
import vn.nhom17.banhang.platform.ProblemException;
import vn.nhom17.banhang.platform.Role;

@Component
class BackofficeHandlers {
    private final ObjectProvider<BackofficeCommandHandler<?>> adapters;
    private final JsonMapper json;
    private volatile Map<String, BackofficeCommandHandler<?>> registered;

    BackofficeHandlers(ObjectProvider<BackofficeCommandHandler<?>> adapters, JsonMapper json) {
        this.adapters = adapters;
        this.json = json;
    }

    BackofficeCommandHandler<?> require(String action) {
        var handler = handlers().get(action);
        if (handler == null) throw new IllegalArgumentException("Back-office action is not registered: " + action);
        return handler;
    }

    private Map<String, BackofficeCommandHandler<?>> handlers() {
        if (registered == null) {
            synchronized (this) {
                if (registered == null) {
                    Map<String, BackofficeCommandHandler<?>> found = new LinkedHashMap<>();
                    adapters.orderedStream().forEach(handler -> {
                        if (!handler.action().matches("[A-Z][A-Z0-9_]{2,79}")
                                || found.putIfAbsent(handler.action(), handler) != null) {
                            throw new IllegalStateException("Invalid/duplicate back-office action: " + handler.action());
                        }
                    });
                    registered = Map.copyOf(found);
                }
            }
        }
        return registered;
    }

    static void authorize(BackofficeCommandHandler<?> handler, Role role) {
        if (role == null || !handler.allowedRoles().contains(role)) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "/errors/common/forbidden", "Không có quyền",
                    "Tài khoản không có quyền thực hiện thao tác này.");
        }
    }

    Object fingerprint(BackofficeCommandHandler<?> handler, Object payload) {
        return fingerprintTyped(handler, payload);
    }
    private <T> Object fingerprintTyped(BackofficeCommandHandler<T> handler, Object payload) {
        return handler.fingerprint(handler.payloadType().cast(payload));
    }
    List<BackofficeFileStore.Reference> fileReferences(BackofficeCommandHandler<?> handler, Object payload) {
        return fileReferencesTyped(handler, payload);
    }
    private <T> List<BackofficeFileStore.Reference> fileReferencesTyped(BackofficeCommandHandler<T> handler, Object payload) {
        return handler.fileReferences(handler.payloadType().cast(payload));
    }
    Object read(BackofficeCommandHandler<?> handler, JsonNode payload) {
        return json.treeToValue(payload, handler.payloadType());
    }
    BackofficeCommandResult execute(BackofficeCommandHandler<?> handler, Object payload) {
        return executeTyped(handler, payload);
    }
    private <T> BackofficeCommandResult executeTyped(BackofficeCommandHandler<T> handler, Object payload) {
        return handler.execute(handler.payloadType().cast(payload));
    }
}
