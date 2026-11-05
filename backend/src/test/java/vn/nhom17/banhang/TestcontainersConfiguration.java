package vn.nhom17.banhang;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL thật cho test (AD-21: không dùng H2): đúng image {@code pgvector/pgvector:pg18} và đúng
 * {@code deploy/db/init.sql}, nên test chạy cả 4 role và RLS như khi demo. Backend nối bằng {@code app_backend}
 * (không BYPASSRLS); Flyway chạy bằng {@code app_owner}. KHÔNG dùng superuser cho datasource, vì superuser bỏ qua RLS.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String OWNER_PASSWORD = "owner-test-password";
    public static final String BACKEND_PASSWORD = "backend-test-password";
    public static final String SYNC_PASSWORD = "sync-test-password";
    public static final String AI_PASSWORD = "ai-test-password";
    public static final String JWT_SECRET = "test-jwt-secret-0123456789-0123456789-abcdef";
    public static final String SERVICE_KEY = "test-service-key";

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("banhang")
                .withEnv("DB_OWNER_PASSWORD", OWNER_PASSWORD)
                .withEnv("DB_BACKEND_PASSWORD", BACKEND_PASSWORD)
                .withEnv("DB_SYNC_PASSWORD", SYNC_PASSWORD)
                .withEnv("DB_AI_PASSWORD", AI_PASSWORD)
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../deploy/db/init.sql")),
                        "/docker-entrypoint-initdb.d/01-init.sql");
    }

    @Bean
    DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", () -> "app_backend");
            registry.add("spring.datasource.password", () -> BACKEND_PASSWORD);
            registry.add("spring.flyway.user", () -> "app_owner");
            registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
            registry.add("app.security.jwt-secret", () -> JWT_SECRET);
            registry.add("app.security.internal-service-key", () -> SERVICE_KEY);
        };
    }
}
