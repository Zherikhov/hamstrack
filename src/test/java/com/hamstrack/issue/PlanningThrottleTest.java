package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The planning surface has a budget (HD-174).
 *
 * <p>Until this landed, the largest single response this product produces answered with no budget
 * of any kind — and that was not a decision anybody made, only what the surface happened to have.
 * The argument that spared it was that a planning response is bounded by
 * {@code MAX_PLANNING_VIEW_ROWS}; "bounded" is not "small" (20 000 assembled issues in one unpaged
 * response, the same number as {@code app.reports.max-rows}), and the budget is earned by the
 * grouped stats query being <em>unconditional and cap-blind</em> rather than by response size.
 *
 * <p>Several things are pinned here, and the first is the one that would fail silently.
 * {@code PLANNING_PATH} ends in {@code /**}, which in a {@code PathPattern} matches zero or more
 * trailing segments — the reading that makes it cover the bare {@code GET …/backlog} as well as its
 * sections. If that reading were wrong nothing would break: the aggregate would simply keep working
 * unbudgeted, exactly as it did before this file existed. Hence a test on the bare path.
 *
 * <p>And, as everywhere else on a throttled surface, the budget is spent in {@code preHandle} —
 * <strong>before</strong> the workspace is resolved — which is what makes the 429 safe to return to
 * a stranger: byte-identical for a workspace that exists, one that does not, and one the caller
 * merely cannot see. Never re-key either control on a workspace, a project or a sprint.
 *
 * <p>The occupancy half of HD-174 is not tested here — it is
 * {@code PlanningConcurrencyTest} — and that split is deliberate: the two controls protect
 * different resources and answer different refusals, so a file that mixed them would have to
 * disable one to observe the other.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.planning.requests-per-minute=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PlanningThrottleTest extends LabelTestBase {

    /**
     * AC-1 and AC-2. The bare aggregate path is inside the pattern, the budget is per principal,
     * and it is spent before tenancy — so a scan against somebody else's workspace exhausts the
     * scanner's own allowance and nobody else's.
     */
    @Test
    void theAggregateIsThrottledAndTheRefusalArrivesBeforeTenancy() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        // Three planning reads against a workspace I am not a member of: 404 every time, and each
        // still costs me a unit of my own budget.
        for (int i = 0; i < 3; i++) {
            backlog(theirs.wsId(), theirs.projectId(), mine.token())
                    .andExpect(status().isNotFound());
        }

        backlog(theirs.wsId(), theirs.projectId(), mine.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Identical for a workspace that does not exist at all — the refusal answers no existence
        // question, which is the property that makes spending it in preHandle safe.
        backlog(UUID.randomUUID(), UUID.randomUUID(), mine.token())
                .andExpect(status().isTooManyRequests());

        // AC-2: a scan against you must never throttle you.
        backlog(theirs.wsId(), theirs.projectId(), theirs.token()).andExpect(status().isOk());
    }

    /**
     * <strong>One pot for the whole planning surface</strong>, which is the behavioural half of the
     * parity property {@code PlanningThrottleParityTest} holds structurally: a client cannot double
     * its allowance by alternating between the aggregate and a section refresh, and — the direction
     * that actually matters — a client refused on the cheap section fetch cannot buy itself the
     * expensive aggregate instead.
     */
    @Test
    void theSectionReadsSpendTheSameBudgetAsTheAggregate() throws Exception {
        var ctx = newProject();

        backlog(ctx.wsId(), ctx.projectId(), ctx.token()).andExpect(status().isOk());
        backlogSection(ctx.wsId(), ctx.projectId(), ctx.token()).andExpect(status().isOk());
        backlogSection(ctx.wsId(), ctx.projectId(), ctx.token()).andExpect(status().isOk());

        // A separate pot would let this fourth request through.
        backlog(ctx.wsId(), ctx.projectId(), ctx.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * AC-4 — <strong>past the budget the answer is a refusal, never a narrowed view.</strong>
     *
     * <p>Stated as its own test because the alternative is a plausible-looking "graceful
     * degradation" somebody could add later: a smaller {@code sectionCap}, a dropped section, a
     * truncated list. The {@code sectionCap}/{@code truncated}/{@code totalAvailable} protocol is a
     * statement about the DATA, and a throttle must not become the thing that changes it — a client
     * that cannot tell a budget refusal from a small project is a client that will draw the wrong
     * board.
     */
    @Test
    void aRefusedPlanningReadIsNeverANarrowedView() throws Exception {
        var ctx = newProject();

        backlog(ctx.wsId(), ctx.projectId(), ctx.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionCap").exists())
                .andExpect(jsonPath("$.backlog").exists());
        backlog(ctx.wsId(), ctx.projectId(), ctx.token()).andExpect(status().isOk());
        backlog(ctx.wsId(), ctx.projectId(), ctx.token()).andExpect(status().isOk());

        var refused = backlog(ctx.wsId(), ctx.projectId(), ctx.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.backlog").doesNotExist())
                .andExpect(jsonPath("$.sectionCap").doesNotExist())
                .andReturn();

        var retryAfter = Integer.parseInt(refused.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter)
                .as("Retry-After is the seconds until the caller's own minute window rolls, so it "
                    + "is inside (0, 60] — a bigger number would be a different mechanism and a "
                    + "client would wait for it")
                .isBetween(1, 60);

        // The refusal carries no errorType: clients discriminate the two OCCUPANCY refusals from a
        // budget refusal on its presence, exactly as they do on the reports and search surfaces.
        assertThat(refused.getResponse().getContentAsString())
                .as("the budget 429 must not grow an errorType — its absence is how a client tells "
                    + "'you asked too often' from TOO_MANY_IN_FLIGHT / EXPENSIVE_SURFACE_BUSY, and "
                    + "the two call for different client behaviour")
                .doesNotContain("errorType\":\"");
    }

    private ResultActions backlog(UUID wsId, UUID projectId, String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + wsId + "/projects/" + projectId + "/backlog")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions backlogSection(UUID wsId, UUID projectId, String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + wsId + "/projects/" + projectId
                                   + "/backlog/sections/backlog")
                .header("Authorization", "Bearer " + token));
    }
}
