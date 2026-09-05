package com.hamstrack.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Phase 3 observability wiring: Prometheus metrics are served on the
 * separate internal management port (9091 here) and the actuator surface is NOT
 * reachable on the public application port.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.enabled=false",
                "app.demo.seed-on-first-login=false",
                "seed.admin.email=",
                // fixed management port for the test (default 9090 may collide with a running app)
                "management.server.port=9091"
        }
)
class ManagementPortTest {

    @LocalServerPort
    int appPort;

    @Value("${management.server.port}")
    int managementPort;

    private final RestClient http = RestClient.create();

    @Test
    void prometheusMetricsServedOnManagementPort() {
        String body = http.get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertThat(body).isNotNull();
        // auto-provided technical metrics from Micrometer / Boot actuator
        assertThat(body).contains("jvm_");
        assertThat(body).contains("http_server_requests_seconds");
        assertThat(body).contains("hikaricp_connections");
        // the deployment tag from application.properties should be present
        assertThat(body).contains("application=\"hamstrack\"");
    }

    /**
     * <strong>The management chain carries no Content-Security-Policy</strong> (HD-264). Deliberate
     * rather than overlooked: :9090 is never published or proxied, nothing served on it is a
     * document that could execute script, and a policy is a statement about the JavaScript bundle —
     * which is not reachable from here. Asserted so the exclusion is a decision on the record
     * instead of something a future reader "fixes" by adding a header block to
     * {@code managementFilterChain}.
     */
    @Test
    void noContentSecurityPolicyOnManagementPort() {
        var headers = http.get()
                .uri("http://localhost:" + managementPort + "/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .getHeaders();

        assertThat(headers.getFirst("Content-Security-Policy-Report-Only")).isNull();
        assertThat(headers.getFirst("Content-Security-Policy")).isNull();
    }

    @Test
    void healthServedOnManagementPort() {
        HttpStatusCode status = http.get()
                .uri("http://localhost:" + managementPort + "/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
        assertThat(status.is2xxSuccessful()).isTrue();
    }

    @Test
    void actuatorNotExposedOnApplicationPort() {
        HttpStatusCode status = http.get()
                .uri("http://localhost:" + appPort + "/actuator/prometheus")
                .retrieve()
                .onStatus(s -> true, (req, res) -> { /* swallow — we only assert the code */ })
                .toBodilessEntity()
                .getStatusCode();
        // actuator is bound to the management port only; on :8080 it is not mapped,
        // and the SPA fallback / security should never return the metrics text
        assertThat(status.value()).isNotEqualTo(200);
    }
}
