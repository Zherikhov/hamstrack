package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-129 (S6) — <strong>{@code roleId} on {@code WorkspaceMemberResponse} and
 * {@code ProjectMemberResponse}</strong>.
 *
 * <p>A member row used to name its role with a <em>key</em>, and a key is not an identity.
 * Keys are unique per {@code (workspace, scope)} only, so a client holding the role catalog
 * cannot always map a member onto the row that member holds — which is exactly the mapping a
 * role picker is. The S6 SPA handles the miss defensively (a placeholder rather than a
 * confidently wrong privilege label), and that refusal is right; this field is what makes the
 * refusal unnecessary.
 *
 * <p>Two halves, asserted separately on purpose: the field must be <strong>present and
 * correct</strong> on every member response, and it must be <strong>absent exactly where the
 * key is</strong> — emitting an id whose key the scope/ownership assertion refused would hand
 * the client the very thing the degrade withheld, since the client would then look the id up
 * in the catalog and print the name.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class MemberRoleIdentityTest extends SprintTestBase {

    // ==================================================== the field is there and correct

    @Test
    void bothMemberListsIdentifyTheRoleById() throws Exception {
        var ctx = newProject();

        var owner = entry(workspaceMembers(ctx, ctx.token()), ctx.owner().getId());
        assertThat(owner.get("roleId").asText()).isEqualTo(BuiltInRoles.WORKSPACE_OWNER.toString());
        assertThat(owner.get("role").asText())
                .as("roleId is added BESIDE the key, never instead of it — S5 screens read the "
                    + "key and each slice has to be independently deployable")
                .isEqualTo("OWNER");

        var inProject = entry(projectMembers(ctx, ctx.token()), ctx.owner().getId());
        assertThat(inProject.get("roleId").asText()).isEqualTo(BuiltInRoles.PROJECT_MANAGER.toString());
        assertThat(inProject.get("role").asText()).isEqualTo("MANAGER");
    }

    /**
     * <strong>The ambiguity a client actually hits today.</strong> No custom role is needed
     * for it: {@code MEMBER} is a built-in key in <em>both</em> scopes — workspace Member and
     * project Contributor — because {@code roles_scope_key_uk} is per
     * {@code (workspace_id, scope, key)} and {@code RoleService.generateKey} only suffixes
     * within a scope. So one member, two rows, the same string, two different roles with two
     * different permission sets. Any client resolving a member role by key against a catalog
     * that spans both scopes is one filter away from naming the wrong privilege.
     */
    @Test
    void theSameKeyNamesTwoDifferentRolesAcrossScopes() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");

        var atWorkspace = entry(workspaceMembers(ctx, ctx.token()), member.user().getId());
        var atProject = entry(projectMembers(ctx, ctx.token()), member.user().getId());

        assertThat(atWorkspace.get("role").asText()).isEqualTo("MEMBER");
        assertThat(atProject.get("role").asText()).isEqualTo("MEMBER");
        assertThat(atWorkspace.get("roleId").asText())
                .as("the two MEMBERs are different roles, and only the id says so")
                .isNotEqualTo(atProject.get("roleId").asText());
        assertThat(atWorkspace.get("roleId").asText()).isEqualTo(BuiltInRoles.WORKSPACE_MEMBER.toString());
        assertThat(atProject.get("roleId").asText()).isEqualTo(BuiltInRoles.PROJECT_MEMBER.toString());
    }

    /** A custom role is addressable by id, which is the whole point of the field. */
    @Test
    void aCustomRoleIsIdentifiedByIdNotByItsGeneratedKey() throws Exception {
        var ctx = newProject();
        var lead = actorWithCustomProjectRole(ctx, "roster-lead", Permission.PROJECT_MEMBER_MANAGE);

        var entry = entry(projectMembers(ctx, ctx.token()), lead.user().getId());
        assertThat(entry.get("role").asText()).isEqualTo("roster-lead");
        assertThat(UUID.fromString(entry.get("roleId").asText()))
                .isEqualTo(projectMemberRepository
                        .findByProjectAndUser(projectRepository.findById(ctx.projectId()).orElseThrow(),
                                lead.user())
                        .orElseThrow().getRole().getId());
    }

    /**
     * The write responses report what was <strong>stored</strong>, both fields together —
     * including after the legacy {@code VIEWER → Contributor} translation, where the echoed
     * key and the echoed id have to agree on the role that actually landed in the row.
     */
    @Test
    void theWriteResponsesEchoTheStoredRole() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", null);

        mockMvc.perform(post(projectMembersBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + member.user().getId() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.roleId").value(BuiltInRoles.PROJECT_MEMBER.toString()));

        mockMvc.perform(patch(projectMembersBase(ctx) + "/" + member.user().getId())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.roleId").value(BuiltInRoles.PROJECT_VIEWER.toString()));

        mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/members/" + member.user().getId())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + BuiltInRoles.WORKSPACE_ADMIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.roleId").value(BuiltInRoles.WORKSPACE_ADMIN.toString()));
    }

    // ==================================================== the degrade path

    /**
     * <strong>{@code roleId} degrades WITH the key, never past it</strong> (HD-127 §3b).
     *
     * <p>{@code resolveRoleOrDegrade} answers empty for a wrong-scope or foreign
     * {@code role_id}, and the listing renders {@code role: null} rather than the name it
     * just refused — that name being the one genuine leak in the vicinity, and the reason the
     * assertion runs at all. An id emitted beside that {@code null} would restore the leak by
     * proxy: the client resolves it against the catalog and prints the name anyway. Both
     * fields therefore come from one nullable {@code RoleView}, so they cannot drift.
     */
    @Test
    void aRefusedRoleLeavesNeitherAKeyNorAnId() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        var projectImpostor = customRole(ctx.wsId(), RoleScope.WORKSPACE, "pm-sneaky",
                Permission.WORKSPACE_EDIT);
        var workspaceImpostor = customRole(ctx.wsId(), RoleScope.PROJECT, "wm-sneaky",
                Permission.ISSUE_CREATE);
        // Wrong-scope roles on ANOTHER member's rows — unreachable through the API (every
        // write door resolves ids through findAssignable), which is precisely why the read
        // path asserts rather than trusts.
        plantProjectRole(ctx, member.user().getId(), projectImpostor);
        plantWorkspaceRole(ctx, member.user().getId(), workspaceImpostor);

        var inProject = projectMembers(ctx, ctx.token());
        var degradedProject = entry(inProject, member.user().getId());
        assertThat(degradedProject.get("role").isNull()).isTrue();
        assertThat(degradedProject.get("roleId").isNull())
                .as("the id of the role whose KEY was just refused must not be emitted — the "
                    + "client would look it up in the role catalog and render the name")
                .isTrue();
        assertThat(inProject.toString()).doesNotContain(projectImpostor.toString());

        var atWorkspace = workspaceMembers(ctx, ctx.token());
        var degradedWorkspace = entry(atWorkspace, member.user().getId());
        assertThat(degradedWorkspace.get("role").isNull()).isTrue();
        assertThat(degradedWorkspace.get("roleId").isNull()).isTrue();
        assertThat(atWorkspace.toString()).doesNotContain(workspaceImpostor.toString());

        // …and the rest of the list is intact: one corrupt row must not cost the whole tab.
        assertThat(entry(atWorkspace, ctx.owner().getId()).get("roleId").asText())
                .isEqualTo(BuiltInRoles.WORKSPACE_OWNER.toString());
    }

    // ==================================================== helpers

    private String projectMembersBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/members";
    }

    private JsonNode projectMembers(Ctx ctx, String token) throws Exception {
        return json.readTree(mockMvc.perform(get(projectMembersBase(ctx))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode workspaceMembers(Ctx ctx, String token) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/members")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode entry(JsonNode members, UUID userId) {
        for (var m : members) {
            if (m.get("userId").asText().equals(userId.toString())) return m;
        }
        throw new AssertionError("no member row for " + userId + " in " + members);
    }

    /** A custom role of this workspace in the given scope, held by nobody yet. */
    private UUID customRole(UUID workspaceId, RoleScope scope, String key, Permission... permissions) {
        return txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(workspaceId);
            role.setScope(scope);
            role.setKey(key);
            role.setName(key);
            role.setBuiltIn(false);
            var grants = new LinkedHashSet<RolePermission>();
            for (var p : permissions) grants.add(new RolePermission(p, false));
            role.setPermissions(grants);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
    }

    private void plantProjectRole(Ctx ctx, UUID userId, UUID roleId) {
        txTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE project_members SET role_id = :role "
                                   + "WHERE project_id = :project AND user_id = :user")
                .setParameter("role", roleId)
                .setParameter("project", ctx.projectId())
                .setParameter("user", userId)
                .executeUpdate());
    }

    private void plantWorkspaceRole(Ctx ctx, UUID userId, UUID roleId) {
        txTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE workspace_members SET role_id = :role "
                                   + "WHERE workspace_id = :workspace AND user_id = :user")
                .setParameter("role", roleId)
                .setParameter("workspace", ctx.wsId())
                .setParameter("user", userId)
                .executeUpdate());
    }
}
