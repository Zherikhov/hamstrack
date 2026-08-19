package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The project's top bug class, applied to R4's two endpoints (§4.1, §12 "Tenancy").
 *
 * <p>{@code FlowReportTenancyTest} and {@code CycleTimeTenancyTest} make the same assertions for R1
 * and R3, and the duplication is deliberate: the rule is enforced per <em>endpoint</em>, and the way
 * a report surface leaks is that the sixth one on the base path forgets what the first five did.
 *
 * <p>R4 adds a case the earlier slices could not have: a <strong>second</strong> resolution. These
 * endpoints resolve a project and then a sprint inside it, so there are two places to lose the
 * scope, and the second one takes a caller-supplied id. A sprint is the report's SUBJECT, so unlike
 * a filter it resolves and 404s — which means "404" here has to be proven to come from resolution
 * rather than from the sprint simply having no ledger rows to leak.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintReportTenancyTest extends SprintReportTestBase {

    private static final String[] REPORTS = {"sprint-burnup", "sprint-review"};

    @Test
    void anonymousIsUnauthorized() throws Exception {
        var ctx = newProject();

        for (var report : REPORTS) {
            mockMvc.perform(get(reportsBase(ctx) + "/" + report))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anUnknownWorkspaceOrProjectIsNotFound() throws Exception {
        var ctx = newProject();

        for (var report : REPORTS) {
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

        for (var report : REPORTS) {
            // Their project id spliced into MY workspace path.
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
     * A sprint belonging to another tenant, named by a member of this one — the shape unique to
     * these two endpoints, and the one a naive implementation answers 200 to because
     * {@code sprint_id} looks like scope enough on its own.
     *
     * <p>Both directions are checked, and the second is the interesting one: my own sprint id sent
     * against THEIR project must not resolve either, or a sprint would be addressable through any
     * project the caller can see.
     */
    @Test
    void aForeignSprintIsNotFoundFromEitherEnd() throws Exception {
        var mine = newProject();
        var theirs = newProject();
        var mySprint = startedSprint(mine, "mine");
        var theirSprint = startedSprint(theirs, "theirs");

        for (var report : REPORTS) {
            mockMvc.perform(get(reportsBase(mine) + "/" + report + "?sprintId=" + theirSprint)
                            .header("Authorization", "Bearer " + mine.token()))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/projects/"
                            + theirs.projectId() + "/reports/" + report + "?sprintId=" + mySprint)
                            .header("Authorization", "Bearer " + theirs.token()))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The ordering rule: resolution happens before anything else is looked at, so a non-member
     * naming a real sprint of the project they cannot see gets the same 404 as one naming nothing.
     * A 403 — or a 200 with an empty body — would confirm the project exists.
     */
    @Test
    void aNonMemberGetsNotFoundEvenWhenTheSprintIdIsReal() throws Exception {
        var mine = newProject();
        var theirs = newProject();
        var theirSprint = startedSprint(theirs, "theirs");
        var path = "/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                   + "/reports/sprint-burnup?sprintId=" + theirSprint;

        mockMvc.perform(get(path).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // The same request from a member is a 200 — which proves the 404 above came from the
        // tenancy check and not from the sprint being unreadable for some other reason.
        mockMvc.perform(get(path).header("Authorization", "Bearer " + theirs.token()))
                .andExpect(status().isOk());
    }

    /**
     * A sibling project in the SAME workspace: same tenant, same owner, and still not the same
     * sprint. This is the case a {@code workspace_id}-only scope would pass.
     */
    @Test
    void aSiblingProjectsSprintAndIssuesAreNotCounted() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var mine = createIssue(ctx, "mine");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(mine)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        var theirIssue = createIssue(sibling, "theirs");
        var theirSprint = createSprint(sibling, "S");
        addIssuesToSprint(sibling, sibling.token(), theirSprint, idOf(theirIssue))
                .andExpect(status().isOk());
        startSprint(sibling, sibling.token(), theirSprint).andExpect(status().isOk());

        getBurnup(ctx, ctx.token(), "?sprintId=" + theirSprint).andExpect(status().isNotFound());

        var report = review(ctx, "?sprintId=" + sprint);
        assertThat(listKeys(report, "committed")).containsExactly(mine.get("key").asText());
        assertThat(report.get("meta").get("basedOnIssues").asInt()).isEqualTo(1);
    }
}
