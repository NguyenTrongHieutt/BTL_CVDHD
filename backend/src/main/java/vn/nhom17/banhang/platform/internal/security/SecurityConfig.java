package vn.nhom17.banhang.platform.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Phân quyền MẶC ĐỊNH LÀ CẤM (AD-16). Mọi nhóm route khai báo vai trò được phép tại đây; route chưa khai báo bị
 * {@code denyAll}. Chủ module muốn mở route mới thì sửa file này qua PR (Hiếu duyệt). Ma trận theo PRD mục 4.7.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)  // job seed chạy không có web
@EnableMethodSecurity
class SecurityConfig {

    private static final String OWNER = "OWNER";
    private static final String STAFF = "STAFF";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtService jwt, ProblemSecurityHandlers handlers,
                                    SecurityProperties props, AccountStatusCache accountStatuses) throws Exception {
        var accountStatus = new SessionFilters.AccountStatusFilter(accountStatuses, handlers);
        var renewal = new SessionFilters.TokenRenewalFilter(jwt);

        http.csrf(csrf -> csrf.disable())   // cookie SameSite=Strict, cùng một địa chỉ sau nginx (AD-1, AD-9)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(new CookieOrHeaderBearerTokenResolver())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterAfter(accountStatus, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(renewal, SessionFilters.AccountStatusFilter.class)
                .authorizeHttpRequests(a -> a
                        // Công khai
                        .requestMatchers(HttpMethod.POST, "/api/v1/platform/auth/login", "/api/v1/platform/auth/logout",
                                "/api/v1/platform/stores").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        // Chỉ dịch vụ AI, bằng X-Service-Key (AD-9, AD-12); nginx không mở /internal ra ngoài
                        .requestMatchers("/internal/**").access(new ServiceKeyAuthorization(props.internalServiceKey()))
                        // platform
                        .requestMatchers("/api/v1/platform/me", "/api/v1/platform/me/**").authenticated()
                        .requestMatchers("/api/v1/platform/**").hasRole(OWNER)
                        // catalog: Khách hàng cả hai vai trò; xem Sản phẩm cả hai; thêm/sửa chỉ Chủ cửa hàng
                        .requestMatchers("/api/v1/catalog/customers", "/api/v1/catalog/customers/**").hasAnyRole(OWNER, STAFF)
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/**").hasAnyRole(OWNER, STAFF)
                        .requestMatchers("/api/v1/catalog/**").hasRole(OWNER)
                        // sales: bán hàng, trả hàng, tra cứu cả hai; quy tắc hủy đơn của Nhân viên kiểm trong sales (AD-16)
                        .requestMatchers("/api/v1/sales/**").hasAnyRole(OWNER, STAFF)
                        // inventory: Nhân viên chỉ xem Tồn kho (DTO không có Giá vốn) và cảnh báo tồn thấp
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/stock", "/api/v1/inventory/stock/**",
                                "/api/v1/inventory/low-stock").hasAnyRole(OWNER, STAFF)
                        .requestMatchers("/api/v1/inventory/**").hasRole(OWNER)
                        // reporting: chỉ Chủ cửa hàng
                        .requestMatchers("/api/v1/reporting/**").hasRole(OWNER)
                        .anyRequest().denyAll());
        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    /** So header {@code X-Service-Key} với {@code INTERNAL_SERVICE_KEY} theo thời gian hằng. */
    static final class ServiceKeyAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

        private final byte[] expected;

        ServiceKeyAuthorization(String key) {
            this.expected = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                             RequestAuthorizationContext context) {
            HttpServletRequest request = context.getRequest();
            String given = request.getHeader("X-Service-Key");
            boolean ok = expected.length > 0 && given != null
                    && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
            return new AuthorizationDecision(ok);
        }
    }
}
