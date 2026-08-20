package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The throttle for the one report that does <strong>not</strong> live under the reports base path.
 *
 * <p>Every other report inherits its budget by construction: {@code ReportRateLimitConfig} binds
 * the interceptor to {@code …/reports/**}, so a new {@code @GetMapping} under it is throttled the
 * moment it exists. Insights hangs off {@code /search} instead, so it inherits <em>nothing</em> —
 * it needed a second path pattern, and a second path pattern is a line somebody can delete while
 * every functional test keeps passing (they all run with {@code app.rate-limit.enabled=false}).
 * Hence this file.
 *
 * <p>It matters more here than anywhere else on the surface. This endpoint's work is O(matching
 * issues) regardless of what it returns, and being a POST it is the one report that <em>no</em>
 * HTTP cache will ever absorb a repeat of — browsers do not cache POST responses at all. Without
 * a budget, one authenticated member in a loop is a connection-pool exhaustion made entirely of
 * legal 200s.
 *
 * <p>And, as everywhere else on this surface, the budget is spent in {@code preHandle} —
 * <strong>before</strong> the workspace is resolved — which is what makes the 429 safe to return
 * to a stranger: it is byte-identical for a workspace that exists, one that does not, and one the
 * caller merely cannot see.
 *
 * <p>Properties are byte-identical to the other throttle tests' so Spring's context cache key
 * matches and no second context is stood up.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InsightsThrottleTest extends InsightsTestBase {

    @Test
    void insightsIsThrottledAndTheRefusalArrivesBeforeTenancy() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        // Three probes at a workspace I am not a member of: 404 every time, and each still costs
        // me a unit of my own budget.
        for (int i = 0; i < 3; i++) {
            insights(theirs.wsId(), mine.token(), "", null, "STATUS", null)
                    .andExpect(status().isNotFound());
        }

        insights(theirs.wsId(), mine.token(), "", null, "STATUS", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Identical for a workspace that does not exist at all, so the 429 cannot be read as
        // "that one was real".
        insights(UUID.randomUUID(), mine.token(), "", null, "STATUS", null)
                .andExpect(status().isTooManyRequests());

        // A scan against you must never throttle you.
        insights(theirs.wsId(), theirs.token(), "", null, "STATUS", null)
                .andExpect(status().isOk());
    }

    /**
     * One pot for the whole reports surface, across the two base paths. A caller must not double
     * their allowance by alternating between a project report and the panel — which is exactly
     * what a second, separately-counted limiter would have given them.
     */
    @Test
    void insightsSharesTheOneReportBudgetWithTheProjectReports() throws Exception {
        var ctx = newProject();

        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", null).andExpect(status().isOk());
        insights(ctx.wsId(), ctx.token(), "", null, "TYPE", null).andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/reports/aging")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());

        insights(ctx.wsId(), ctx.token(), "", null, "PRIORITY", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
