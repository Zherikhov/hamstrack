package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The project's top bug class, applied to R5 (§4.1, §12 "Tenancy").
 *
 * <p>A suite of its own rather than three more lines in {@code SprintReportTenancyTest}, because
 * velocity's exposure is a different shape from R4's and the difference is the whole slice.
 *
 * <p>R4 reads <strong>one</strong> sprint that a caller named, so its tenancy risk is a
 * caller-supplied id (answered by {@code findByIdAndProject}, 404 either way). R5 names no sprint at
 * all: it <em>sweeps</em> the ledger across up to twelve of them, driving from
 * {@code sprint_scope_events} with {@code workspace_id} as the driving predicate. That is exactly
 * the statement shape R4's review found the query-scope guard blind to — a scoped {@code JOIN}
 * hanging off an unscoped driver — and it is why the guard's driving-table rule was added a slice
 * early, with this sweep as its hostile fixture.
 *
 * <p>So the test that carries this file is {@link #aSiblingProjectsSprintsAreNotInThisChart()}: a
 * workspace-scoped sweep is a complete <em>tenant</em> scope and not a <em>project</em> scope, and
 * the only thing making it project-safe is that the sprint ids were resolved through the project.
 * Two projects in one workspace is the case that proves it — and a leak there would render as an
 * extra bar and a moved forecast band, with no error and nothing else to notice.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VelocityTenancyTest extends SprintReportTestBase {

    @Test
    void anonymousIsUnauthorized() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get(reportsBase(ctx) + "/velocity"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownWorkspaceOrProjectIsNotFound() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID()
                        + "/projects/" + ctx.projectId() + "/reports/velocity")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId()
                        + "/projects/" + UUID.randomUUID() + "/reports/velocity")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsProjectIsNotFoundBothWaysRound() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        // Their project id spliced into MY workspace path.
        mockMvc.perform(get("/api/workspaces/" + mine.wsId()
                        + "/projects/" + theirs.projectId() + "/reports/velocity")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // Their workspace, their project, my token: not a member. 404, never 403.
        mockMvc.perform(get("/api/workspaces/" + theirs.wsId()
                        + "/projects/" + theirs.projectId() + "/reports/velocity")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());
    }

    /**
     * <strong>The case this suite exists for.</strong> Two projects, one workspace, one owner — the
     * sweep's {@code workspace_id} predicate is satisfied by both, so nothing but the resolved
     * sprint set keeps them apart. A leak here is silent: an extra bar, a band computed from
     * somebody else's sprints, and a chart that renders perfectly.
     */
    @Test
    void aSiblingProjectsSprintsAreNotInThisChart() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        completedSprint(ctx, "mine", "2025-01-06T00:00:00Z", "2025-01-17T10:00:00Z", 2, 2, null);
        completedSprint(sibling, "theirs", "2025-01-20T00:00:00Z", "2025-01-31T10:00:00Z",
                3, 3, null);

        var report = velocity(ctx, null);

        assertThat(barNames(report))
                .as("a workspace-scoped sweep is a tenant scope, not a project scope; the sprint "
                    + "ids are what make it project-safe, and they are resolved through the project")
                .containsExactly("mine");
        assertThat(report.get("meta").get("basedOnIssues").asInt())
                .as("meta is swept from the same ledger and leaks the same way if it is not scoped "
                    + "with it")
                .isEqualTo(2);
        assertThat(report.get("forecast").get("sampleSize").asInt())
                .as("and the band is computed from the sample, so a leaked sprint moves the "
                    + "forecast as well as the chart")
                .isEqualTo(1);

        // ...and symmetrically, so the assertion above is not passing because the sibling's data
        // simply is not there.
        assertThat(barNames(velocity(sibling, null))).containsExactly("theirs");
    }

    /**
     * The ordering rule: tenancy resolves before any parameter is judged. A non-member sending a
     * malformed {@code sprints} to a project they cannot see gets the 404, not the 400 — a 400
     * would confirm the project exists.
     */
    @Test
    void aNonMemberGetsNotFoundRatherThanTheBadRequestTheParameterEarned() throws Exception {
        var mine = newProject();
        var theirs = newProject();
        var path = "/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                   + "/reports/velocity?sprints=99";

        mockMvc.perform(get(path).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // The same request from a member IS the 400 — which proves the 404 above came from the
        // tenancy check rather than from the parameter.
        mockMvc.perform(get(path).header("Authorization", "Bearer " + theirs.token()))
                .andExpect(status().isBadRequest());
    }
}
