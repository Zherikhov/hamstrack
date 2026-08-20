package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-28 R1 round 2, item 1 — <strong>the reports surface has a budget</strong>.
 *
 * <p>Before this existed the endpoint was bounded by {@code app.reports.max-window-days}
 * alone, and that bounds the <em>response array</em>, not the work: the opening balance is
 * O(project history) by design, {@code Cache-Control: private} means no shared cache ever
 * absorbs a repeat, and the auth rate limiter is registered on six explicit auth URLs and
 * skips every non-POST. So one authenticated member in a loop could saturate the connection
 * pool with perfectly legal 200s. This is the same shape as the per-project cooldown on the
 * backlog-rank rebalance ({@code IssueRankService}) and answers the same way: a
 * <strong>429 with {@code Retry-After}</strong>, from the shared {@code RateLimitedException}
 * envelope, so a client can back off instead of hammering.
 *
 * <p><strong>Per principal, not per project</strong> — asserted below, because the difference
 * is load-bearing twice over. It is the correct unit (the cost is paid by whoever asks, and a
 * per-project key would let one user's dashboard tab throttle a colleague), and it keeps the
 * refusal free of tenancy information: the limiter runs before the controller resolves the
 * project, so a per-project key would answer differently for a project that exists.
 *
 * <p>{@code app.rate-limit.enabled} is the master switch (it is what every other suite here
 * turns off), so it is left ON and only the auth-IP budget is raised — otherwise the
 * register/login POSTs of the fixtures would spend the auth budget instead.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class FlowReportThrottleTest extends FlowReportTestBase {

    private static final String WINDOW = "?from=2025-03-01&to=2025-03-05";

    @Test
    void aMemberPastTheBudgetGetsA429WithRetryAfter() throws Exception {
        var ctx = newProject();

        for (int i = 0; i < 3; i++) {
            getFlow(ctx, ctx.token(), WINDOW).andExpect(status().isOk());
        }

        getFlow(ctx, ctx.token(), WINDOW)
                .andExpect(status().isTooManyRequests())
                // The shared 429 envelope: a client that can't see Retry-After can only guess.
                .andExpect(header().exists("Retry-After"))
                // And the detail says what is throttled — the generic "too many requests"
                // would leave a user staring at a chart that just stopped loading.
                .andExpect(jsonPath("$.detail", containsString("report")));
    }

    /**
     * The budget is the caller's, not the project's. A second member reading the same
     * project's report is unaffected by the first one's spending — otherwise one open
     * dashboard tab would be a denial of service against every colleague, which is a worse
     * failure than the one the throttle exists to prevent.
     */
    @Test
    void theBudgetIsPerPrincipalNotPerProject() throws Exception {
        var ctx = newProject();
        var other = actorWith(ctx, "MEMBER", "MEMBER");

        for (int i = 0; i < 3; i++) {
            getFlow(ctx, ctx.token(), WINDOW).andExpect(status().isOk());
        }
        getFlow(ctx, ctx.token(), WINDOW).andExpect(status().isTooManyRequests());

        getFlow(ctx, other.token(), WINDOW)
                .andExpect(status().isOk());
    }

    /**
     * <strong>The budget is spent BEFORE tenancy is resolved, and that is deliberate.</strong>
     *
     * <p>The limiter is a {@code HandlerInterceptor} bound to the reports base path, so it runs
     * in {@code preHandle} — ahead of the controller, ahead of {@code resolveProject}. An
     * outsider hammering project ids therefore gets 404, 404, 404, then <strong>429</strong>,
     * and the 429 is the point: if the budget were only spent on requests that survived
     * tenancy resolution, the cheapest way to spend this server's connection pool would be to
     * ask about projects you cannot see, which is exactly what an id-guessing scan does.
     *
     * <p>It discloses nothing. The key is the caller, so the refusal is byte-identical for a
     * real project, a nonexistent one and somebody else's — there is no id in it, no timing
     * difference that depends on existence, and a member of the project being probed is
     * unaffected. The tenancy contract is untouched: every request under the budget still
     * answers 404.
     *
     * <p>Not covered by {@link #theBudgetIsPerPrincipalNotPerProject()}, which proves the key
     * is the caller but spends the budget on a project the caller CAN see. This one proves the
     * ordering, and it is the assertion that fails the moment somebody "tidies" the interceptor
     * into a check inside {@code FlowReportService} after the resolution.
     */
    @Test
    void theBudgetIsSpentBeforeTenancyIsResolved() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        var foreign = "/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                + "/reports/flow" + WINDOW;

        // Three probes at a project I am not a member of: 404 every time, and each one
        // still costs me a unit — the interceptor never learns whether the project exists.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(foreign).header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isNotFound());
        }

        // The fourth is 429, NOT 404. The budget is gone, and it was gone before anything
        // looked up a workspace.
        mockMvc.perform(get(foreign).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // And the 429 is byte-identical for a project that does not exist at all, so it
        // cannot be read as "this one was real" — nothing about tenancy leaks through it.
        mockMvc.perform(get("/api/workspaces/" + java.util.UUID.randomUUID()
                        + "/projects/" + java.util.UUID.randomUUID() + "/reports/flow" + WINDOW)
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isTooManyRequests());

        // Meanwhile the project's own owner — whose budget the outsider cannot touch — is
        // served normally. A probe against you must not throttle you.
        getFlow(theirs, theirs.token(), WINDOW).andExpect(status().isOk());
    }
}
