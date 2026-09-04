package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §3.1 / §4.9 — the tenant boundary around sprints, the project's top bug class.
 *
 * <p>The two-code rule this pins down: a foreign sprint id is a <strong>404</strong>
 * only when it is the <em>subject</em> of the request ({@code GET /sprints/{id}}), and a
 * <strong>422 "Unknown sprint"</strong> when it is a <em>field value</em> inside a
 * request the caller is entitled to make (create/update/rank/complete). Neither leaks
 * whether the id exists; both are reached through {@code findByIdAndProject}, never a
 * bare {@code findById}. A non-member is always a 404, never a 403.
 *
 * <p>Cross-<em>project</em> isolation inside ONE workspace is tested alongside
 * cross-<em>tenant</em> isolation on purpose: the composite FK proves "same workspace",
 * so "same project" is a service-level invariant that only tests can hold in place.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintTenancyTest extends SprintTestBase {

    @Test
    void aForeignSprintIdIs404AsASubjectAnd422AsAFieldValue() throws Exception {
        var ctx = newProject();
        var otherTenant = newProject();
        var theirSprint = createSprint(otherTenant, "Their sprint");
        var sibling = siblingProject(ctx);
        var siblingSprint = createSprint(sibling, "Sibling sprint");
        var issue = createIssue(ctx, "ours");

        // ---- subject of the request → 404 ----
        getSprint(ctx, ctx.token(), theirSprint).andExpect(status().isNotFound());
        getSprint(ctx, ctx.token(), siblingSprint).andExpect(status().isNotFound());
        patchSprint(ctx, ctx.token(), theirSprint, "{\"name\":\"stolen\"}")
                .andExpect(status().isNotFound());
        startSprint(ctx, ctx.token(), theirSprint).andExpect(status().isNotFound());
        deleteSprint(ctx, ctx.token(), theirSprint, true).andExpect(status().isNotFound());
        addIssuesToSprint(ctx, ctx.token(), theirSprint, idOf(issue)).andExpect(status().isNotFound());

        // ---- field value inside a legitimate request → 422 "Unknown sprint" ----
        postIssue(ctx, ctx.token(), "filed into their sprint", "\"sprintId\":\"" + theirSprint + "\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));
        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + theirSprint + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));
        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + siblingSprint + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));
        rank(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + theirSprint + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));
        rank(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent());

        // Nothing stuck to the issue through any of those attempts.
        assertThat(sprintName(getIssue(ctx, numberOf(issue)))).as("a foreign sprint was assigned").isNull();
    }

    /** A foreign id in the LIST filter is not an error at all — it simply matches nothing. */
    @Test
    void aForeignSprintIdInTheListFilterMatchesNothingRatherThanLeaking() throws Exception {
        var ctx = newProject();
        var otherTenant = newProject();
        var theirSprint = createSprint(otherTenant, "Their sprint");
        var theirIssue = createIssue(otherTenant, "their work");
        addIssuesToSprint(otherTenant, otherTenant.token(), theirSprint, idOf(theirIssue))
                .andExpect(status().isOk());
        createIssue(ctx, "our work");

        var board = board(ctx, "?sprintId=" + theirSprint);
        assertThat(board.get("issues")).as("a foreign sprint filter returned rows: " + board).isEmpty();

        var paged = backlog(ctx, "&sprintId=" + theirSprint);
        assertThat(paged.get("content")).as("a foreign sprint filter returned rows: " + paged).isEmpty();
    }

    /** No sprint list and no planning view ever contains another tenant's row. */
    @Test
    void neitherTheSprintListNorThePlanningViewEverShowsAForeignRow() throws Exception {
        var ctx = newProject();
        var otherTenant = newProject();
        createSprint(otherTenant, "Their sprint");
        var sibling = siblingProject(ctx);
        createSprint(sibling, "Sibling sprint");
        var mine = createSprint(ctx, "Mine");

        var page = listSprints(ctx, ctx.token(), null);
        assertThat(page.get("content")).as("the list crossed a project/tenant boundary: " + page).hasSize(1);
        assertThat(page.get("content").get(0).get("id").asText()).as("%s", page).isEqualTo(mine.toString());

        var view = backlogView(ctx);
        assertThat(view.get("sprints")).as("the planning view crossed a boundary: " + view).hasSize(1);
        assertThat(view.get("sprints").get(0).get("sprint").get("id").asText()).as("%s", view).isEqualTo(mine.toString());
    }

    /** Bulk assignment resolves issue ids within the project too — a foreign one is a 422. */
    @Test
    void bulkAssignRefusesIssueIdsFromAnotherProject() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var sprintId = createSprint(ctx, "Sprint 1");
        var mine = createIssue(ctx, "mine");
        var theirs = createIssue(sibling, "theirs");

        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(mine), idOf(theirs))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown issue"));

        // …and the legitimate half of the batch did NOT sneak through.
        assertThat(sprintName(getIssue(ctx, numberOf(mine)))).as("a partially-rejected bulk move applied anyway").isNull();

        addIssuesToSprint(ctx, ctx.token(), sprintId, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
    }

    /** Removing an issue that belongs to another project is a 404, not a silent no-op. */
    @Test
    void removingAForeignIssueFromASprintIs404() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var sprintId = createSprint(ctx, "Sprint 1");
        var theirs = createIssue(sibling, "theirs");

        removeIssueFromSprint(ctx, ctx.token(), sprintId, idOf(theirs))
                .andExpect(status().isNotFound());
    }
}
