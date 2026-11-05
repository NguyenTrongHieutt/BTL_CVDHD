package vn.nhom17.banhang.platform.internal.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình bảo mật (AD-9). */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        String jwtSecret,
        Duration tokenTtl,
        Duration renewAfter,
        Duration accountStatusCache,
        boolean cookieSecure,
        String internalServiceKey) {

    public static final String COOKIE_NAME = "access_token";

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET phải là chuỗi UTF-8 từ 32 byte trở lên (AD-9)");
        }
        if (tokenTtl == null) {
            tokenTtl = Duration.ofHours(8);
        }
        if (renewAfter == null) {
            renewAfter = Duration.ofMinutes(15);
        }
        if (accountStatusCache == null) {
            accountStatusCache = Duration.ofSeconds(60);
        }
    }
}
