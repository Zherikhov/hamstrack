package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R4's two endpoints are inside the reports budget, and inside the <em>same</em> one.
 *
 * <p>Small on purpose: {@code FlowReportThrottleTest} and {@code AgingReportThrottleTest} already
 * establish what the limiter does and that it runs before tenancy is resolved. The only thing left
 * to prove for a new report is that it is actually on the path the interceptor is bound to —
 * {@code /api/workspaces/*&#47;projects/*&#47;reports/**} — because a report that quietly sat
 * outside the budget would look completely healthy until somebody put it in a loop.
 *
 * <p>Same annotation values as those classes wherever they can be, so Spring's context cache key
 * matches and this does not stand up a third context; the budget differs because the assertion
 * needs exactly two requests to fit.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=2",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintReportThrottleTest extends SprintReportTestBase {

    @Test
    void theSprintReportsShareTheOneReportBudget() throws Exception {
        var ctx = newProject();

        getBurnup(ctx, ctx.token(), null).andExpect(status().isOk());
        getReview(ctx, ctx.token(), null).andExpect(status().isOk());

        // The third request is over the budget whichever of the two it is: one pot, not one each.
        getBurnup(ctx, ctx.token(), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        getReview(ctx, ctx.token(), null).andExpect(status().isTooManyRequests());
    }
}
