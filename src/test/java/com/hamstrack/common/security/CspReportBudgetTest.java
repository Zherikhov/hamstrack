package com.hamstrack.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>The sink's own volume bound</strong> (HD-264) — per sender, per instance, and
 * deliberately outside the master rate-limit switch.
 *
 * <p>{@code app.rate-limit.enabled=false} here, and that is the point of the class rather than
 * boilerplate: this budget is the only bound on an endpoint that requires no account at all, so if
 * it answered to the master switch an operator debugging a limiter would silently convert the sink
 * into a public log-fill primitive. The off switch for this door is the door —
 * {@code CSP_REPORT_SINK_ENABLED=false} — which is why it is offered no third state.
 */
@SpringBootTest(properties = {
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=3",
        "app.csp.reports-per-minute=10000",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CspReportBudgetTest {

    private static final String REPORT =
            "{\"csp-report\":{\"document-uri\":\"http://localhost:8080/\","
            + "\"effective-directive\":\"img-src\"}}";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void theFourthReportFromOneSenderIsRefusedWithRetryAfterAndCounted() throws Exception {
        var ip = "198.51.100.21";
        double before = dropped("budget");

        for (int i = 1; i <= 3; i++) {
            assertThat(send(ip).getStatus()).as("report %d of the per-IP budget", i).isEqualTo(204);
        }
        var refused = send(ip);

        assertThat(refused.getStatus())
                .as("""
                    THE SINK MUST HAVE A BOUND OF ITS OWN, AND IT MUST HOLD WITH \
                    app.rate-limit.enabled=false.

                    This is the only bound on an endpoint that needs no account. If the master \
                    switch reached it, "turn off rate limiting while I debug something" would \
                    quietly mean "let anyone on the internet write unlimited lines into this \
                    instance's log".""")
                .isEqualTo(429);
        assertThat(refused.getHeader("Retry-After"))
                .as("a refusal may only prescribe an action its reader can perform, so it says "
                    + "when the window rolls")
                .isNotNull();
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(refused.getContentAsString())
                .as("no body: there is nothing a report sender is entitled to know")
                .isEmpty();
        assertThat(dropped("budget") - before).isEqualTo(1.0);
    }

    private MockHttpServletResponse send(String ip) throws Exception {
        return mockMvc.perform(post(ContentSecurityPolicy.REPORT_PATH)
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType("application/csp-report")
                        .content(REPORT))
                .andReturn().getResponse();
    }

    private double dropped(String reason) {
        var counter = meterRegistry.find("hamstrack.csp.reports_dropped")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
