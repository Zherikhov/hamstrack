package com.hamstrack.project;

import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.common.security.Permission;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three guards the HD-125 review put on {@code /projects/{p}/members} — none of which
 * changes what an existing actor may do, all of which close a door the permission model
 * opened or inherited.
 *
 * <ul>
 *   <li><strong>M1</strong> — {@code role: "VIEWER"} must not write the new, empty Viewer
 *       through an endpoint whose meaning did not change.</li>
 *   <li><strong>M3</strong> — {@code project.member.manage} must not be self-escalation to
 *       Project admin, and a project must not lose its last administrator.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectMembershipGuardsTest extends SprintTestBase {

    // ==================================================== M1 — the empty Viewer

    /**
     * The built-in Viewer grants nothing, which is the whole point of it — and exactly why
     * this endpoint must not mint one yet. {@code AddProjectMemberRequest.role} is the
     * legacy enum, where {@code VIEWER} meant "the fallback everybody gets" and granted
     * everything; writing the new one verbatim would silently turn an operator's
     * "add a viewer" into "add someone who 403s on every write and cannot be assigned".
     * So it lands on Contributor, the same role V14 maps existing VIEWER rows to.
     */
    @Test
    void addingAViewerWritesContributorUntilTheDtoTakesARoleId() throws Exception {
        var ctx = newProject();
        var newcomer = actorWith(ctx, "MEMBER", null);

        addMember(ctx, ctx.token(), newcomer.user().getId(), "VIEWER")
                .andExpect(status().isCreated())
                // The response echoes what was STORED, not what was asked for.
                .andExpect(jsonPath("$.role").value("MEMBER"));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        var membership = projectMemberRepository.findByProjectAndUser(project, newcomer.user()).orElseThrow();
        assert membership.getRole().getId().equals(BuiltInRoles.PROJECT_MEMBER)
                : "an added 'VIEWER' landed on role " + membership.getRole().getId()
                  + "; it must be the Contributor V14 maps existing VIEWER rows to.";

        // And the newcomer can still do what any workspace member could do before HD-123.
        postIssue(ctx, newcomer.token(), "Still able to work").andExpect(status().isCreated());
    }

    // ==================================================== M3 — ceiling + last admin

    /**
     * {@code project.member.manage} on its own must not be a ladder to everything. The
     * attack is two calls that each pass their own gate: remove your own membership row,
     * then add it back naming a bigger role. The ceiling refuses the second.
     */
    @Test
    void memberManagementIsNotSelfEscalation() throws Exception {
        var ctx = newProject();
        // A "team lead" built the way §7.2's recipe says workspaces will build one:
        // duplicate Contributor, then add member management. It has to be built that way
        // round — the rule is "everything you hand out, you hold", not "anything below
        // you", so a role carrying `project.member.manage` and nothing else could not add
        // a single Contributor. (Worth knowing before the S4 role editor ships: that is a
        // compose-time warning, not a use-time 403.)
        var lead = actorWithCustomProjectRole(ctx, "team-lead",
                Permission.PROJECT_MEMBER_MANAGE,
                Permission.ISSUE_CREATE, Permission.ISSUE_EDIT, Permission.ISSUE_TRANSITION,
                Permission.ISSUE_ASSIGN, Permission.ISSUE_ASSIGNABLE, Permission.ISSUE_RANK,
                Permission.COMMENT_CREATE, Permission.COMMENT_EDIT, Permission.COMMENT_DELETE,
                Permission.ATTACHMENT_CREATE, Permission.ATTACHMENT_DELETE,
                Permission.SPRINT_ASSIGN);
        var newcomer = actorWith(ctx, "MEMBER", null);

        // Handing out a role they themselves hold is fine.
        addMember(ctx, lead.token(), newcomer.user().getId(), "MEMBER")
                .andExpect(status().isCreated());

        // Handing out MANAGER is not: it includes permissions they do not hold, and the
        // 403 names the first of them rather than saying "insufficient role".
        var other = actorWith(ctx, "MEMBER", null);
        addMember(ctx, lead.token(), other.user().getId(), "MANAGER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("cannot grant")));

        // Nor may they remove the project admin who outranks them (the other door: HD-132's
        // "only checking the new role would let an ADMIN demote an OWNER").
        removeMember(ctx, lead.token(), ctx.owner().getId())
                .andExpect(status().isForbidden());

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assert projectMemberRepository.findByProjectAndUser(project, other.user()).isEmpty()
                : "the refused grant created a membership anyway";
        assert projectMemberRepository.findByProjectAndUser(project, ctx.owner()).isPresent()
                : "the refused removal deleted the project admin anyway";
    }

    /**
     * <strong>The ceiling bounds the delta, not the row</strong> (review round 2). In an
     * {@code OPEN} workspace a {@code DELETE} does not remove anybody from the project —
     * it drops them onto the default role chain, ending at Contributor. So a role that is
     * <em>narrower</em> than the default could delete its own row and inherit permissions
     * it was denied, passing a ceiling that only ever compared its role with itself.
     */
    @Test
    void aRemovalMayNotLeaveSomebodyWithMoreThanTheActorHolds() throws Exception {
        var ctx = newProject();
        // Member management, plus strictly less than Contributor: no issue.rank, which is
        // exactly the "developers must not reprioritise the backlog" policy the catalog
        // exists to express.
        var lead = actorWithCustomProjectRole(ctx, "narrow-lead",
                Permission.PROJECT_MEMBER_MANAGE,
                Permission.ISSUE_CREATE, Permission.ISSUE_EDIT, Permission.ISSUE_TRANSITION,
                Permission.ISSUE_ASSIGN, Permission.ISSUE_ASSIGNABLE,
                Permission.COMMENT_CREATE, Permission.COMMENT_EDIT, Permission.COMMENT_DELETE,
                Permission.ATTACHMENT_CREATE, Permission.ATTACHMENT_DELETE,
                Permission.SPRINT_ASSIGN);

        // Deleting their own row would inherit Contributor — which carries issue.rank.
        removeMember(ctx, lead.token(), lead.user().getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.rank")))
                .andExpect(jsonPath("$.detail", containsString("default role")));

        // The mirror: promoting somebody else to the default by deleting their narrow row.
        var narrow = actorWith(ctx, "MEMBER", null);
        addMember(ctx, lead.token(), narrow.user().getId(), "MEMBER")
                .andExpect(status().isForbidden());   // Contributor > the lead's own set

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assert projectMemberRepository.findByProjectAndUser(project, lead.user()).isPresent()
                : "the refused self-removal deleted the row anyway";
    }

    /**
     * A project that has an administrator must keep one. {@code project.member.manage} is
     * not part of the workspace-admin curator bypass, so the last removal would strand the
     * project: nobody — not even a workspace Owner — could add members back.
     *
     * <p>This is the review round's one user-visible change, and it is exactly this narrow:
     * a project with no explicit members at all stays perfectly normal, and removing any
     * non-last administrator still works.
     */
    @Test
    void aProjectMayNotLoseItsLastAdministrator() throws Exception {
        var ctx = newProject();   // the creator is the sole MANAGER

        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        // With a second administrator in place, either of them may go.
        var second = actorWith(ctx, "MEMBER", "MANAGER");
        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());
        removeMember(ctx, second.token(), second.user().getId())
                .andExpect(status().isConflict());
    }

    /**
     * <strong>HD-136 — the guard must recognise an administrator by what they may do, not
     * by which role id they carry.</strong> It used to look for members whose {@code role_id}
     * is the built-in Project admin, so a project whose only holder of
     * {@code project.member.manage} carries a <em>custom</em> role was not protected at all:
     * the guard was not looking for them, so their removal passed.
     *
     * <p>Both steps are load-bearing, and both flip on the old code:
     * <ol>
     *   <li>the creator stepping down is <strong>allowed</strong>, because the custom-role
     *       lead already counts as the second administrator (the old guard saw one, and
     *       refused);</li>
     *   <li>the lead, now the last administrator, is <strong>refused</strong> (the old guard
     *       saw none, and let the project be stranded).</li>
     * </ol>
     */
    @Test
    void aCustomRoleCarryingMemberManagementCountsAsAnAdministrator() throws Exception {
        var ctx = newProject();   // the creator is the sole built-in MANAGER
        // Contributor's set plus member management — §7.2's recipe for a "team lead", which
        // is also the only way to build one that can act on its own row (the ceiling bounds
        // what a removal LEAVES behind, not only what it removes).
        var lead = actorWithCustomProjectRole(ctx, "team-lead",
                Permission.PROJECT_MEMBER_MANAGE,
                Permission.ISSUE_CREATE, Permission.ISSUE_EDIT, Permission.ISSUE_TRANSITION,
                Permission.ISSUE_ASSIGN, Permission.ISSUE_ASSIGNABLE, Permission.ISSUE_RANK,
                Permission.COMMENT_CREATE, Permission.COMMENT_EDIT, Permission.COMMENT_DELETE,
                Permission.ATTACHMENT_CREATE, Permission.ATTACHMENT_DELETE,
                Permission.SPRINT_ASSIGN);

        // 1. The creator may hand the project over: the lead is an administrator now.
        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());

        // 2. …and the lead is the last one, so the project keeps them.
        removeMember(ctx, lead.token(), lead.user().getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assert projectMemberRepository.findByProjectAndUser(project, lead.user()).isPresent()
                : "the project's last administrator was removed anyway";
    }

    /**
     * The project-endpoint twin of the workspace-side case: <strong>an administrator by
     * inheritance counts only when somebody is actually standing on the fallback</strong>.
     *
     * <p>Here the project’s default role grants {@code project.member.manage} — so on
     * paper every member without an explicit row holds it — and the removal is
     * <em>still</em> refused, because this workspace’s only member is the creator and they
     * have an explicit row of their own. Nobody inherits anything, so the project really
     * would be left with no holder of the permission.
     *
     * <p>That distinction is the whole rule ({@code ProjectAdminGuard.cannotBeStranded}):
     * “the default grants it” alone is the tempting cheap version, and it is wrong in the
     * dangerous direction — it waves through exactly this stranding. The other half of the
     * pair, where somebody does inherit and the removal is allowed, lives in
     * {@code WorkspaceRemovalStrandingTest}, which can build both fixtures side by side.
     */
    @Test
    void anInheritedAdministratorDoesNotCountWhenNobodyActuallyInheritsIt() throws Exception {
        var ctx = newProject();   // the creator is the sole explicit MANAGER
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        project.setDefaultProjectRoleId(BuiltInRoles.PROJECT_MANAGER);
        projectRepository.save(project);

        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner()))
                .as("the project's last explicit administrator was removed anyway")
                .isPresent();
    }

    /**
     * <strong>Archiving is not a way out of the last-administrator rule.</strong> One actor,
     * no race, no privilege they do not already hold: a Project admin archives the project
     * ({@code project.archive} is theirs) and then removes their own membership. If the
     * guard skipped archived projects here — as the workspace-side guard rightly does, since
     * a frozen project must not block an offboarding — the project would be left archived
     * with no administrator, and nothing could undo it: unarchiving needs
     * {@code project.archive}, which is outside the curator bypass, so no workspace Owner
     * can thaw it; nobody can add an administrator; and the adoption path deliberately
     * ignores archived projects, so even that cannot reach it. Manual {@code UPDATE} only.
     *
     * <p>Refusing "you are the last administrator of this archived project" costs nobody an
     * offboarding — the workspace-side removal, which is the one people actually block on,
     * still ignores archived projects entirely.
     */
    @Test
    void archivingAProjectDoesNotLetItsLastAdministratorWalkOut() throws Exception {
        var ctx = newProject();   // the creator is the sole MANAGER

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                             + "/archive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        // assertThat, not bare `assert`: this regression’s two decisive claims must not
        // depend on the JVM being started with -ea.
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(project.getArchivedAt())
                .as("the fixture never archived the project, so it proves nothing")
                .isNotNull();
        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner()))
                .as("an archived project lost its last administrator — nothing can repair that")
                .isPresent();
    }

    /**
     * <strong>A deactivated co-administrator is not a co-administrator</strong> — at this
     * endpoint too, not only at the workspace one.
     *
     * <p>Who counts as an administrator is decided by two locking reads, one per endpoint,
     * and both carry the same {@code status = ACTIVE} filter for the same reason: an account
     * that cannot log in administers nothing, so counting one lets a project be left with an
     * "administrator" nobody can reach. The workspace-side removal already pins that filter;
     * the project-side one did not, so dropping it there was a silent change — the whole
     * suite stayed green while {@code DELETE /projects/&#123;p&#125;/members/&#123;u&#125;}
     * happily removed the last person who could actually manage the roster.
     *
     * <p>Recovery from that state is a manual {@code UPDATE}: {@code project.member.manage}
     * is outside the curator bypass, so no workspace Owner can repair it through the API.
     */
    @Test
    void aDeactivatedCoAdministratorDoesNotSaveTheProjectsLastActiveOne() throws Exception {
        var ctx = newProject();                              // the creator: administrator #1
        var dormant = actorWith(ctx, "MEMBER", "MANAGER");   // administrator #2…
        dormant.user().setStatus(UserStatus.DISABLED);       // …whose account is switched off
        userRepository.save(dormant.user());

        removeMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("last administrator")));

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        assertThat(projectMemberRepository.findByProjectAndUser(project, ctx.owner()))
                .as("the project's only reachable administrator was removed anyway")
                .isPresent();
    }

    // ==================================================== helpers

    private String membersBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/members";
    }

    private ResultActions addMember(Ctx ctx, String token, UUID userId, String role) throws Exception {
        return mockMvc.perform(post(membersBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}"));
    }

    private ResultActions removeMember(Ctx ctx, String token, UUID userId) throws Exception {
        return mockMvc.perform(delete(membersBase(ctx) + "/" + userId)
                .header("Authorization", "Bearer " + token));
    }
}
