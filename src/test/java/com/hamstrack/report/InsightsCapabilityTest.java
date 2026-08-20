package com.hamstrack.report;

import com.hamstrack.project.entity.BoardMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Suggestions narrow; resolution never does</strong> (delivery-paths §5.1 Rule A,
 * reports-proposal §2.6), applied to the Insights panel.
 *
 * <p>Two halves, and the second is the one that has to hold under pressure. {@code /search/schema}
 * omits the {@code SPRINT} dimension and the {@code POINTS} measure when no visible project has
 * the capability — that is autocomplete vocabulary. The endpoint accepts them regardless, with a
 * 200 and real numbers. <strong>A capability may never change a status code</strong>: a hidden
 * control is not a permission, and a panel state saved beside a filter must not start 4xx-ing
 * because a colleague flipped a project toggle.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InsightsCapabilityTest extends InsightsTestBase {

    @Test
    void aKanbanNoEstimationWorkspaceIsOfferedNeitherSprintNorPoints() throws Exception {
        var ctx = newProject();   // defaults: KANBAN, estimation off

        schema(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.dimensions",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("SPRINT"))))
                .andExpect(jsonPath("$.insights.measures",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("POINTS"))))
                // Everything that belongs to no capability is always offered.
                .andExpect(jsonPath("$.insights.dimensions",
                        org.hamcrest.Matchers.hasItems("STATUS", "TYPE", "PRIORITY", "ASSIGNEE",
                                "COMPONENT", "LABEL", "PROJECT")))
                .andExpect(jsonPath("$.insights.measures",
                        org.hamcrest.Matchers.hasItems("COUNT", "NONE")));
    }

    @Test
    void oneCapableProjectIsEnoughToOfferItToTheWholeWorkspace() throws Exception {
        var ctx = newProject();
        capabilities(ctx.projectId(), BoardMode.SCRUM, true);

        schema(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights.dimensions",
                        org.hamcrest.Matchers.hasItem("SPRINT")))
                .andExpect(jsonPath("$.insights.measures",
                        org.hamcrest.Matchers.hasItem("POINTS")));
    }

    /**
     * The half that matters. A dimension the UI hides still RESOLVES — 200 with a real breakdown,
     * not a 400, not a 404, not a silently empty body.
     */
    @Test
    void aHiddenSliceStillResolves() throws Exception {
        var ctx = newProject();   // KANBAN: SPRINT is not offered
        createIssue(ctx, "Task", "backlog work");

        var body = insightsBody(ctx, "", "SPRINT", null);

        assertThat(body.get("slice").asText()).isEqualTo("SPRINT");
        // One bar: "in no sprint", which is the honest answer for a Kanban project.
        assertThat(body.get("slices")).hasSize(1);
        assertThat(sliceNamed(body, null).get("count").asLong()).isEqualTo(1);
        assertThat(sliceNamed(body, null).get("bucket").get("hql").asText()).isEqualTo("sprint IS EMPTY");
    }

    /**
     * And a hidden MEASURE still resolves, over data that exists even though the capability that
     * produces it is off (Rule B: the toggle hides the input, not the data).
     */
    @Test
    void aHiddenMeasureStillResolvesOverPointsThatAlreadyExist() throws Exception {
        var ctx = newProject();   // estimation off
        createIssue(ctx, "Task", "estimated anyway", withStatus(ctx, "In Progress"), points("8"));

        var body = json.readTree(insights(ctx.wsId(), ctx.token(), "", "POINTS", "STATUS", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("measure").asText()).isEqualTo("POINTS");
        assertThat(sliceNamed(body, "In Progress").get("points").asDouble()).isEqualTo(8.0);
    }

    // ------------------------------------------------------------------ plumbing

    private org.springframework.test.web.servlet.ResultActions schema(Ctx ctx) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                .header("Authorization", "Bearer " + ctx.token()));
    }

    private void capabilities(UUID projectId, BoardMode board, boolean estimation) {
        var project = projectRepository.findById(projectId).orElseThrow();
        project.setBoardMode(board);
        project.setEstimationEnabled(estimation);
        projectRepository.save(project);
    }
}
