package com.hamstrack.issue;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectMember;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-126 (S3) — the comment permissions actually bite</strong>
 * (roles-permissions-proposal §10.2, §10.3.5, §17.3).
 *
 * <p>Three permissions, three different shapes, and this is the file that proves each one
 * is wired into the HTTP surface rather than merely seeded:
 * <ul>
 *   <li>{@code comment.create} — <strong>the first gate this endpoint has ever had.</strong>
 *       Until S3 posting a comment was ungated entirely: any workspace member could comment
 *       on any issue in any project of that workspace;</li>
 *   <li>{@code comment.edit} — own-only and <em>not grantable unrestricted</em> (§17.3), so
 *       "nobody may edit another person's words" is a property of the catalog and not of a
 *       role, and a project administrator must be refused exactly like everyone else;</li>
 *   <li>{@code comment.delete} — own <em>or</em> unrestricted, and the unrestricted half is
 *       <strong>the epic's one accepted widening</strong> (§10.3.5). Nobody could moderate
 *       before this slice. It ships here and it is in the release notes.</li>
 * </ul>
 *
 * <p>Written in the style S2 established ({@code IssuePermissionEnforcementTest},
 * {@code IssueOwnGrantAndDoorTest}): real HTTP, built-in roles wherever a built-in
 * expresses the case, and every refusal checked for the permission key in its {@code detail}
 * — a bare 403 was exactly what these endpoints used to answer, and it told a user nothing.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CommentPermissionEnforcementTest extends ComponentTestBase {

    // ==================================================== comment.create

    /**
     * The Viewer is the archetype that could not exist before HD-123 (the legacy VIEWER
     * granted everything, §2.2) and is the whole point of the epic: read everything,
     * change nothing — comments included.
     */
    @Test
    void aProjectViewerReadsCommentsAndPostsNone() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var viewer = actorWith(ctx, "MEMBER", "VIEWER");

        mockMvc.perform(get(commentsBase(ctx, number))
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk());

        postComment(ctx, viewer.token(), number, "nope")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.create")));
    }

    /** The Commenter exists for exactly this: comments and attachments, nothing else. */
    @Test
    void aCommenterMayComment() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var commenter = actorWithBuiltInProjectRole(ctx, "COMMENTER");

        postComment(ctx, commenter.token(), number, "Looks right to me")
                .andExpect(status().isCreated());
    }

    // ==================================================== comment.edit (own only)

    /**
     * <strong>Own-only is not "own-only unless you are important".</strong> The project
     * administrator holds every project permission there is, and {@code comment.edit} is
     * seeded {@code own_only} even for them — {@code Permission.ownRequired()} makes the
     * unrestricted grant unrepresentable, so no role, custom ones included, can ever
     * acquire it. This is the assertion that would fail first if that changed.
     */
    @Test
    void nobodyEditsSomebodyElsesComment() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var author = actorWithBuiltInProjectRole(ctx, "MEMBER");
        var admin = actorWithBuiltInProjectRole(ctx, "MANAGER");
        var commentId = comment(ctx, author.token(), number, "My words");

        // The author edits their own comment: the own-only grant covers it.
        patchComment(ctx, author.token(), number, commentId, "My words, revised")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("My words, revised"));

        // The project administrator does not, and neither does the workspace owner who
        // created the project — the widening is deletion, never impersonation.
        patchComment(ctx, admin.token(), number, commentId, "Actually I think")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.edit")));
        patchComment(ctx, ctx.token(), number, commentId, "Actually I think")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.edit")));

        assert body(ctx, number, commentId).equals("My words, revised")
                : "a refused edit changed the comment anyway";
    }

    // ==================================================== comment.delete (the widening)

    /**
     * <strong>The release note.</strong> Before this slice the rule was authorship alone,
     * so nobody could delete another person's comment. The built-in Project admin holds
     * {@code comment.delete} unrestricted, so from HD-126 on they can — that is moderation,
     * and it is the only user-visible behaviour change in S3.
     *
     * <p>The same test pins the boundary of the widening, which matters more than the
     * widening itself: a Contributor still cannot, and a workspace <em>Owner</em> with no
     * {@code project_members} row still cannot, because {@code project.curate.all} — the
     * workspace-admin bypass — deliberately does not carry {@code comment.delete}.
     */
    @Test
    void aProjectAdminModeratesAndNobodyElseDoes() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var author = actorWithBuiltInProjectRole(ctx, "MEMBER");
        var contributor = actorWithBuiltInProjectRole(ctx, "MEMBER");
        var admin = actorWithBuiltInProjectRole(ctx, "MANAGER");
        var workspaceOwner = actorWith(ctx, "OWNER", null);

        var first = comment(ctx, author.token(), number, "Something regrettable");

        // Another contributor: no.
        deleteComment(ctx, contributor.token(), number, first)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.delete")));
        // A workspace OWNER who is not a project member: still no. The curator bypass is
        // four permissions (§17.2) and moderation is not one of them.
        deleteComment(ctx, workspaceOwner.token(), number, first)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.delete")));
        assert body(ctx, number, first).equals("Something regrettable")
                : "a refused delete removed the comment anyway";

        // The project administrator: yes — this is the widening.
        deleteComment(ctx, admin.token(), number, first).andExpect(status().isNoContent());

        // And the ordinary own-grant still works for everybody who holds it.
        var second = comment(ctx, author.token(), number, "Also regrettable");
        deleteComment(ctx, author.token(), number, second).andExpect(status().isNoContent());
    }

    /**
     * <strong>"Not grantable unrestricted" is a property of the catalog, and this is the only
     * test that can prove it.</strong> Every other assertion about {@code comment.edit} uses a
     * built-in role, and all four built-ins are seeded {@code own_only = TRUE} — so they would
     * all still pass if {@code Permission.ownRequired()} were deleted tomorrow and the rule
     * survived only as a seed convention.
     *
     * <p>Here the role is written with {@code own_only = FALSE} in the row, which is what an
     * S4 role editor (or a hand-edited install) can produce, and
     * {@code PermissionSet.of} is what must refuse to widen it. Note the actor <em>can</em>
     * edit their own comment through the very same grant — otherwise this would pass just as
     * well if the grant had been dropped on the floor.
     */
    @Test
    void aRoleGrantedCommentEditUnrestrictedStillOnlyEditsItsOwn() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var author = actorWithBuiltInProjectRole(ctx, "MEMBER");
        var theirs = comment(ctx, author.token(), number, "My words");

        // A custom project role whose stored grant is comment.edit UNRESTRICTED.
        var wide = actorWithCustomProjectRole(ctx, "editor-of-all",
                Permission.COMMENT_CREATE, Permission.COMMENT_EDIT);
        var mine = comment(ctx, wide.token(), number, "Mine");

        // ANCHOR: the grant resolved, own-only — they may edit their own.
        patchComment(ctx, wide.token(), number, mine, "Mine, revised")
                .andExpect(status().isOk());
        // …and the unrestricted row bought them nothing on somebody else's words.
        patchComment(ctx, wide.token(), number, theirs, "Rewritten by a stranger")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.edit")));

        assert body(ctx, number, theirs).equals("My words") : "a refused edit changed the comment";
    }

    /**
     * The other half of the moderation boundary: {@code comment.delete} is an ordinary
     * permission, so a role that holds it unrestricted really does moderate — even a custom
     * one that is nothing like a project administrator. Without this the suite could not tell
     * "moderation is gated on comment.delete" from "moderation is gated on being MANAGER",
     * which is the shape of gate HD-123 exists to delete.
     */
    @Test
    void moderationFollowsThePermissionAndNotTheRole() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var author = actorWithBuiltInProjectRole(ctx, "MEMBER");
        var moderator = actorWithCustomProjectRole(ctx, "moderator", Permission.COMMENT_DELETE);

        var theirs = comment(ctx, author.token(), number, "Something regrettable");
        deleteComment(ctx, moderator.token(), number, theirs).andExpect(status().isNoContent());

        // …and the permission is genuinely what carried it: a neighbouring custom role
        // holding comment.create instead is refused on the identical request.
        var second = comment(ctx, author.token(), number, "Also regrettable");
        var talker = actorWithCustomProjectRole(ctx, "talker", Permission.COMMENT_CREATE);
        deleteComment(ctx, talker.token(), number, second)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.delete")));
    }

    /** A Commenter holds {@code comment.delete} own-only: their own, and no further. */
    @Test
    void aCommenterDeletesOnlyTheirOwn() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var commenter = actorWithBuiltInProjectRole(ctx, "COMMENTER");
        var other = actorWithBuiltInProjectRole(ctx, "MEMBER");

        var mine = comment(ctx, commenter.token(), number, "Mine");
        var theirs = comment(ctx, other.token(), number, "Theirs");

        deleteComment(ctx, commenter.token(), number, theirs)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.delete")));
        deleteComment(ctx, commenter.token(), number, mine).andExpect(status().isNoContent());
    }

    // ==================================================== §10.3.6 — permission before state

    /**
     * A 403 must never depend on whether the project happens to be archived (§10.3.6): the
     * error a caller sees has to be stable, and a status code that changes with project
     * state makes that state observable through the authorization answer.
     */
    @Test
    void anArchivedProjectStill403sTheCallerWhoLacksThePermission() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Discuss this").get("number").asLong();
        var viewer = actorWith(ctx, "MEMBER", "VIEWER");
        archiveProject(ctx);

        // Missing permission → 403, not the 409 the archived project would otherwise give.
        postComment(ctx, viewer.token(), number, "nope")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("comment.create")));
        // …and someone who DOES hold it gets the state answer.
        postComment(ctx, ctx.token(), number, "nope")
                .andExpect(status().isConflict());
    }

    // ==================================================== helpers

    private String commentsBase(Ctx ctx, long number) {
        return ctx.issuesBase() + "/" + number + "/comments";
    }

    private ResultActions postComment(Ctx ctx, String token, long number, String body) throws Exception {
        return mockMvc.perform(post(commentsBase(ctx, number))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + json.writeValueAsString(body) + "}"));
    }

    private UUID comment(Ctx ctx, String token, long number, String body) throws Exception {
        var resp = postComment(ctx, token, number, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    private ResultActions patchComment(Ctx ctx, String token, long number, UUID commentId, String body)
            throws Exception {
        return mockMvc.perform(patch(commentsBase(ctx, number) + "/" + commentId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + json.writeValueAsString(body) + "}"));
    }

    private ResultActions deleteComment(Ctx ctx, String token, long number, UUID commentId)
            throws Exception {
        return mockMvc.perform(delete(commentsBase(ctx, number) + "/" + commentId)
                .header("Authorization", "Bearer " + token));
    }

    /** The stored body of one comment, read back as the project owner. */
    private String body(Ctx ctx, long number, UUID commentId) throws Exception {
        var resp = mockMvc.perform(get(commentsBase(ctx, number) + "?size=100")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var node : json.readTree(resp).get("content")) {
            if (node.get("id").asText().equals(commentId.toString())) return node.get("body").asText();
        }
        throw new AssertionError("comment " + commentId + " is gone from the issue");
    }

    private void archiveProject(Ctx ctx) throws Exception {
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/archive")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
    }

    /** A workspace member holding a <em>built-in</em> project role by key. */
    private Actor actorWithBuiltInProjectRole(Ctx ctx, String key) throws Exception {
        var actor = actorWith(ctx, "MEMBER", null);
        var member = new ProjectMember();
        member.setProject(projectRepository.findById(ctx.projectId()).orElseThrow());
        member.setUser(actor.user());
        member.setRole(roleCatalog.reference(RoleScope.PROJECT, key));
        projectMemberRepository.save(member);
        return actor;
    }
}
