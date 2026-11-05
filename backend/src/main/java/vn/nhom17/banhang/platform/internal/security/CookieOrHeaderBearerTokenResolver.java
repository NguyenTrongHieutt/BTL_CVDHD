package vn.nhom17.banhang.platform.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Lấy JWT từ header {@code Authorization: Bearer} (ai-service gọi reporting) hoặc từ cookie {@code access_token}
 * (trình duyệt) — AD-9. Bỏ qua token ở các endpoint đăng nhập để cookie cũ hết hạn không chặn việc đăng nhập lại.
 */
class CookieOrHeaderBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/v1/platform/auth/") || uri.equals("/api/v1/platform/stores")
                || uri.startsWith("/actuator/health")) {
            return null;
        }
        String fromHeader = header.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SecurityProperties.COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
