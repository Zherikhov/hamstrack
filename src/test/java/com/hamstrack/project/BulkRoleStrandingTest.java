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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-127 (S4) — <strong>doors 4 and 5</strong> of the last-administrator enumeration.
 *
 * <p>HD-136 guards two deletions of a {@code project_members} row, and S4's door 3 guards a
 * demotion. These two are neither: a role <strong>edit</strong> that drops
 * {@code project.member.manage}, and a <strong>delete-with-reassign</strong> to a role
 * lacking it, each demote every holder at once, in every project, with no membership row
 * touched at all.
 *
 * <p>Both are guarded by one aggregate, and the guard is <strong>advisory</strong> — an
 * aggregate cannot take {@code FOR UPDATE}, so a concurrent membership change can make its
 * answer stale. That honesty is in the method's javadoc rather than papered over with a lock
 * that is not there; the locked invariant remains the per-row one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class BulkRoleStrandingTest extends SprintTestBase {

    /**
     * <strong>Door 4 — the role edit.</strong> The project's only administrator carries the
     * custom role; dropping {@code project.member.manage} from it demotes them without
     * touching a single membership row.
     */
    @Test
    void anEditThatDropsMemberManagementIsRefusedWhenItWouldStrandAProject() throws Exception {
        var ctx = newProject();
        var role = duplicateOfViewer(ctx, "Roster lead");
        patchRole(ctx, role, "{\"permissions\":[{\"key\":\"project.member.manage\"}]}")
                .andExpect(status().isOk());

        // The lead becomes the project's ONLY administrator.
        var lead = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), lead.user().getId(), role)
                .andExpect(status().isCreated());
        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());

        var refused = patchRole(ctx, role, "{\"permissions\":[{\"key\":\"issue.create\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("LAST_PROJECT_ADMIN_BULK"))
                .andExpect(jsonPath("$.projects[0].id").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).contains(ctx.projectId().toString());

        // Nothing was written: the role still manages members.
        assertThat(permissionsOf(ctx, role)).contains("project.member.manage");

        // Give the project a second administrator, and the identical edit succeeds.
        var second = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, lead.token(), second.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isCreated());
        patchRole(ctx, role, "{\"permissions\":[{\"key\":\"issue.create\"}]}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>Door 5 — delete-with-reassign to a role that lacks the permission.</strong>
     * The same bulk demotion by another name, and refused identically.
     */
    @Test
    void aReassignToARoleWithoutMemberManagementIsRefusedWhenItWouldStrandAProject() throws Exception {
        var ctx = newProject();
        var role = duplicateOfViewer(ctx, "Roster lead 2");
        patchRole(ctx, role, "{\"permissions\":[{\"key\":\"project.member.manage\"}]}")
                .andExpect(status().isOk());

        var lead = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), lead.user().getId(), role)
                .andExpect(status().isCreated());
        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());

        // Contributor grants everyday delivery and NOT member management.
        deleteRole(ctx, role, BuiltInRoles.PROJECT_MEMBER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("LAST_PROJECT_ADMIN_BULK"));

        // Team lead does grant it, so the same delete goes through — which is the remedy the
        // refusal names, and one the refused person can perform themselves. That is the whole
        // reason there is deliberately no adoption path for the bulk doors.
        deleteRole(ctx, role, BuiltInRoles.PROJECT_TEAM_LEAD)
                .andExpect(status().isNoContent());

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, lead.user())
                .orElseThrow().getRole().getId())
                .isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
    }

    /**
     * The guard runs <strong>only</strong> on the transition that actually removes the
     * permission — so an ordinary rename of the same role, or an edit to a role that never
     * carried it, pays nothing and is never refused.
     */
    @Test
    void anEditThatDoesNotDropMemberManagementIsNeverRefused() throws Exception {
        var ctx = newProject();
        var role = duplicateOfViewer(ctx, "Roster lead 3");
        patchRole(ctx, role, "{\"permissions\":[{\"key\":\"project.member.manage\"}]}")
                .andExpect(status().isOk());
        var lead = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), lead.user().getId(), role)
                .andExpect(status().isCreated());
        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());

        // A rename, with the grants untouched.
        patchRole(ctx, role, "{\"name\":\"Roster lead renamed\"}")
                .andExpect(status().isOk());
        // An edit that keeps the permission and adds another.
        patchRole(ctx, role, "{\"permissions\":[{\"key\":\"project.member.manage\"},"
                             + "{\"key\":\"issue.create\"}]}")
                .andExpect(status().isOk());
    }

    // ==================================================== helpers

    private String rolesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/roles";
    }

    private UUID duplicateOfViewer(Ctx ctx, String name) throws Exception {
        var body = mockMvc.perform(post(rolesBase(ctx) + "/" + BuiltInRoles.PROJECT_VIEWER + "/duplicate")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + json.writeValueAsString(name) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private ResultActions patchRole(Ctx ctx, UUID roleId, String body) throws Exception {
        return mockMvc.perform(patch(rolesBase(ctx) + "/" + roleId)
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteRole(Ctx ctx, UUID roleId, UUID reassignTo) throws Exception {
        return mockMvc.perform(delete(rolesBase(ctx) + "/" + roleId
                        + "?reassignToRoleId=" + reassignTo)
                .header("Authorization", "Bearer " + ctx.token()));
    }

    private String permissionsOf(Ctx ctx, UUID roleId) throws Exception {
        return mockMvc.perform(get(rolesBase(ctx) + "/" + roleId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String membersBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/members";
    }

    private ResultActions addMemberById(Ctx ctx, String token, UUID userId, UUID roleId) throws Exception {
        return mockMvc.perform(post(membersBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions removeMember(Ctx ctx, String token, UUID userId) throws Exception {
        return mockMvc.perform(delete(membersBase(ctx) + "/" + userId)
                .header("Authorization", "Bearer " + token));
    }
}
