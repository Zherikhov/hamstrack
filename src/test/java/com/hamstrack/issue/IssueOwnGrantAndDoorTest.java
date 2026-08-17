package com.hamstrack.issue;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-125 (S2), the three boundaries the built-in roles cannot express</strong>
 * (roles-permissions-proposal §6.4, §6.5, §10.3.3, §10.3.4).
 *
 * <p>{@code BuiltInRoleSeedParityTest} proves the built-in <em>seed</em> moved nobody's
 * verdict — it never makes a request — and {@code IssuePermissionEnforcementTest} proves
 * the predicates are wired to HTTP at all. Neither can reach what is here, because each of
 * these cases needs an actor holding a combination <em>no built-in role ships</em>, which
 * puts it outside a table whose rows are all about built-ins:
 *
 * <ol>
 *   <li><strong>The {@code :own} boundary, end to end.</strong> Every built-in that holds
 *       an own-only grant also holds enough neighbouring grants to blur what is being
 *       observed. A role holding {@code issue.delete:own} and nothing else answers the
 *       question head-on: the reporter may, the same actor on somebody else's issue may
 *       not, and the refusal is a 403 rather than a silently-ignored write. The failure
 *       direction that matters is <em>widening</em> — an own-only grant read as
 *       unrestricted — so each pair is asserted from both sides with nothing else
 *       changed.</li>
 *   <li><strong>The per-field PATCH relaxation (§10.3.3).</strong> The SPA sends
 *       whole-form patches, so an unchanged field must demand nothing. The actor here
 *       holds {@code issue.edit} <em>alone</em>: if carrying an unchanged {@code statusId}
 *       or {@code assigneeId} dragged its permission into the request, this is where it
 *       401s the entire product's edit surface. The {@code dueDate} pair is the opposite
 *       trap — an unchanged {@code dueDate} plus {@code clearDueDate:true} really does
 *       mutate, and a tidier single "is dueDate changing" boolean would let it through
 *       ungated.</li>
 *   <li><strong>Each door, independently (§6.5).</strong> An actor who lacks
 *       {@code sprint.assign} but <em>holds</em> {@code issue.edit}/{@code issue.create}/
 *       {@code issue.rank} is the only actor who can tell "the sprint door is shut" from
 *       "the request was refused for some other reason". The built-in Commenter used by
 *       the sibling suite cannot: it lacks both.</li>
 * </ol>
 *
 * <p>Storage is redirected to a per-JVM temp dir ({@link #STORAGE_DIR}) exactly as
 * {@code AttachmentCrudTenancyTest} does, so the {@code attachment.delete:own} case
 * writes real blobs without touching the developer's {@code ./data/attachments}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueOwnGrantAndDoorTest extends SprintTestBase {

    static final Path STORAGE_DIR;
    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-own-grant-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", () -> STORAGE_DIR.toString());
    }

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;

    // ============================================ 1. the :own boundary, end to end

    /**
     * {@code issue.delete:own} — the grant §6.4 exists for. The actor files their own
     * issue and may bin it; the identical actor, on an issue somebody else reported, is
     * refused with the same key named in the message.
     */
    @Test
    void anOwnOnlyDeleteGrantReachesTheReportersOwnIssueAndNoOther() throws Exception {
        var ctx = newProject();
        var reporter = actorWithCustomProjectRole(ctx, "own-deleter",
                grant(Permission.ISSUE_CREATE), ownGrant(Permission.ISSUE_DELETE));

        long mine = numberOfPosted(postIssue(ctx, reporter.token(), "I filed this"));
        long theirs = createIssue(ctx, "The owner filed this").get("number").asLong();

        deleteIssue(ctx, reporter.token(), theirs)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.delete")));
        deleteIssue(ctx, reporter.token(), mine).andExpect(status().isNoContent());

        // The refusal refused: the other issue is still there, and still readable.
        assert getIssue(ctx, theirs).get("title").asText().equals("The owner filed this");
    }

    /**
     * The same boundary for {@code issue.edit:own}, and the extra thing an edit can show
     * that a delete cannot: the refusal happens <em>before</em> the mutation, so the
     * other person's issue is byte-for-byte unchanged afterwards.
     */
    @Test
    void anOwnOnlyEditGrantReachesTheReportersOwnIssueAndNoOther() throws Exception {
        var ctx = newProject();
        var reporter = actorWithCustomProjectRole(ctx, "own-editor",
                grant(Permission.ISSUE_CREATE), ownGrant(Permission.ISSUE_EDIT));

        long mine = numberOfPosted(postIssue(ctx, reporter.token(), "Mine to rename"));
        long theirs = createIssue(ctx, "Not mine").get("number").asLong();

        patchIssue(ctx, reporter.token(), theirs, "{\"title\":\"Renamed by a stranger\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.edit")));
        patchIssue(ctx, reporter.token(), mine, "{\"title\":\"Renamed by its reporter\"}")
                .andExpect(status().isOk());

        assert getIssue(ctx, theirs).get("title").asText().equals("Not mine");
        assert getIssue(ctx, mine).get("title").asText().equals("Renamed by its reporter");
    }

    /**
     * {@code attachment.delete:own} — ownership here is the <em>uploader</em>, not the
     * issue's reporter, and both files hang off the same issue so the only thing that
     * differs between the two calls is who uploaded.
     */
    @Test
    void anOwnOnlyAttachmentDeleteGrantReachesTheUploadersOwnFileAndNoOther() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Two files, two uploaders").get("number").asLong();
        var uploader = actorWithCustomProjectRole(ctx, "own-attacher",
                grant(Permission.ATTACHMENT_CREATE), ownGrant(Permission.ATTACHMENT_DELETE));

        var mine = upload(ctx, uploader.token(), number, "mine.txt");
        var theirs = upload(ctx, ctx.token(), number, "theirs.txt");

        mockMvc.perform(delete(attachmentPath(ctx, number, theirs))
                        .header("Authorization", "Bearer " + uploader.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("attachment.delete")));
        mockMvc.perform(delete(attachmentPath(ctx, number, mine))
                        .header("Authorization", "Bearer " + uploader.token()))
                .andExpect(status().isNoContent());

        assert attachmentIds(ctx, number).equals(List.of(theirs))
                : "the other person's file should have survived: " + attachmentIds(ctx, number);
    }

    /**
     * The reverse direction of the same modifier: an <strong>unrestricted</strong> grant
     * covers "not mine" too. Without this the suite could pass with a server that reads
     * every grant as own-only — a narrowing rather than a widening, but still a
     * regression, and one no other assertion here would notice.
     */
    @Test
    void anUnrestrictedGrantCoversSomebodyElsesObject() throws Exception {
        var ctx = newProject();
        long theirs = createIssue(ctx, "The owner filed this").get("number").asLong();
        var moderator = actorWithCustomProjectRole(ctx, "moderator",
                grant(Permission.ISSUE_EDIT), grant(Permission.ISSUE_DELETE));

        patchIssue(ctx, moderator.token(), theirs, "{\"title\":\"Renamed by a moderator\"}")
                .andExpect(status().isOk());
        deleteIssue(ctx, moderator.token(), theirs).andExpect(status().isNoContent());
    }

    // ==================================== 2. the per-field PATCH relaxation (§10.3.3)

    /**
     * <strong>The whole-form patch.</strong> The SPA's issue detail sends every field it
     * rendered, so an actor holding {@code issue.edit} alone routinely PATCHes a body
     * carrying {@code statusId} and {@code assigneeId} at their current values. That must
     * cost nothing beyond {@code issue.edit} — if an unchanged field dragged its
     * permission along, every contributor edit in the product would 403.
     */
    @Test
    void anUnchangedFieldCarriedAlongDemandsNothing() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Original").get("number").asLong();
        var assignee = actorWith(ctx, "MEMBER", "MEMBER");
        // Set the two fields the whole-form patch will carry, as somebody who may.
        patchIssue(ctx, ctx.token(), number,
                "{\"statusId\":\"" + doneStatusId(ctx) + "\",\"assigneeId\":\""
                        + assignee.user().getId() + "\"}")
                .andExpect(status().isOk());

        var editor = actorWithCustomProjectRole(ctx, "editor-only", grant(Permission.ISSUE_EDIT));

        // The real shape: title changes, status and assignee ride along unchanged.
        patchIssue(ctx, editor.token(), number,
                "{\"title\":\"Renamed\",\"statusId\":\"" + doneStatusId(ctx) + "\",\"assigneeId\":\""
                        + assignee.user().getId() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.assignee.id").value(assignee.user().getId().toString()));

        // …and the same actor is still refused the moment either one actually moves.
        patchIssue(ctx, editor.token(), number,
                "{\"title\":\"Renamed\",\"statusId\":\"" + ctx.todoStatusId() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.transition")));
        patchIssue(ctx, editor.token(), number, "{\"clearAssignee\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        var after = getIssue(ctx, number);
        assert after.get("assignee").get("id").asText().equals(assignee.user().getId().toString()) : after;
    }

    /**
     * <strong>The {@code dueDate} two-flag case.</strong> {@code dueDate} present and
     * unchanged is a no-op — but the same payload with {@code clearDueDate:true} clears
     * the date, because the mutation's {@code else if} fires whenever "set" is false. A
     * gate written as one "is dueDate changing" boolean answers false for that body and
     * lets an unpermitted actor wipe the date. Both halves are asserted with the same
     * actor and the same {@code dueDate} value, so only the flag differs.
     */
    @Test
    void anUnchangedDueDateWithTheClearFlagStillNeedsThePermission() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Has a due date").get("number").asLong();
        patchIssue(ctx, ctx.token(), number, "{\"dueDate\":\"2026-12-01\"}")
                .andExpect(status().isOk());

        // Holds the neighbouring permission, so a refusal below cannot be collateral.
        var transitioner = actorWithCustomProjectRole(ctx, "transitioner",
                grant(Permission.ISSUE_TRANSITION));

        // Unchanged, no flag: a no-op, and no-ops are free.
        patchIssue(ctx, transitioner.token(), number, "{\"dueDate\":\"2026-12-01\"}")
                .andExpect(status().isOk());

        // Unchanged, WITH the flag: this really clears it, so it costs issue.edit.
        patchIssue(ctx, transitioner.token(), number,
                "{\"dueDate\":\"2026-12-01\",\"clearDueDate\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.edit")));

        assert getIssue(ctx, number).get("dueDate").asText().equals("2026-12-01")
                : "the refused patch cleared the date anyway: " + getIssue(ctx, number);

        // And the permitted actor really does clear it through that same body — the
        // assertion above must be refusing a mutation that exists.
        var editor = actorWithCustomProjectRole(ctx, "due-editor", grant(Permission.ISSUE_EDIT));
        patchIssue(ctx, editor.token(), number,
                "{\"dueDate\":\"2026-12-01\",\"clearDueDate\":true}")
                .andExpect(status().isOk());
        assert getIssue(ctx, number).get("dueDate").isNull() : getIssue(ctx, number);
    }

    // ============================================== 3. every door, independently (§6.5)

    /**
     * One actor who holds {@code issue.create} + {@code issue.edit} + {@code issue.rank}
     * and <strong>not</strong> {@code sprint.assign}, put through all five doors. The
     * point of the combination: every refusal below is provably about the sprint, because
     * the same actor performs the identical request without the sprint field and is let
     * through.
     */
    @Test
    void sprintAssignIsShutOnEveryDoorForAnActorWhoHoldsEveryNeighbouringGrant() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "Planned work");
        var issueId = UUID.fromString(issue.get("id").asText());
        long number = issue.get("number").asLong();
        var anchor = createIssue(ctx, "A rank anchor");

        var planner = actorWithCustomProjectRole(ctx, "no-sprint-assign",
                grant(Permission.ISSUE_CREATE), grant(Permission.ISSUE_EDIT),
                grant(Permission.ISSUE_RANK));

        // Door 1 — the sprint endpoints.
        addIssuesToSprint(ctx, planner.token(), sprintId, issueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // Door 2 — PATCH. The actor HOLDS issue.edit, so "sprint.assign" is the only
        // thing that can be missing; the negative assertion is what makes it independent.
        patchIssue(ctx, planner.token(), number, "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")))
                .andExpect(jsonPath("$.detail", not(containsString("issue.edit"))));
        // …and the same PATCH shape without the sprint field goes through.
        patchIssue(ctx, planner.token(), number, "{\"title\":\"Renamed\"}")
                .andExpect(status().isOk());

        // Door 3 — POST …/rank carrying a sprint. The actor holds issue.rank, and a
        // plain move proves it.
        rank(ctx, planner.token(), number, "{\"beforeIssueId\":\"" + anchor.get("id").asText() + "\"}")
                .andExpect(status().isOk());
        rank(ctx, planner.token(), number,
                "{\"beforeIssueId\":\"" + anchor.get("id").asText() + "\",\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")))
                .andExpect(jsonPath("$.detail", not(containsString("issue.rank"))));

        // Door 4 — POST /issues carrying a sprint. The actor holds issue.create, and a
        // plain create proves it.
        postIssue(ctx, planner.token(), "Filed into the backlog").andExpect(status().isCreated());
        postIssue(ctx, planner.token(), "Filed into the sprint", "\"sprintId\":\"" + sprintId + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")))
                .andExpect(jsonPath("$.detail", not(containsString("issue.create"))));

        // Door 5 — removal, once a curator has put the issue in.
        addIssuesToSprint(ctx, ctx.token(), sprintId, issueId).andExpect(status().isOk());
        removeIssueFromSprint(ctx, planner.token(), sprintId, issueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));
        patchIssue(ctx, planner.token(), number, "{\"clearSprint\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // Nothing moved: the issue is where the curator left it, and no sprint issue
        // was filed behind the refusals.
        assert sprintId(getIssue(ctx, number)).equals(sprintId.toString()) : getIssue(ctx, number);
    }

    /**
     * The same treatment for {@code issue.assign}: an actor who may edit and file, and
     * may not hand work out — on both doors that carry an assignee.
     */
    @Test
    void issueAssignIsShutOnBothDoorsForAnActorWhoMayOtherwiseEdit() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Unassigned").get("number").asLong();
        var colleague = actorWith(ctx, "MEMBER", "MEMBER");
        var editor = actorWithCustomProjectRole(ctx, "no-assign",
                grant(Permission.ISSUE_CREATE), grant(Permission.ISSUE_EDIT));

        patchIssue(ctx, editor.token(), number,
                "{\"assigneeId\":\"" + colleague.user().getId() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")))
                .andExpect(jsonPath("$.detail", not(containsString("issue.edit"))));
        postIssue(ctx, editor.token(), "For a colleague",
                "\"assigneeId\":\"" + colleague.user().getId() + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        // The neighbouring capabilities are intact, so the two refusals are about the
        // assignee and nothing else.
        patchIssue(ctx, editor.token(), number, "{\"title\":\"Renamed\"}").andExpect(status().isOk());
        postIssue(ctx, editor.token(), "Filed unassigned").andExpect(status().isCreated());

        assert getIssue(ctx, number).get("assignee").isNull() : getIssue(ctx, number);
    }

    // ============================== 4. the actor's refusal precedes the value's (§10.3.4)

    /**
     * <strong>403 before 422.</strong> An actor lacking {@code issue.assign} who names a
     * target who could not be assigned anyway must be told about <em>their own</em>
     * missing permission — not about the target. Reversing the order would turn the 422
     * into an oracle: anyone could probe who holds {@code issue.assignable} in a project
     * they have no rights in at all, and would be told "unknown assignee" for users
     * outside the workspace into the bargain.
     *
     * <p>Since the HD-125 review (L4) this holds for <em>every</em> assignee value,
     * including one that resolves to nobody: the change is detected from the raw id, so
     * the assignee is not looked up at all until the 403 has been answered.
     */
    @Test
    void anActorWhoMayNotAssignIsRefusedBeforeTheTargetIsJudged() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Needs an owner").get("number").asLong();
        // A colleague who may NOT be assigned here (a Viewer holds no issue.assignable).
        var unassignable = actorWith(ctx, "MEMBER", "VIEWER");
        var editor = actorWithCustomProjectRole(ctx, "no-assign-order",
                grant(Permission.ISSUE_CREATE), grant(Permission.ISSUE_EDIT));

        patchIssue(ctx, editor.token(), number,
                "{\"assigneeId\":\"" + unassignable.user().getId() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        // An id that names NOBODY in this workspace is ALSO a 403 now — this assertion
        // used to record the opposite, with a note that the fix would be "to hoist the
        // permission block above the resolve phase", and the HD-125 review (L4) took that
        // option: `assigneeChanges` is computed from the raw id, so nothing about the
        // value is resolved until the actor's own entitlement has been answered. It was
        // a small leak — the two answers distinguished "is a member of this workspace"
        // from "is not", for a caller who may not assign at all — and closing it made the
        // whole endpoint one rule instead of two.
        patchIssue(ctx, editor.token(), number, "{\"assigneeId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        // The create path orders the two the same way.
        postIssue(ctx, editor.token(), "Also needs an owner",
                "\"assigneeId\":\"" + unassignable.user().getId() + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        // …and the actor who DOES hold issue.assign gets the value-level answer, which
        // is what makes the ordering above observable rather than vacuous.
        patchIssue(ctx, ctx.token(), number,
                "{\"assigneeId\":\"" + unassignable.user().getId() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("That user cannot be assigned in this project"));
    }

    // ==================================================== fixtures

    private record Grant(Permission permission, boolean ownOnly) {}

    private static Grant grant(Permission permission) {
        return new Grant(permission, false);
    }

    private static Grant ownGrant(Permission permission) {
        return new Grant(permission, true);
    }

    /**
     * A workspace member holding a one-off custom project role granting exactly
     * {@code grants}. Written through the {@link EntityManager} because
     * {@code RoleRepository} deliberately exposes no {@code save} until S4 ships the role
     * editor — and the role is workspace-owned, so it disappears with the fixture.
     */
    private Actor actorWithCustomProjectRole(Ctx ctx, String key, Grant... grants) throws Exception {
        var actor = actorWith(ctx, "MEMBER", null);
        var roleId = txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ctx.wsId());
            role.setScope(RoleScope.PROJECT);
            role.setKey(key);
            role.setName(key);
            role.setBuiltIn(false);
            var permissions = new LinkedHashSet<RolePermission>();
            for (var g : grants) permissions.add(new RolePermission(g.permission(), g.ownOnly()));
            role.setPermissions(permissions);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
        var member = new ProjectMember();
        member.setProject(projectRepository.findById(ctx.projectId()).orElseThrow());
        member.setUser(actor.user());
        member.setRole(roleCatalog.reference(roleId));
        projectMemberRepository.save(member);
        return actor;
    }

    private ResultActions deleteIssue(Ctx ctx, String token, long number) throws Exception {
        return mockMvc.perform(delete(ctx.issuesBase() + "/" + number)
                .header("Authorization", "Bearer " + token));
    }

    private long numberOfPosted(ResultActions created) throws Exception {
        return json.readTree(created.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("number").asLong();
    }

    private String attachmentPath(Ctx ctx, long number, String attachmentId) {
        return ctx.issuesBase() + "/" + number + "/attachments/" + attachmentId;
    }

    /** Upload a small file as {@code token} and return the attachment id. */
    private String upload(Ctx ctx, String token, long number, String filename) throws Exception {
        var file = new MockMultipartFile("file", filename, "text/plain", "hello".getBytes());
        var body = mockMvc.perform(multipart(ctx.issuesBase() + "/" + number + "/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private List<String> attachmentIds(Ctx ctx, long number) throws Exception {
        var body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(ctx.issuesBase() + "/" + number + "/attachments")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ids = new ArrayList<String>();
        for (var node : json.readTree(body)) ids.add(node.get("id").asText());
        return ids;
    }
}
