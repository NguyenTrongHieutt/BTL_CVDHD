package vn.nhom17.banhang.platform.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.nhom17.banhang.platform.Role;

/**
 * Hai filter của phiên đăng nhập (AD-9). Không phải bean, để Spring Boot không đăng ký chúng thêm lần nữa ngoài
 * chuỗi bảo mật.
 */
final class SessionFilters {

    private SessionFilters() {
    }

    /** Mỗi request kiểm tra tài khoản còn hoạt động, cache tối đa {@code accountStatusCache}. */
    static final class AccountStatusFilter extends OncePerRequestFilter {

        private record Entry(boolean active, Instant checkedAt) {
        }

        private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final Clock clock;
        private final SecurityProperties props;
        private final ProblemSecurityHandlers handlers;

        AccountStatusFilter(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, SecurityProperties props,
                            ProblemSecurityHandlers handlers) {
            this.jdbc = jdbc;
            this.tx = tx;
            this.clock = clock;
            this.props = props;
            this.handlers = handlers;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken token && !isActive(UUID.fromString(token.getToken().getSubject()))) {
                SecurityContextHolder.clearContext();
                handlers.commence(request, response, new ProblemSecurityHandlers.AccountLockedException());
                return;
            }
            chain.doFilter(request, response);
        }

        private boolean isActive(UUID userId) {
            Instant now = clock.instant();
            Entry cached = cache.get(userId);
            if (cached != null && cached.checkedAt().plus(props.accountStatusCache()).isAfter(now)) {
                return cached.active();
            }
            Boolean active = tx.execute(status -> jdbc.query(
                    "select status from app.app_user where id = ?",
                    rs -> rs.next() && "ACTIVE".equals(rs.getString(1)), userId));
            boolean result = Boolean.TRUE.equals(active);
            cache.put(userId, new Entry(result, now));
            return result;
        }

        /** Dùng khi khóa tài khoản để có hiệu lực ngay trên máy chủ này. */
        void evict(UUID userId) {
            cache.remove(userId);
        }
    }

    /** Gia hạn trượt: token lấy từ cookie mà đã cũ hơn {@code renewAfter} thì cấp cookie mới. */
    static final class TokenRenewalFilter extends OncePerRequestFilter {

        private final JwtService jwt;

        TokenRenewalFilter(JwtService jwt) {
            this.jwt = jwt;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken token && fromCookie(request, token.getToken())
                    && jwt.shouldRenew(token.getToken().getIssuedAt())) {
                Jwt t = token.getToken();
                String renewed = jwt.issue(UUID.fromString(t.getSubject()), UUID.fromString(t.getClaimAsString("store_id")),
                        Role.valueOf(t.getClaimAsString("role")));
                response.addHeader(HttpHeaders.SET_COOKIE, jwt.cookie(renewed).toString());
            }
            chain.doFilter(request, response);
        }

        private static boolean fromCookie(HttpServletRequest request, Jwt token) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return false;
            }
            for (Cookie c : cookies) {
                if (SecurityProperties.COOKIE_NAME.equals(c.getName()) && token.getTokenValue().equals(c.getValue())) {
                    return true;
                }
            }
            return false;
        }
    }
}
