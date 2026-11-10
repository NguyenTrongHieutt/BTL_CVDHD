package vn.nhom17.banhang.platform.internal.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import vn.nhom17.banhang.platform.Role;

/**
 * Ký và kiểm tra JWT HS256 (AD-9). Claim: {@code sub}, {@code store_id}, {@code role}, {@code iat}, {@code exp},
 * {@code jti}. Python ({@code ai-service}) kiểm tra bằng cùng {@code JWT_SECRET}.
 */
@Component
public class JwtService {

    private final SecurityProperties props;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtService(SecurityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        SecretKey key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Kiểm tra exp/nbf theo Clock của ứng dụng (cùng nguồn giờ với lúc cấp), không theo giờ hệ thống.
        // createDefaultWithValidators giữ các validator mặc định (typ, thumbprint) và chỉ thêm JwtTimestampValidator
        // khi danh sách chưa có, nên không có validator thứ hai theo giờ hệ thống.
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        nimbus.setJwtValidator(JwtValidators.createDefaultWithValidators(timestamps));
        this.decoder = nimbus;
    }

    public String issue(UUID userId, UUID storeId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("store_id", storeId.toString())
                .claim("role", role.name())
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public ResponseCookie cookie(String token) {
        return ResponseCookie.from(SecurityProperties.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(props.tokenTtl())
                .build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(SecurityProperties.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    public boolean shouldRenew(Instant issuedAt) {
        return issuedAt != null && issuedAt.plus(props.renewAfter()).isBefore(clock.instant());
    }
}
