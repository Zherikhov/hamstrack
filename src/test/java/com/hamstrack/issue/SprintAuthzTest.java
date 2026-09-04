package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §3.2 / §4.9 — who may do what.
 *
 * <p>Two tiers, deliberately different:
 * <ul>
 *   <li><strong>Sprint lifecycle</strong> (create / rename / start / complete / delete)
 *       is <em>curator</em>: project MANAGER or workspace OWNER/ADMIN. Starting and
 *       completing a sprint are commitments with reporting consequences and must not be
 *       a stray click by any member;</li>
 *   <li><strong>Putting items into a sprint and ranking them</strong> is the ordinary
 *       issue-edit tier: planning is teamwork, and requiring MANAGER to drag would make
 *       the backlog read-only for most of the team.</li>
 * </ul>
 *
 * <p>And the tenancy rule underneath both: a member without the role gets
 * <strong>403</strong>, a non-member of the workspace gets <strong>404</strong> —
 * never a 403 that would confirm the workspace exists.
 *
 * <p>Also covers the release's <em>flagged</em> permission change: {@code PATCH
 * …/projects/{id}} moved from MANAGER-only to the curator predicate, so a workspace
 * ADMIN who is not a project member may now rename a project — and the response must
 * echo that caller's REAL role, not a hardcoded MANAGER.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintAuthzTest extends SprintTestBase {

    @Test
    void theSprintLifecycleIsCuratorOnly() throws Exception {
        var ctx = newProject();
        var plainMember = actorWith(ctx, "MEMBER", "MEMBER");
        var sprintId = createSprint(ctx, "Sprint 1");

        // ---- a plain member may READ everything ----
        getSprint(ctx, plainMember.token(), sprintId).andExpect(status().isOk());
        listSprints(ctx, plainMember.token(), null);
        backlogView(ctx, plainMember.token(), null);
        completionPreview(ctx, plainMember.token(), sprintId).andExpect(status().isOk());

        // ---- …and may change NOTHING about the lifecycle ----
        postSprint(ctx, plainMember.token(), "{\"name\":\"Theirs\"}").andExpect(status().isForbidden());
        patchSprint(ctx, plainMember.token(), sprintId, "{\"name\":\"Renamed\"}")
                .andExpect(status().isForbidden());
        startSprint(ctx, plainMember.token(), sprintId).andExpect(status().isForbidden());
        deleteSprint(ctx, plainMember.token(), sprintId, false).andExpect(status().isForbidden());

        // (complete needs an ACTIVE sprint — the 403 must come before the 409)
        startSprint(ctx, ctx.token(), sprintId).andExpect(status().isOk());
        completeToBacklog(ctx, plainMember.token(), sprintId).andExpect(status().isForbidden());

        // Nothing leaked through: the sprint is still exactly as the curator left it.
        var node = sprintNode(ctx, sprintId);
        assertThat(node.get("name").asText()).as("%s", node).isEqualTo("Sprint 1");
        assertThat(node.get("state").asText()).as("%s", node).isEqualTo("ACTIVE");
    }

    /**
     * A workspace OWNER/ADMIN is a curator of every project in their workspace, even one
     * they are not a member of — the same predicate the SPA's settings area uses.
     */
    @Test
    void aWorkspaceAdminWhoIsNotAProjectMemberIsStillACurator() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        var sprintId = createSprint(ctx, admin.token(), "{\"name\":\"Admin's sprint\"}");
        patchSprint(ctx, admin.token(), sprintId, "{\"goal\":\"ship it\"}").andExpect(status().isOk());
        startSprint(ctx, admin.token(), sprintId).andExpect(status().isOk());
        completeToBacklog(ctx, admin.token(), sprintId).andExpect(status().isOk());
        deleteSprint(ctx, admin.token(), sprintId, false).andExpect(status().isNoContent());
    }

    /** A non-member sees 404 everywhere — never a 403 that would confirm the project exists. */
    @Test
    void aNonMemberGets404NotForbidden() throws Exception {
        var ctx = newProject();
        var stranger = newProject();   // its owner is a member of a DIFFERENT workspace
        var sprintId = createSprint(ctx, "Sprint 1");

        getSprint(ctx, stranger.token(), sprintId).andExpect(status().isNotFound());
        postSprint(ctx, stranger.token(), "{\"name\":\"x\"}").andExpect(status().isNotFound());
        patchSprint(ctx, stranger.token(), sprintId, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        startSprint(ctx, stranger.token(), sprintId).andExpect(status().isNotFound());
        completeToBacklog(ctx, stranger.token(), sprintId).andExpect(status().isNotFound());
        deleteSprint(ctx, stranger.token(), sprintId, true).andExpect(status().isNotFound());
        completionPreview(ctx, stranger.token(), sprintId).andExpect(status().isNotFound());
        addIssuesToSprint(ctx, stranger.token(), sprintId, UUID.randomUUID())
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/backlog")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
    }

    /**
     * The issue-edit tier: assigning to a sprint, removing from it and dragging are
     * available to any member who may edit issues.
     */
    @Test
    void assigningAndRankingIsTheIssueEditTierNotCurator() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        var sprintId = createSprint(ctx, "Sprint 1");
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");

        addIssuesToSprint(ctx, member.token(), sprintId, idOf(a)).andExpect(status().isOk());
        rank(ctx, member.token(), numberOf(b), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isOk());
        rank(ctx, member.token(), numberOf(b),
                "{\"afterIssueId\":\"" + idOf(a) + "\",\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isOk());
        removeIssueFromSprint(ctx, member.token(), sprintId, idOf(a)).andExpect(status().isNoContent());

        assertThat(sprintName(getIssue(ctx, numberOf(a)))).as("the member's removal did not apply").isNull();
        assertThat(sprintName(getIssue(ctx, numberOf(b)))).as("the member's move did not apply").isEqualTo("Sprint 1");
    }

    /** An archived project freezes every sprint mutation and every drag — 409, reads still work. */
    @Test
    void anArchivedProjectFreezesPlanningButNotReading() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "work");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/archive")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        postSprint(ctx, ctx.token(), "{\"name\":\"nope\"}").andExpect(status().isConflict());
        patchSprint(ctx, ctx.token(), sprintId, "{\"name\":\"nope\"}").andExpect(status().isConflict());
        startSprint(ctx, ctx.token(), sprintId).andExpect(status().isConflict());
        deleteSprint(ctx, ctx.token(), sprintId, true).andExpect(status().isConflict());
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isConflict());
        rank(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isConflict());

        // …including the project's own settings (0.13.0 review, security-officer L5):
        // `boardMode` joined that payload and changes how the board and the backlog
        // render, so an archived project's settings must not stay quietly writable while
        // everything they govern is frozen. `unarchive` is the way back.
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"SCRUM\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("archived")));
        patchProject(ctx, ctx.token(), "{\"name\":\"Renamed while archived\"}")
                .andExpect(status().isConflict());

        getSprint(ctx, ctx.token(), sprintId).andExpect(status().isOk());
        backlogView(ctx, ctx.token(), null);

        // Unarchiving reopens both the plan and the settings.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                                + "/unarchive")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"SCRUM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardMode").value("SCRUM"));
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());
    }

    // ===================================================== the widened project PATCH

    /**
     * §3.2's flagged change: {@code PATCH …/projects/{id}} used to be MANAGER-only.
     * {@code boardMode} joined that payload, so it now takes the curator predicate — a
     * workspace ADMIN who is NOT a project member may update it, and the echoed
     * {@code myRole} must be their real role (VIEWER), not a hardcoded MANAGER that
     * would make the SPA render manager-only actions for them.
     */
    @Test
    void aWorkspaceAdminMayNowPatchAProjectAndTheEchoedRoleIsTheirOwn() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        patchProject(ctx, admin.token(), "{\"name\":\"Renamed by the workspace admin\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed by the workspace admin"))
                .andExpect(jsonPath("$.myRole").value("VIEWER"))
                .andExpect(jsonPath("$.boardMode").value("KANBAN"));

        // The project MANAGER still gets their own role echoed back.
        patchProject(ctx, ctx.token(), "{\"boardMode\":\"SCRUM\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("MANAGER"))
                .andExpect(jsonPath("$.boardMode").value("SCRUM"));

        // …and a plain member still cannot: the widening stopped at curator.
        var plain = actorWith(ctx, "MEMBER", "MEMBER");
        patchProject(ctx, plain.token(), "{\"name\":\"nope\"}").andExpect(status().isForbidden());

        // A non-member of the workspace still gets 404, not 403.
        var stranger = newProject();
        patchProject(ctx, stranger.token(), "{\"name\":\"nope\"}").andExpect(status().isNotFound());

        // boardMode survives a round trip through GET.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardMode").value("SCRUM"));
    }

    private org.springframework.test.web.servlet.ResultActions patchProject(
            Ctx ctx, String token, String bodyJson) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }
}
