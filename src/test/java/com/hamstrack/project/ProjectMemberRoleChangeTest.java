package com.hamstrack.project;

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
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-127 (S4), M4 — {@code PATCH /api/workspaces/{ws}/projects/{p}/members/{userId}}.
 *
 * <p>Three things arrive together here and only make sense together:
 * <ul>
 *   <li>the endpoint itself, which until now did not exist — correcting a project role meant
 *       remove + add, two calls with a hole in the middle;</li>
 *   <li><strong>door 3</strong> of the stranding enumeration: a <em>demotion</em> can leave a
 *       project with no administrator while removing no row at all, which neither HD-136
 *       guard could see;</li>
 *   <li>the <strong>§4 ceiling escape</strong>, without which a project whose only
 *       member-managers carry a narrow custom role can never acquire the permissions none of
 *       them holds — through any endpoint, by anybody.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectMemberRoleChangeTest extends SprintTestBase {

    // ==================================================== the endpoint

    @Test
    void aProjectAdminChangesAMembersRoleById() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");

        patchMember(ctx, ctx.token(), member.user().getId(), BuiltInRoles.PROJECT_COMMENTER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COMMENTER"));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, member.user())
                .orElseThrow().getRole().getId())
                .isEqualTo(BuiltInRoles.PROJECT_COMMENTER);
    }

    /**
     * <strong>The {@code roleId} path does not translate.</strong> The legacy {@code role}
     * key maps {@code "VIEWER"} onto Contributor, so a genuinely read-only project member was
     * inexpressible until now; naming the built-in Viewer by id stores the built-in Viewer.
     */
    @Test
    void aGenuinelyReadOnlyMemberIsFinallyExpressible() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");

        patchMember(ctx, ctx.token(), member.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, member.user())
                .orElseThrow().getRole().getId())
                .as("the id path must store what it was given, not the legacy translation")
                .isEqualTo(BuiltInRoles.PROJECT_VIEWER);

        // …and the role now means what it says: Viewer grants nothing.
        postIssue(ctx, member.token(), "Should not be allowed")
                .andExpect(status().isForbidden());
    }

    /** Re-sending the role a member already holds is a no-op, not a rejection. */
    @Test
    void settingTheSameRoleIsANoOp() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");

        patchMember(ctx, ctx.token(), member.user().getId(), BuiltInRoles.PROJECT_MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    // ==================================================== door 3 — the demotion

    /**
     * <strong>A demotion strands a project with no row removed</strong> — the door HD-136 did
     * not have, because both of its guards watch deletions.
     */
    @Test
    void demotingTheSoleAdministratorIs409() throws Exception {
        var ctx = newProject();   // the creator is the sole Project admin

        patchMember(ctx, ctx.token(), ctx.owner().getId(), BuiltInRoles.PROJECT_MEMBER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner())
                .orElseThrow().getRole().getId())
                .as("the refused demotion was applied anyway")
                .isEqualTo(BuiltInRoles.PROJECT_MANAGER);

        // With a second administrator present the guard stands down: it counts, it does not
        // blanket-ban self-demotion.
        actorWith(ctx, "MEMBER", "MANAGER");
        patchMember(ctx, ctx.token(), ctx.owner().getId(), BuiltInRoles.PROJECT_MEMBER)
                .andExpect(status().isOk());
    }

    /**
     * <strong>A promotion cannot strand, so the last-administrator check is skipped for
     * one.</strong> Otherwise a project with exactly one administrator could never widen or
     * re-role them — the invariant would make itself unfixable, which is the shape
     * {@code WorkspaceMemberService.updateRole}'s
     * {@code if (!requested.isBuiltIn(WORKSPACE_OWNER))} avoids on the workspace side.
     *
     * <p>The pair is the assertion: the sole administrator moves to built-in <strong>Team
     * lead</strong> (which grants {@code project.member.manage}) and it is allowed, while
     * moving to Contributor — one permission different, in the one that matters — is the 409
     * {@link #demotingTheSoleAdministratorIs409} pins.
     */
    @Test
    void movingTheSoleAdministratorToAnotherAdministeringRoleIsAllowed() throws Exception {
        var ctx = newProject();   // the creator is the sole Project admin

        patchMember(ctx, ctx.token(), ctx.owner().getId(), BuiltInRoles.PROJECT_TEAM_LEAD)
                .andExpect(status().isOk());

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner())
                .orElseThrow().getRole().getId())
                .isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
    }

    // ==================================================== §4 — the escape

    /**
     * <strong>The escape, and its one constraint.</strong> Any holder of
     * {@code project.member.manage} may hand out the built-in Project admin — to somebody
     * else. Never to themselves: without {@code target != actor} the permission would imply
     * all twenty project permissions for its holder, the project-scope ceiling would be
     * decorative, and HD-136's adoption path would become a two-call route to
     * {@code issue.delete}.
     *
     * <p><strong>The colleague starts as a Viewer, and that is not incidental.</strong> The
     * ceiling applies to <em>both</em> ends of a role change, and the {@code ACTING_ON} half
     * is untouched by the escape: a bare member-manager cannot administer somebody who
     * already holds more than they do. Viewer grants the empty set, which every set covers,
     * so this fixture isolates the half the escape is about.
     */
    @Test
    void aNarrowMemberManagerMayAppointAProjectAdminButNotThemselves() throws Exception {
        var ctx = newProject();
        var lead = actorWithCustomProjectRole(ctx, "roster-lead",
                Permission.PROJECT_MEMBER_MANAGE);
        var colleague = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), colleague.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isCreated());

        patchMember(ctx, lead.token(), colleague.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isOk());

        patchMember(ctx, lead.token(), lead.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("cannot grant")));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, lead.user())
                .orElseThrow().getRole().getId())
                .as("the refused self-grant was applied anyway")
                .isNotEqualTo(BuiltInRoles.PROJECT_MANAGER);
    }

    /** The escape is <strong>one fixed target</strong>, not a general exemption. */
    @Test
    void theEscapeDoesNotCoverAnyOtherWiderRole() throws Exception {
        var ctx = newProject();
        var lead = actorWithCustomProjectRole(ctx, "roster-lead-2",
                Permission.PROJECT_MEMBER_MANAGE);
        var colleague = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), colleague.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isCreated());
        var wider = unassignedProjectRole(ctx, "deleter", Permission.ISSUE_DELETE);

        patchMember(ctx, lead.token(), colleague.user().getId(), wider)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.delete")));
    }

    /**
     * <strong>After the escape fires, the removal ceiling is unchanged</strong> — so A can no
     * longer act on B, because {@code ACTING_ON} compares against B's <em>current</em> role.
     * Intended, and pinned so it is not later "fixed": it is also the residual the escape is
     * documented with, since the way back is B (who now holds everything) acting on A.
     */
    @Test
    void promotingSomebodyPutsThemBeyondTheActorsReach() throws Exception {
        var ctx = newProject();
        var lead = actorWithCustomProjectRole(ctx, "roster-lead-3",
                Permission.PROJECT_MEMBER_MANAGE);
        var colleague = actorWith(ctx, "MEMBER", null);
        addMemberById(ctx, ctx.token(), colleague.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isCreated());

        patchMember(ctx, lead.token(), colleague.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isOk());
        patchMember(ctx, lead.token(), colleague.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isForbidden());

        // …and the documented recovery: the newly-minted Project admin can act on the lead,
        // which is what makes the escape a two-person procedure rather than a trap.
        patchMember(ctx, colleague.token(), lead.user().getId(), BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isOk());
    }

    // ==================================================== tenancy & role references

    /**
     * A role id in a <strong>body</strong> is a value, so every unusable one is the same 422 —
     * foreign, wrong-scope and nonsense alike. Two distinguishable codes here would be an
     * oracle for whether a role exists in another tenant.
     */
    @Test
    void everyUnusableRoleIdIsTheSame422() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        var other = newProject();
        var foreign = unassignedProjectRole(other, "theirs", Permission.ISSUE_CREATE);

        for (var roleId : new UUID[]{
                BuiltInRoles.WORKSPACE_MEMBER,   // right workspace, WRONG SCOPE
                foreign,                          // right scope, WRONG WORKSPACE
                UUID.randomUUID()}) {             // nothing at all
            patchMember(ctx, ctx.token(), member.user().getId(), roleId)
                    .andExpect(status().isUnprocessableContent());
        }

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, member.user())
                .orElseThrow().getRole().getId())
                .isEqualTo(BuiltInRoles.PROJECT_MEMBER);
    }

    @Test
    void nonMembersAndUnprivilegedMembersGet404And403() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        var stranger = newProject();

        // A member of a different workspace cannot tell this project apart from one that
        // does not exist.
        patchMember(ctx, stranger.token(), member.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isNotFound());

        // A proven member without project.member.manage gets 403 — reachable only after
        // membership is established.
        patchMember(ctx, member.token(), ctx.owner().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.member.manage")));

        // A workspace member who holds no project row here is a 404 ABOUT THE MEMBERSHIP,
        // which says nothing about whether the account exists.
        var outsider = actorWith(ctx, "MEMBER", null);
        patchMember(ctx, ctx.token(), outsider.user().getId(), BuiltInRoles.PROJECT_VIEWER)
                .andExpect(status().isNotFound());
    }

    // ==================================================== helpers

    private String membersBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/members";
    }

    private ResultActions patchMember(Ctx ctx, String token, UUID userId, UUID roleId) throws Exception {
        return mockMvc.perform(patch(membersBase(ctx) + "/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions addMemberById(Ctx ctx, String token, UUID userId, UUID roleId) throws Exception {
        return mockMvc.perform(post(membersBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    /**
     * A PROJECT-scoped custom role held by nobody — what
     * {@code actorWithCustomProjectRole} cannot give, since it also assigns what it creates.
     */
    private UUID unassignedProjectRole(Ctx ctx, String key, Permission... permissions) {
        return txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ctx.wsId());
            role.setScope(RoleScope.PROJECT);
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
}
