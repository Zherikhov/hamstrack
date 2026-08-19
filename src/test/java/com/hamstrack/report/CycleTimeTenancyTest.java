package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The project's top bug class, applied to R3's two endpoints (§4.1, §12 "Tenancy").
 *
 * <p>{@code FlowReportTenancyTest} makes the same assertions for R1, and the duplication is
 * deliberate: the rule is enforced per <em>endpoint</em>, not per package, and the way a report
 * surface leaks is that the sixth one on the base path forgets what the first five did. A report
 * is also the quietest possible leak — no title, no key, no id, just a number that is silently
 * somebody else's — so there is no symptom to notice at runtime.
 *
 * <p>R3 adds one case R1 could not have: {@code /aging} takes no parameters at all, so it is the
 * proof that the 404 comes from resolution rather than from validation happening to reject
 * something first.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CycleTimeTenancyTest extends CycleTimeTestBase {

    @Test
    void anonymousIsUnauthorized() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get(reportsBase(ctx) + "/cycle-time")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(reportsBase(ctx) + "/aging")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownWorkspaceOrProjectIsNotFound() throws Exception {
        var ctx = newProject();

        for (var report : new String[]{"cycle-time", "aging"}) {
            mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID()
                            + "/projects/" + ctx.projectId() + "/reports/" + report)
                            .header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/workspaces/" + ctx.wsId()
                            + "/projects/" + UUID.randomUUID() + "/reports/" + report)
                            .header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void anotherTenantsProjectIsNotFoundBothWaysRound() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        for (var report : new String[]{"cycle-time", "aging"}) {
            // Their project id spliced into MY workspace path: the project exists, but not inside
            // a workspace I am a member of.
            mockMvc.perform(get("/api/workspaces/" + mine.wsId()
                            + "/projects/" + theirs.projectId() + "/reports/" + report)
                            .header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isNotFound());

            // Their workspace, their project, my token: not a member. 404, never 403.
            mockMvc.perform(get("/api/workspaces/" + theirs.wsId()
                            + "/projects/" + theirs.projectId() + "/reports/" + report)
                            .header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The ordering rule. A 400 here would tell an outsider that the project exists — cheaply,
     * repeatably, and without ever authenticating as anyone entitled to know.
     */
    @Test
    void aNonMemberGetsNotFoundEvenWhenTheWindowIsAlsoInvalid() throws Exception {
        var mine = newProject();
        var theirs = newProject();
        var badWindow = "/reports/cycle-time?from=2025-12-31&to=2025-01-01";

        mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                        + badWindow).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // The same request from a member is the 400 it deserves — which proves the 404 above came
        // from the tenancy check and not from validation accidentally producing the right code.
        mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                        + badWindow).header("Authorization", "Bearer " + theirs.token()))
                .andExpect(status().isBadRequest());
    }

    /**
     * Same workspace, same owner, different project — and neither report mixes them.
     *
     * <p>The tail of this test is the <strong>filter-versus-subject</strong> rule, asserted with
     * the ids that make it a claim rather than a tautology (round 2, item 6). A filter id is
     * never resolved: it is applied as an equality inside an already project-scoped statement,
     * where it can only subtract. {@code CycleTimeReportApiTest} shows that with a
     * {@code UUID.randomUUID()}, which proves the behaviour but not the interesting half of the
     * claim — a random id matches nothing anywhere, so an unscoped implementation would pass it
     * too. The ids used here are <em>real rows a foreign statement would happily join to</em>:
     * one from a sibling project in the same workspace, one from a different tenant entirely.
     * Behaviourally identical by construction, but that identity is the whole assertion.
     *
     * <p><strong>{@code componentId} rather than {@code typeId}, and that is not
     * interchangeable.</strong> Review proposed the sibling's issue type; issue types are
     * <em>workspace</em>-scoped taxonomy ({@code issue_types.scope_workspace_id}), so a sibling
     * project in the same workspace offers the very same type rows under the very same ids —
     * "the sibling's type" is also this project's type, the filter would match, and the test
     * would assert the opposite of what it says. Components are the project-scoped one
     * ({@code components.project_id NOT NULL}), so a sibling's component is a genuinely foreign
     * real id, which is what the case needs.
     */
    @Test
    void aSiblingProjectsIssuesAreNotCounted() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);

        completed(ctx, "mine", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z", "2025-03-03T00:00:00Z");
        for (int day = 1; day <= 6; day++) {
            completed(sibling, "theirs " + day, "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z", "2025-03-0" + (day + 1) + "T00:00:00Z");
        }
        inProgressSince(sibling, "their open one", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var cycle = cycleTime(ctx, "?from=2025-03-01&to=2025-03-31");
        assertThat(cycle.get("items")).hasSize(1);
        assertThat(cycle.get("sampleSize").asLong()).isEqualTo(1);
        assertThat(cycle.get("percentiles").get("cycle").get("p50").isNull())
                .as("the sibling's six completed issues must not lift this project over the "
                    + "five-sample threshold")
                .isTrue();

        var aging = aging(ctx);
        assertThat(aging.get("meta").get("basedOnIssues").asLong()).isZero();
        assertThat(aging.get("percentiles").get("p50").isNull())
                .as("the reference lines are the PROJECT's finished work, not the workspace's")
                .isTrue();
        for (var column : aging.get("columns")) {
            assertThat(column.get("items")).isEmpty();
        }

        // A REAL component id belonging to the sibling project, and another belonging to a
        // different tenant altogether. Both only subtract: the statement is already scoped to
        // this project, so a foreign filter can narrow it to nothing and can never widen it.
        var foreignIds = new java.util.LinkedHashMap<String, java.util.UUID>();
        foreignIds.put("a sibling project's component", createComponent(sibling, "Sibling area"));
        foreignIds.put("another tenant's component", createComponent(newProject(), "Foreign area"));

        foreignIds.forEach((what, componentId) -> {
            try {
                var filtered = cycleTime(ctx, "?from=2025-03-01&to=2025-03-31&componentId="
                                              + componentId);
                assertThat(filtered.get("items"))
                        .as("%s is a filter, not a subject: it may narrow this project's report "
                            + "to nothing, and must never reach across into its own project's "
                            + "rows", what)
                        .isEmpty();
                assertThat(filtered.get("sampleSize").asLong()).isZero();
                assertThat(filtered.get("meta").get("unmatchedFilters").toString())
                        .as("an empty chart caused by %s must say so, or the reader cannot tell "
                            + "it from 'nothing finished'", what)
                        .contains("componentId");
            } catch (Exception e) {
                throw new AssertionError("filtering by " + what + " failed", e);
            }
        });
    }
}
