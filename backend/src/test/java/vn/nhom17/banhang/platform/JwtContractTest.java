package vn.nhom17.banhang.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.internal.security.JwtService;
import vn.nhom17.banhang.platform.internal.security.SecurityProperties;

/**
 * Hợp đồng JWT Java ↔ Python (AD-9, AD-21). Token mẫu trong {@code docs/contracts/jwt-fixture.json} do Java ký;
 * test này kiểm Java đọc đúng, còn {@code ai-service/tests} kiểm PyJWT đọc đúng cùng token đó.
 * Sinh lại file: {@code ./mvnw test -Dtest=JwtContractTest -DupdateJwtFixture=true}.
 */
class JwtContractTest {

    private static final Path FIXTURE = Path.of("../docs/contracts/jwt-fixture.json");
    private static final String SECRET = "contract-secret-0123456789-0123456789-abcdef";
    private static final UUID USER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID STORE = UUID.fromString("a0000000-0000-4000-8000-0000000000aa");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void javaReadsTheSharedFixtureToken() throws Exception {
        if (Boolean.getBoolean("updateJwtFixture") || !Files.exists(FIXTURE)) {
            writeFixture();
        }
        JsonNode fixture = json.readTree(FIXTURE.toFile());
        JwtService service = service(Clock.systemUTC(), Duration.ofHours(8));
        Jwt jwt = service.decoder().decode(fixture.get("token").asText());
        assertThat(jwt.getSubject()).isEqualTo(USER.toString());
        assertThat(jwt.getClaimAsString("store_id")).isEqualTo(STORE.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
    }

    @Test
    void issuedTokenHasExactlyTheAgreedClaims() {
        Instant now = Instant.parse("2026-11-06T01:00:00Z");
        JwtService service = service(Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(8));
        Jwt jwt = service(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC), Duration.ofHours(8)).decoder()
                .decode(service.issue(USER, STORE, Role.STAFF));
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "store_id", "role", "iat", "exp", "jti");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(8)));
    }

    private void writeFixture() throws Exception {
        // Hạn rất xa để fixture dùng lâu dài; token thật luôn hết hạn sau 8 giờ.
        Instant issued = Instant.parse("2026-01-01T00:00:00Z"); // iat phải ở quá khứ: PyJWT từ chối iat tương lai
        JwtService service = service(Clock.fixed(issued, ZoneOffset.UTC), Duration.ofDays(365L * 70));
        String token = service.issue(USER, STORE, Role.OWNER);
        Files.createDirectories(FIXTURE.getParent());
        var node = json.createObjectNode();
        node.put("_note", "Token mẫu do backend Java ký (JwtContractTest). Chỉ dùng cho test hợp đồng, không phải bí mật thật.");
        node.put("secret", SECRET);
        node.put("token", token);
        node.put("sub", USER.toString());
        node.put("store_id", STORE.toString());
        node.put("role", "OWNER");
        Files.writeString(FIXTURE, json.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
    }

    private static JwtService service(Clock clock, Duration ttl) {
        return new JwtService(new SecurityProperties(SECRET, ttl, Duration.ofMinutes(15), Duration.ofSeconds(60),
                false, "k"), clock);
    }
}
