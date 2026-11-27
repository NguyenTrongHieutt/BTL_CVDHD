package vn.nhom17.banhang.platform.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = ManagementMetricsSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.jwt-secret=metrics-test-signing-secret-at-least-32-bytes"
})
class ManagementMetricsDisabledTest {
    @Value("${local.server.port}") int applicationPort;
    @MockitoBean AccountStatusCache accountStatuses;

    @Test
    void defaultConfigurationKeepsHealthAndDoesNotExposePrometheus() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var health = client.send(request("/actuator/health"), HttpResponse.BodyHandlers.ofString());
            var prometheus = client.send(request("/actuator/prometheus"), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(prometheus.statusCode()).isEqualTo(401);
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path)).GET().build();
    }
}
