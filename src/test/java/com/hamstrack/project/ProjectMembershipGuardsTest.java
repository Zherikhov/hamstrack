package com.hamstrack.project;

import com.hamstrack.common.security.Permission;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

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
