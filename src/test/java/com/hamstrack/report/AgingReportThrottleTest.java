package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-138 R3 round 2, item 6 — <strong>the throttle-before-tenancy ordering, asserted for R3's
 * endpoints instead of inherited by inspection</strong>.
 *
 * <p>{@code FlowReportThrottleTest} proves the ordering for {@code /flow}, and R3 genuinely does
 * inherit it: {@code ReportRateLimitConfig} binds the interceptor to the whole
 * {@code …/reports/**} base path, so a new endpoint under it is throttled by existing. But
 * "inherited by path binding" is a statement about a configuration file, and every R3 suite that
 * touches these endpoints runs with {@code app.rate-limit.enabled=false} — so before this class,
 * nothing failed if the interceptor's path pattern were narrowed to {@code /flow}, or if a later
 * slice registered its own mapping outside it. Both reviewers verified the ordering by reading
 * the config, which is exactly the situation a test is for.
 *
 * <p>The ordering is what makes the 429 safe to return to a stranger, so it is a security
 * property and not a performance detail: because the budget is spent in {@code preHandle}, before
 * {@code resolveProject}, the refusal is byte-identical for a project that exists, one that does
 * not, and one the caller merely cannot see. If the check ever moved into the service — after
 * resolution, which is where somebody "tidying" would put it — then probing ids would become
 * free, and an id-guessing scan could spend this server's connection pool without ever spending
 * its own budget.
 *
 * <p><strong>{@code /aging} is the sharper case than {@code /flow}</strong>, which is why it is
 * worth its own class rather than a fourth method over there: it takes no parameters at all, so
 * there is no window to be invalid and no validation that could plausibly produce a status code
 * first. Whatever answers here answered from the interceptor.
 *
 * <p>Properties are byte-identical to {@code FlowReportThrottleTest}'s on purpose — same
 * annotations and same property values mean Spring's context cache key matches, so this class
 * reuses that context instead of standing up a second one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AgingReportThrottleTest extends CycleTimeTestBase {

    /**
     * A non-member hammering {@code /aging} gets 404 until the budget runs out, then
     * <strong>429</strong> — and the 429 arrives without anything having looked up a workspace.
     */
    @Test
    void aNonMemberIsThrottledBeforeTenancyIsResolved() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        var foreign = "/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                      + "/reports/aging";

        // Three probes at a project I am not a member of. 404 every time — the tenancy contract
        // is untouched by the throttle — and each one still costs me a unit.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(foreign).header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isNotFound());
        }

        // The fourth is 429, not 404: the budget was already gone when the request arrived, and
        // the interceptor never learned whether the project exists.
        mockMvc.perform(get(foreign).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Byte-identical for a workspace and project that do not exist at all, so the 429 cannot
        // be read as "that one was real". This is the disclosure property the ordering buys.
        mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID()
                        + "/projects/" + UUID.randomUUID() + "/reports/aging")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isTooManyRequests());

        // And the probed project's own owner is served normally throughout: a scan against you
        // must never throttle you.
        getAging(theirs, theirs.token()).andExpect(status().isOk());
    }

    /**
     * The budget is one pot across the whole reports surface, not one per endpoint — so a caller
     * cannot triple their allowance by rotating between {@code /flow}, {@code /cycle-time} and
     * {@code /aging}. It is bound to the base path for exactly this reason, and every later
     * report on it inherits the budget rather than remembering to ask for one.
     */
    @Test
    void theBudgetIsSharedAcrossEveryReportOnTheBasePath() throws Exception {
        var ctx = newProject();

        getAging(ctx, ctx.token()).andExpect(status().isOk());
        getCycleTime(ctx, ctx.token(), "?from=2025-03-01&to=2025-03-05").andExpect(status().isOk());
        getFlow(ctx, ctx.token(), "?from=2025-03-01&to=2025-03-05").andExpect(status().isOk());

        getAging(ctx, ctx.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
