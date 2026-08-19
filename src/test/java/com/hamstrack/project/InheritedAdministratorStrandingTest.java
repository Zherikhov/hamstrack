package com.hamstrack.project;

import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-130 (S7 §5) — <strong>stranding doors 6, 7, 8 and 9: the administrators that exist only
 * by inheritance.</strong>
 *
 * <p>{@code ProjectAdminGuard.lockStrandedProjects} (door 2) builds its candidate set from
 * explicit {@code project_members} rows, so a project whose administrators exist <em>only</em>
 * through the §5.2 default was never a candidate and {@code cannotBeStranded} never ran for it:
 * removing the last workspace member who had no row there left it permanently unmanageable —
 * no 409, no adoption, no log line. Nothing could reach that state before S7, because no
 * built-in role anybody would choose as a default grants {@code project.member.manage}. S7
 * ships the picker that can set one to Team lead, so S7 closes it — and closes the three doors
 * the picker itself opens.
 *
 * <p><strong>The fixture is deliberately contrived</strong> (the workspace default is Team
 * lead, i.e. every member manages every project's roster) because that is the only shape in
 * which any of this is reachable. AC 21 asserts the flip side: in the configuration the product
 * actually ships, all four doors are a role-permission bit test and issue no existence query at
 * all — see {@code ProjectAccessPreviewQueryCountTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InheritedAdministratorStrandingTest extends SprintTestBase {

    /**
     * <strong>AC 17 — door 6, and AC 22's "no adoption path" in the same test.</strong>
     *
     * <p>Two ACTIVE members inherit Team lead in a project with no explicit rows. Removing the
     * first is fine — one inherited administrator is left. Removing the second is a
     * <strong>409 {@code STRANDED_BY_INHERITANCE}</strong> naming the project, and
     * {@code ?adoptStrandedProjects=true} does <em>not</em> clear it: {@code adoptAll} writes a
     * Team lead row, and here the caller inherits at least Team lead already, so adopting could
     * only narrow them. The message names the person who can still fix it.
     */
    @Test
    void removingTheLastInheritedAdministratorIsRefusedAndAdoptionDoesNotClearIt() throws Exception {
        var ctx = strictlyInheritedFixture();
        var first = actorWith(ctx, "MEMBER", null);
        var second = actorWith(ctx, "MEMBER", null);
        var bare = bareProject(ctx);
        // The workspace Owner holds an explicit VIEWER row here, so they do NOT stand on the
        // fallback — otherwise condition 4 is satisfied by them and nothing is ever stranded.
        // It is also why the Owner cannot fix this themselves, which is the residual §5.2
        // records and the reason the message names the departing member.
        projectMember(projectRepository.findById(bare).orElseThrow(), ctx.owner(), "VIEWER");

        // Two inherited administrators: removing one leaves the other.
        removeFromWorkspace(ctx, first.user().getId(), false).andExpect(status().isNoContent());

        removeFromWorkspace(ctx, second.user().getId(), false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_BY_INHERITANCE"))
                .andExpect(jsonPath("$.projects[0].id").value(bare.toString()))
                .andExpect(jsonPath("$.detail", containsString("default access")));

        // The adoption retry is deliberately not a way out of THIS door: adoptAll writes a
        // Team lead row, and the caller inherits at least Team lead already, so "adopting"
        // could only narrow them.
        removeFromWorkspace(ctx, second.user().getId(), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_BY_INHERITANCE"))
                .andExpect(jsonPath("$.detail",
                        containsString("ask the member you are removing")));

        // …and the remedy the message names does work, performed by the person it names: the
        // departing member still inherits project.member.manage here, so they can promote the
        // Owner's Viewer row before they go.
        promoteProjectMember(ctx, second.token(), bare, ctx.owner().getId(),
                BuiltInRoles.PROJECT_MANAGER).andExpect(status().isOk());
        removeFromWorkspace(ctx, second.user().getId(), false).andExpect(status().isNoContent());
    }

    /**
     * <strong>AC 18 — door 7.</strong> Flipping to {@code STRICT} takes every inherited
     * administrator at once, in every affected project, with no membership row touched. The
     * refusal names the projects; adding an explicit administrator clears it.
     */
    @Test
    void flippingToStrictIsRefusedWhileAProjectIsAdministeredOnlyByInheritance() throws Exception {
        var ctx = strictlyInheritedFixture();
        var member = actorWith(ctx, "MEMBER", null);
        var bare = bareProject(ctx);

        patchWorkspace(ctx, "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_BY_INHERITANCE"))
                .andExpect(jsonPath("$.projects[0].id").value(bare.toString()))
                .andExpect(jsonPath("$.detail", containsString("Restricting project access")));

        // The preview says the same thing, from the same computation (AC 27).
        var preview = preview(ctx, "{\"projectAccessMode\":\"STRICT\"}");
        assertThat(preview.get("strandedProjects").size()).isEqualTo(1);
        assertThat(preview.get("strandedProjects").get(0).get("id").asText())
                .isEqualTo(bare.toString());

        addProjectMember(ctx, bare, member.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isCreated());
        patchWorkspace(ctx, "{\"projectAccessMode\":\"STRICT\"}").andExpect(status().isOk());
    }

    /**
     * <strong>AC 19 — door 8.</strong> Narrowing the workspace default to Contributor is the
     * same demotion by a different lever and is refused; widening it to Project admin, which
     * also grants {@code project.member.manage}, is not. The guard is one-way by construction.
     */
    @Test
    void narrowingTheWorkspaceDefaultBelowMemberManagementIsRefusedAndWideningIsNot()
            throws Exception {
        var ctx = strictlyInheritedFixture();
        actorWith(ctx, "MEMBER", null);
        var bare = bareProject(ctx);

        patchWorkspace(ctx, "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MEMBER + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_BY_INHERITANCE"))
                .andExpect(jsonPath("$.projects[0].id").value(bare.toString()))
                .andExpect(jsonPath("$.detail", containsString("the default")));

        patchWorkspace(ctx, "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>AC 20 — door 9.</strong> A project whose own default grants member management,
     * switched to {@code inherit} where the workspace default does not, loses its
     * administrators the same way.
     */
    @Test
    void switchingAProjectDefaultToInheritIsRefusedWhenTheWorkspaceDefaultDoesNotManageMembers()
            throws Exception {
        var ctx = newProject();                   // workspace default stays NULL → Contributor
        var plain = actorWith(ctx, "MEMBER", null);
        var bare = bareProject(ctx);
        // The Owner needs project.member.manage HERE to touch this project's default at all —
        // the gate is membership authority, not project settings — so they start with an
        // explicit row and give it up once the default is in place. That sequence is the only
        // way a project reaches "administered only by its own default", which is precisely why
        // the door was unreachable before S7 shipped the picker.
        projectMember(projectRepository.findById(bare).orElseThrow(), ctx.owner(), "MANAGER");

        setProjectDefault(ctx, bare, "{\"roleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());
        // Dropping the explicit row is allowed: the project's own default now grants member
        // management and `plain` is standing on it, so `cannotBeStranded` excuses it.
        removeProjectMember(ctx, bare, ctx.owner().getId()).andExpect(status().isNoContent());

        setProjectDefault(ctx, bare, "{\"inherit\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_BY_INHERITANCE"))
                .andExpect(jsonPath("$.projects[0].id").value(bare.toString()))
                .andExpect(jsonPath("$.detail", containsString("the default")));

        // Explicit administrator first — somebody OTHER than the actor, because the §4 escape
        // is deliberately not a self-grant — and then the same change goes through.
        addProjectMember(ctx, bare, plain.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isCreated());
        setProjectDefault(ctx, bare, "{\"inherit\":true}").andExpect(status().isOk());
    }

    /**
     * <strong>AC 23 — the guard that fails loudly if somebody "unifies" the two chain
     * methods.</strong>
     *
     * <p>{@code cannotBeStranded} reads the <strong>mode-aware</strong>
     * {@code defaultProjectRole}, so in a {@code STRICT} workspace it answers "no fallback" and
     * the inheritance <em>excuse</em> never fires: a project's administrators are exactly its
     * explicit administering rows. Point it at {@code declaredDefaultProjectRole} instead and
     * the same removal is waved through on the strength of an inheritance the mode says does
     * not exist — and nothing else in the suite notices.
     */
    @Test
    void inAStrictWorkspaceTheInheritanceExcuseDoesNotApplyToARemoval() throws Exception {
        var ctx = strictlyInheritedFixture();   // workspace default = Team lead, and STRICT below
        var lead = actorWith(ctx, "MEMBER", null);
        var bare = bareProject(ctx);
        // One explicit administrator, so the flip to STRICT is legal…
        addProjectMember(ctx, bare, lead.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isCreated());
        patchWorkspace(ctx, "{\"projectAccessMode\":\"STRICT\"}").andExpect(status().isOk());
        // …and there are other ACTIVE members with no row, who WOULD inherit Team lead if the
        // workspace were OPEN. In STRICT they inherit nothing.
        actorWith(ctx, "MEMBER", null);
        actorWith(ctx, "MEMBER", null);

        removeFromWorkspace(ctx, lead.user().getId(), false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("STRANDED_PROJECTS"));

        // The control: the SAME removal is excused once the workspace is OPEN again, because
        // then somebody really does inherit the permission.
        patchWorkspace(ctx, "{\"projectAccessMode\":\"OPEN\"}").andExpect(status().isOk());
        removeFromWorkspace(ctx, lead.user().getId(), false).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ fixture

    /** A workspace whose default project role is the built-in Team lead. */
    private Ctx strictlyInheritedFixture() throws Exception {
        var ctx = newProject();
        patchWorkspace(ctx, "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());
        return ctx;
    }

    /**
     * A live project of {@code ctx}'s workspace with <strong>no</strong> {@code project_members}
     * rows at all — the shape door 2 cannot see. {@code newProject} cannot produce it, because
     * it seeds its creator as an explicit Project admin.
     */
    private UUID bareProject(Ctx ctx) {
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        return project(ws, ctx.owner()).getId();
    }

    private ResultActions patchWorkspace(Ctx ctx, String body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId())
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private com.fasterxml.jackson.databind.JsonNode preview(Ctx ctx, String body) throws Exception {
        var resp = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/workspaces/" + ctx.wsId() + "/project-access/preview")
                                .header("Authorization", "Bearer " + ctx.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp);
    }

    private ResultActions setProjectDefault(Ctx ctx, UUID projectId, String body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId
                        + "/default-role")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions addProjectMember(Ctx ctx, UUID projectId, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions promoteProjectMember(Ctx ctx, String token, UUID projectId,
                                               UUID userId, UUID roleId) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId
                        + "/members/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions removeProjectMember(Ctx ctx, UUID projectId, UUID userId) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId
                        + "/members/" + userId)
                .header("Authorization", "Bearer " + ctx.token()));
    }

    private ResultActions removeFromWorkspace(Ctx ctx, UUID userId, boolean adopt) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/members/" + userId
                        + (adopt ? "?adoptStrandedProjects=true" : ""))
                .header("Authorization", "Bearer " + ctx.token()));
    }
}
