package com.hamstrack.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>The instance ceiling, which binds when no single sender does</strong> (HD-264).
 *
 * <p>Its own context rather than a second method beside {@code CspReportBudgetTest}, and the reason
 * is the thing being tested: an instance ceiling is <em>shared</em>, so exercising it in the same
 * minute as the per-IP test starves that test of the budget it is asserting about. Two contexts is
 * the honest price of two ceilings that are deliberately not independent.
 *
 * <p>The shape here is the one that matters. A per-IP bound alone is no bound at all against a
 * sender with more than one address, and an unauthenticated endpoint has no other way to tell one
 * sender from ten — so the second ceiling is not belt-and-braces, it is the only one that applies
 * to the abuse that is actually cheap to mount.
 */
@SpringBootTest(properties = {
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=1000",
        "app.csp.reports-per-minute=5",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CspReportInstanceBudgetTest {

    private static final String REPORT =
            "{\"csp-report\":{\"document-uri\":\"http://localhost:8080/\","
            + "\"effective-directive\":\"img-src\"}}";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void tenSendersEachInsideTheirOwnBudgetStillHitTheInstanceCeiling() throws Exception {
        double before = dropped("budget");
        int refusals = 0;

        for (int host = 100; host < 110; host++) {
            for (int i = 0; i < 2; i++) {
                if (status("203.0.113." + host) == 429) {
                    refusals++;
                }
            }
        }

        assertThat(refusals)
                .as("""
                    THE INSTANCE CEILING MUST BIND WHEN NO SINGLE SENDER DOES.

                    Twenty reports from ten addresses, two each - every one of them far inside the \
                    per-IP budget of 1000. Without this ceiling the sink's only bound is per \
                    address, which is not a bound at all against anybody who has more than one. A \
                    sink hearing from ten simultaneously-violating clients has already told the \
                    operator everything it can and must not become the instance's log budget.""")
                // 15 exactly when all twenty land in one minute, and >= 10 is what survives a
                // window rolling mid-run: the split that refuses fewest is ten and ten, which
                // still refuses five in each. Asserted as the bound rather than the number,
                // because a test that is right 999 times in 1000 is a flake with a good excuse.
                .isGreaterThanOrEqualTo(10);
        assertThat(dropped("budget") - before).isEqualTo(refusals);
    }

    private int status(String ip) throws Exception {
        return mockMvc.perform(post(ContentSecurityPolicy.REPORT_PATH)
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType("application/csp-report")
                        .content(REPORT))
                .andReturn().getResponse().getStatus();
    }

    private double dropped(String reason) {
        var counter = meterRegistry.find("hamstrack.csp.reports_dropped")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
