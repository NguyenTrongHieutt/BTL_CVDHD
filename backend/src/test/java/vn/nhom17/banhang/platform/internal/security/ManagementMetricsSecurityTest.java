package vn.nhom17.banhang.platform.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Kiểm tra bằng hai listener thật: scrape nội bộ, chặn cổng API, nhãn không chứa dữ liệu request. */
@SpringBootTest(classes = ManagementMetricsSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.endpoints.web.exposure.include=prometheus,health",
        "management.metrics.distribution.percentiles-histogram.http.server.requests=true",
        "app.security.jwt-secret=metrics-test-signing-secret-at-least-32-bytes"
})
@AutoConfigureMetrics
class ManagementMetricsSecurityTest {

    @Value("${local.server.port}") int applicationPort;
    @Value("${local.management.port}") int managementPort;
    @MockitoBean AccountStatusCache accountStatuses;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void scrapesJvmAndHttpHistogramOnlyOnTheManagementListener() throws Exception {
        var sample = get(applicationPort, "/v3/api-docs/metrics-sample/customer-secret-947?key=query-secret-947");
        assertThat(sample.statusCode()).isEqualTo(200);

        var scrape = client.send(HttpRequest.newBuilder(uri(managementPort, "/actuator/prometheus"))
                .header("Cookie", "access_token=cookie-secret-947")
                .header("X-Service-Key", "service-secret-947")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("content-type").orElse("")).contains("text/plain");
        assertThat(scrape.headers().allValues("set-cookie")).isEmpty();
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "http_server_requests_seconds_bucket",
                        "uri=\"/v3/api-docs/metrics-sample/{id}\"")
                .doesNotContain("customer-secret-947", "query-secret-947", "cookie-secret-947", "service-secret-947");
        assertThat(get(applicationPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }

    @Test
    void probesRemainAvailableOnTheApplicationListener() throws Exception {
        assertThat(get(applicationPort, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(applicationPort, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void theManagementListenerDoesNotOpenOtherActuatorEndpoints() throws Exception {
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/metrics").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class}, excludeName = {
            "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
            "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration"
    })
    @EnableConfigurationProperties(SecurityProperties.class)
    @Import({SecurityConfig.class, JwtService.class, ProblemSecurityHandlers.class, SampleController.class})
    static class TestApplication {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    @RestController
    static class SampleController {
        @GetMapping("/v3/api-docs/metrics-sample/{id}")
        String sample() { return "ok"; }
    }
}
