package com.hamstrack.search;

import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The search surface has a budget (HD-140 R6 round 2, security item 12).
 *
 * <p>Until R6 the application had two limiters — six literal auth URLs and the reports interceptor
 * — and {@code POST …/search}, {@code GET …/search/schema} and {@code GET …/search/suggest} were
 * under neither. Search only looks like the cheap surface: a query may carry up to
 * {@code HqlParser.MAX_PREDICATES} leaves, a {@code text ~ "…"} leaf compiles to two unanchored
 * unindexable {@code LIKE}s over a TEXT column, and the endpoint runs the whole predicate
 * <strong>twice</strong> (count + page). So the throttled Insights panel was the narrow door, and
 * the wide one next to it stood open.
 *
 * <p>Several things are pinned here, and the first is the one that would fail silently.
 * {@code SEARCH_PATH} ends in {@code /**}, which in a {@code PathPattern} matches zero or more
 * trailing segments — the reading that makes it cover {@code POST …/search} itself and not merely
 * its sub-paths. If that reading were wrong nothing would break: search would simply keep working,
 * unthrottled, exactly as it did before this file existed. Hence a test on the bare path.
 *
 * <p>And, as everywhere else on a throttled surface, the budget is spent in {@code preHandle} —
 * <strong>before</strong> the workspace is resolved — which is what makes the 429 safe to return to
 * a stranger: byte-identical for a workspace that exists, one that does not, and one the caller
 * merely cannot see.
 *
 * <p>Extends {@code LabelTestBase} because it needs what that base already builds through the
 * public API — a workspace, a project and a member with a token — and nothing else.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.search.requests-per-minute=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SearchThrottleTest extends LabelTestBase {

    @Test
    void thePlainSearchPostIsThrottledAndTheRefusalArrivesBeforeTenancy() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        // Three searches against a workspace I am not a member of: 404 every time, and each still
        // costs me a unit of my own budget.
        for (int i = 0; i < 3; i++) {
            search(theirs.wsId(), mine.token()).andExpect(status().isNotFound());
        }

        search(theirs.wsId(), mine.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Identical for a workspace that does not exist at all.
        search(UUID.randomUUID(), mine.token()).andExpect(status().isTooManyRequests());

        // A scan against you must never throttle you.
        search(theirs.wsId(), theirs.token()).andExpect(status().isOk());
    }

    /**
     * One pot for the whole surface: the schema read, the typeahead and the search itself all spend
     * it, so a client cannot triple its allowance by rotating between them.
     */
    @Test
    void schemaAndSuggestSpendTheSameBudgetAsSearch() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", "assignee")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
        search(ctx.wsId(), ctx.token()).andExpect(status().isOk());

        search(ctx.wsId(), ctx.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * Insights sits under {@code /search/**} too, so it spends this budget as well as the reports
     * one — deliberately, and documented at both patterns. The lower configured value binds (not
     * "the tighter, i.e. reports, one": that identification survives only while the defaults stay
     * 60 and 120), which is the only reading under which "the panel is a report that happens to live on the search path"
     * cannot be used to buy a bigger allowance from either surface.
     */
    @Test
    void theInsightsPanelSpendsTheSearchBudgetToo() throws Exception {
        var ctx = newProject();

        search(ctx.wsId(), ctx.token()).andExpect(status().isOk());
        search(ctx.wsId(), ctx.token()).andExpect(status().isOk());
        insights(ctx.wsId(), ctx.token(), "STATUS").andExpect(status().isOk());

        insights(ctx.wsId(), ctx.token(), "STATUS")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * <strong>Saved filters spend the SEARCH pot — not a third one of their own</strong> (HD-140
     * R6 round 4, item 4).
     *
     * <p>{@code ThrottleCoverageTest} asks the handler mapping whether the filter handlers have
     * <em>some</em> throttle in front of them, and that is all it asks. So a future
     * {@code FilterRateLimiter} with its own budget would leave the whole suite green while making
     * "a filter is a saved search, charged to the search budget" false in eight documents at once
     * — the property everything from {@code SearchRateLimitConfig.FILTERS_PATH} to
     * {@code docs/self-hosting.md} states is the shared pot, and nothing was asserting the sharing.
     *
     * <p>Both directions are pinned, because a one-way test still passes if the two surfaces
     * merely happen to be sized alike: a filter write consumes the search allowance, and an
     * exhausted search allowance refuses a filter write. At
     * {@code app.search.requests-per-minute=3} a separate pot would let the fourth search through.
     */
    @Test
    void savedFilterWritesSpendTheSearchBudget() throws Exception {
        var ctx = newProject();

        search(ctx.wsId(), ctx.token()).andExpect(status().isOk());
        search(ctx.wsId(), ctx.token()).andExpect(status().isOk());
        // The third unit of the SEARCH budget, spent by a filter create.
        createFilter(ctx.wsId(), ctx.token(), "Everything").andExpect(status().isCreated());

        search(ctx.wsId(), ctx.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // …and the exhausted search budget refuses the filter surface too — one pot, both ways.
        createFilter(ctx.wsId(), ctx.token(), "Anything else")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private ResultActions createFilter(UUID wsId, String token, String name) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/filters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"hql\":\"\"}"));
    }

    private ResultActions search(UUID wsId, String token) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\"}"));
    }

    private ResultActions insights(UUID wsId, String token, String slice) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search/insights")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\",\"slice\":\"" + slice + "\"}"));
    }
}
