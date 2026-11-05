package vn.nhom17.banhang.platform.internal.context;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.CurrentContext;
import vn.nhom17.banhang.platform.Role;
import vn.nhom17.banhang.platform.TenantScope;

/** Ngữ cảnh lấy từ {@link TenantScope} (ưu tiên) hoặc từ JWT đã xác thực. */
@Component
class SecurityCurrentContext implements CurrentContext {

    @Override
    public UUID storeId() {
        return storeIdIfPresent().orElseThrow(() -> new IllegalStateException("Chưa có ngữ cảnh Cửa hàng"));
    }

    @Override
    public UUID userId() {
        return userIdIfPresent().orElseThrow(() -> new IllegalStateException("Chưa đăng nhập"));
    }

    @Override
    public Role role() {
        TenantScope.Scope scope = TenantScope.current();
        if (scope != null) {
            return scope.role();
        }
        return jwt().map(j -> Role.valueOf(j.getClaimAsString("role")))
                .orElseThrow(() -> new IllegalStateException("Chưa đăng nhập"));
    }

    @Override
    public Optional<UUID> storeIdIfPresent() {
        TenantScope.Scope scope = TenantScope.current();
        if (scope != null) {
            return Optional.of(scope.storeId());
        }
        return jwt().map(j -> UUID.fromString(j.getClaimAsString("store_id")));
    }

    @Override
    public Optional<UUID> userIdIfPresent() {
        TenantScope.Scope scope = TenantScope.current();
        if (scope != null) {
            return Optional.ofNullable(scope.userId());
        }
        return jwt().map(j -> UUID.fromString(j.getSubject()));
    }

    private static Optional<Jwt> jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Optional.of(token.getToken());
        }
        return Optional.empty();
    }
}
