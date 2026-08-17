package com.hamstrack.issue;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-125 (S2) — the project-scoped permissions actually bite</strong>
 * (roles-permissions-proposal §15, criteria 4–8).
 *
 * <p>{@code PermissionParityTest} proves the mapping did not change anyone's verdict.
 * This proves the other half, which parity cannot: that the new predicates are wired into
 * the HTTP surface at all, and with the shapes §10.3 specifies — per-field gating on
 * {@code PATCH /issues/{n}}, every door of {@code sprint.assign}, and the target-side
 * {@code issue.assignable} check with its 422.
 *
 * <p>The actors are built-in roles wherever a built-in expresses the case (a Viewer holds
 * nothing; a Commenter holds no {@code sprint.assign}) and a one-off custom role where no
 * built-in does — see {@link #actorWithCustomProjectRole}. That role is the only place
 * this suite reaches past the API, and it exists because the third sprint door
 * ({@code POST …/rank} carrying {@code sprintId}) can only be observed by someone who has
 * {@code issue.rank} <em>without</em> {@code sprint.assign}, a combination no built-in
 * ships.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssuePermissionEnforcementTest extends SprintTestBase {

    // ==================================================== criterion 4 — the Viewer

    @Test
    void aProjectViewerReadsEverythingAndChangesNothing() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "Filed by the owner");
        long number = issue.get("number").asLong();
        var neighbour = createIssue(ctx, "A rank anchor");
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);

        // ---- every read still works: v1 gates writes, never visibility (§3) ----
        mockMvc.perform(get(ctx.issuesBase() + "/" + number)
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get(ctx.issuesBase() + "?size=50")
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk());

        // ---- …and every write is a 403 that names the missing permission ----
        postIssue(ctx, viewer.token(), "nope")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.create")));
        patchIssue(ctx, viewer.token(), number, "{\"title\":\"nope\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.edit")));
        patchIssue(ctx, viewer.token(), number, "{\"statusId\":\"" + doneStatusId(ctx) + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.transition")));
        rank(ctx, viewer.token(), number,
                "{\"beforeIssueId\":\"" + neighbour.get("id").asText() + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.rank")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(ctx.issuesBase() + "/" + number)
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.delete")));

        // Nothing leaked through any of the refusals.
        var after = getIssue(ctx, number);
        assert after.get("title").asText().equals("Filed by the owner") : after;
    }

    // ============================= criteria 5 & 7 — per-field PATCH (§10.3.3)

    /**
     * The rule that keeps the SPA working: it sends whole-form patches, so a field
     * carrying its <em>current</em> value must demand nothing. The second and third calls
     * are the proof that the gating is per-field rather than per-request — the same
     * payload shape yields a different missing permission depending on which half of it
     * actually changes.
     */
    @Test
    void onlyTheFieldsThatActuallyChangeAreGated() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Original").get("number").asLong();
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);
        var unchangedStatus = ctx.todoStatusId();

        // A whole-form patch in which NOTHING changes needs no permission at all — even
        // for an actor who holds none.
        patchIssue(ctx, viewer.token(), number,
                "{\"title\":\"Original\",\"statusId\":\"" + unchangedStatus + "\"}")
                .andExpect(status().isOk());

        // Same title (unchanged), new status → issue.transition, and issue.edit is NOT
        // asked for: an unchanged field must not drag its permission into the request.
        patchIssue(ctx, viewer.token(), number,
                "{\"title\":\"Original\",\"statusId\":\"" + doneStatusId(ctx) + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.transition")))
                .andExpect(jsonPath("$.detail", not(containsString("issue.edit"))));

        // New title, same status → issue.edit.
        patchIssue(ctx, viewer.token(), number,
                "{\"title\":\"Renamed\",\"statusId\":\"" + unchangedStatus + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.edit")));

        var after = getIssue(ctx, number);
        assert after.get("title").asText().equals("Original") : after;
    }

    // ==================================== criterion 6 — sprint.assign, every door

    @Test
    void sprintAssignmentIsGatedOnEveryDoor() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "Planned work");
        var issueId = UUID.fromString(issue.get("id").asText());
        long number = issue.get("number").asLong();
        // A Commenter may talk, and may not plan: no sprint.assign in the built-in set.
        var commenter = actorWithBuiltInProjectRole(ctx, "COMMENTER");

        // Door 1 — the sprint side.
        addIssuesToSprint(ctx, commenter.token(), sprintId, issueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));
        // Door 2 — the issue side.
        patchIssue(ctx, commenter.token(), number, "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // Put it in as the curator, then check both removal doors.
        addIssuesToSprint(ctx, ctx.token(), sprintId, issueId).andExpect(status().isOk());
        removeIssueFromSprint(ctx, commenter.token(), sprintId, issueId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));
        patchIssue(ctx, commenter.token(), number, "{\"clearSprint\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // Door 3 — POST …/rank also carries sprintId (§6.5 names only the first two).
        // The actor holds issue.rank and nothing else, so a plain move succeeds and the
        // same request with a sprint change does not.
        var ranker = actorWithCustomProjectRole(ctx, "ranker", Permission.ISSUE_RANK);
        var anchor = createIssue(ctx, "A backlog anchor");
        var mover = createIssue(ctx, "Moved in the backlog");
        // A pure move inside one section is fine — issue.rank is all it needs.
        rank(ctx, ranker.token(), mover.get("number").asLong(),
                "{\"beforeIssueId\":\"" + anchor.get("id").asText() + "\"}")
                .andExpect(status().isOk());
        rank(ctx, ranker.token(), number, "{\"clearSprint\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // The issue is still where the curator put it.
        var after = getIssue(ctx, number);
        assert after.get("sprint") != null && !after.get("sprint").isNull() : after;
    }

    /**
     * The create path carries an assignee and a sprint too, so it is a door for
     * {@code issue.assign} and {@code sprint.assign} as much as {@code PATCH} is (§6.5) —
     * otherwise the bypass ({@code POST} with the field) is shorter than the guarded path
     * ({@code POST} then {@code PATCH}). Only what the payload carries is gated: the
     * plain create below succeeds for an actor holding {@code issue.create} alone.
     */
    @Test
    void theCreatePathGatesTheAssigneeAndTheSprintItCarries() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var filer = actorWithCustomProjectRole(ctx, "filer", Permission.ISSUE_CREATE);

        // Carrying nothing but a title: issue.create is the whole requirement.
        postIssue(ctx, filer.token(), "Just filing this").andExpect(status().isCreated());

        postIssue(ctx, filer.token(), "For a colleague",
                "\"assigneeId\":\"" + ctx.owner().getId() + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        postIssue(ctx, filer.token(), "Straight into the sprint",
                "\"sprintId\":\"" + sprintId + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("sprint.assign")));

        // Catalog order decides which one a payload missing both is told about first.
        postIssue(ctx, filer.token(), "Both at once",
                "\"assigneeId\":\"" + ctx.owner().getId() + "\"",
                "\"sprintId\":\"" + sprintId + "\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.assign")));

        // The refusals filed nothing: only the first, permitted create exists.
        var backlog = backlog(ctx, null);
        assert pageTitles(backlog).equals(java.util.Set.of("Just filing this")) : backlog;
    }

    /**
     * {@code POST …/sprints/{id}/issues} writes {@code issues.position}, which is the
     * <strong>project-wide</strong> backlog rank — so re-ranking there under
     * {@code sprint.assign} alone was a back door onto {@code issue.rank} (HD-125 review,
     * M2): drop into a sprint at TOP, take it out again (removal preserves the rank) and
     * the issue comes back to the backlog re-ranked. An actor without {@code issue.rank}
     * may still move issues in and out; their rank is simply preserved.
     */
    @Test
    void theSprintDoorCannotBeUsedToReRankTheBacklog() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        // A new issue lands at the BOTTOM of the backlog (§3.3.1), so filing these first
        // makes the target genuinely last — otherwise "move it to the top" is a no-op and
        // the test would pass without the rank ever being written.
        createIssue(ctx, "Filed first");
        createIssue(ctx, "Filed second");
        var bottom = createIssue(ctx, "Filed last");
        var bottomId = UUID.fromString(bottom.get("id").asText());
        long bottomNumber = bottom.get("number").asLong();

        // sprint.assign WITHOUT issue.rank — the "planner who may not reprioritise" role.
        var planner = actorWithCustomProjectRole(ctx, "planner", Permission.SPRINT_ASSIGN);

        var before = backlogOrder(ctx);
        addIssuesToSprint(ctx, planner.token(), sprintId, bottomId).andExpect(status().isOk());
        removeIssueFromSprint(ctx, planner.token(), sprintId, bottomId).andExpect(status().isNoContent());

        // The membership round trip happened (that is sprint.assign's job) and the order
        // did not move (that is issue.rank's).
        assert getIssue(ctx, bottomNumber).get("sprint").isNull() : "the issue is still in the sprint";
        assert backlogOrder(ctx).equals(before)
                : "the backlog was re-ranked through the sprint door: " + before
                  + " became " + backlogOrder(ctx);

        // The same round trip by someone who DOES hold issue.rank still re-ranks.
        addIssuesToSprint(ctx, ctx.token(), sprintId, "{\"issueIds\":[\"" + bottomId + "\"],"
                + "\"position\":\"TOP\"}").andExpect(status().isOk());
        removeIssueFromSprint(ctx, ctx.token(), sprintId, bottomId).andExpect(status().isNoContent());
        assert !backlogOrder(ctx).equals(before)
                : "issue.rank stopped re-ranking on the sprint door — the fix over-corrected";
    }

    /** Issue titles in backlog (rank) order. */
    private java.util.List<String> backlogOrder(Ctx ctx) throws Exception {
        var page = backlog(ctx, null);
        var titles = new java.util.ArrayList<String>();
        page.get("content").forEach(n -> titles.add(n.get("title").asText()));
        return titles;
    }

    // ================================ criterion 8 — issue.assignable (the TARGET)

    @Test
    void aTargetWhoMayNotBeGivenWorkIs422AndNotConfusedWithAStranger() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Needs an owner").get("number").asLong();
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);

        // A real colleague who simply may not be assigned here: the caller is entitled to
        // ask, so the VALUE is what is refused — 422, with a message that does not
        // pretend the person does not exist.
        patchIssue(ctx, ctx.token(), number,
                "{\"assigneeId\":\"" + viewer.user().getId() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("That user cannot be assigned in this project"));

        // …and the create path enforces the same rule, for the same reason.
        postIssue(ctx, ctx.token(), "Needs an owner too",
                "\"assigneeId\":\"" + viewer.user().getId() + "\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("That user cannot be assigned in this project"));

        // The other branch, and the one that actually matters for tenancy: a REAL user who
        // is a member of a DIFFERENT workspace. A random UUID dies at findById and never
        // reaches the membership filter, so it proves nothing about cross-tenant leakage.
        var stranger = newProject();   // its own workspace, its own owner
        patchIssue(ctx, ctx.token(), number,
                "{\"assigneeId\":\"" + stranger.owner().getId() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown assignee"));

        // …and it must be the IDENTICAL string a non-existent id gets, or the 422 becomes
        // an oracle for "does this user id exist somewhere in the product".
        patchIssue(ctx, ctx.token(), number, "{\"assigneeId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown assignee"));

        var after = getIssue(ctx, number);
        assert after.get("assignee").isNull() : after;
    }

    /**
     * The actor's 403 must precede the value's 422 (HD-125 review, L4). Resolving the
     * assignee with the other reads used to answer "Unknown assignee" (= not a member of
     * this workspace) <em>before</em> {@code issue.assign} was checked, which let anyone
     * who can PATCH an issue — including someone who may not assign at all — probe
     * arbitrary user ids for membership. Create always got this right; now both do.
     */
    @Test
    void theActorsForbiddenComesBeforeTheValues422() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Unassigned").get("number").asLong();
        // issue.edit but NOT issue.assign — the role that makes the ordering observable.
        var editor = actorWithCustomProjectRole(ctx, "editor", Permission.ISSUE_EDIT);
        var stranger = newProject();

        // A member of another workspace, and a user id that exists nowhere: both must be
        // 403 for this actor, and must not be distinguishable from each other.
        for (var id : java.util.List.of(stranger.owner().getId().toString(), UUID.randomUUID().toString())) {
            patchIssue(ctx, editor.token(), number, "{\"assigneeId\":\"" + id + "\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail", containsString("issue.assign")));
        }

        // Re-sending the CURRENT (absent) assignee is not a change, so it needs nothing —
        // the same "present and actually changing" rule the rest of the PATCH follows.
        patchIssue(ctx, editor.token(), number, "{\"title\":\"Renamed\",\"clearAssignee\":true}")
                .andExpect(status().isOk());
    }

    /**
     * §11.5, the non-destructive rule shared with archived components and delivery
     * Rule B: revoking {@code issue.assignable} unassigns nobody, and a whole-form PATCH
     * that re-sends the existing assignee is not "a new assignment".
     */
    @Test
    void anExistingAssignmentSurvivesAfterTheAssigneeLosesTheRight() throws Exception {
        var ctx = newProject();
        long number = createIssue(ctx, "Assigned early").get("number").asLong();
        var worker = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MEMBER);

        patchIssue(ctx, ctx.token(), number, "{\"assigneeId\":\"" + worker.user().getId() + "\"}")
                .andExpect(status().isOk());

        // The team decides this person no longer takes work here.
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        var membership = projectMemberRepository.findByProjectAndUser(project, worker.user()).orElseThrow();
        membership.setRole(roleCatalog.reference(ProjectRole.VIEWER));
        projectMemberRepository.save(membership);

        // Nothing is unassigned, and an unrelated edit that re-sends the same assignee
        // still goes through.
        patchIssue(ctx, ctx.token(), number,
                "{\"title\":\"Renamed\",\"assigneeId\":\"" + worker.user().getId() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(worker.user().getId().toString()));

        // Only a genuinely NEW assignment is refused.
        long other = createIssue(ctx, "A second issue").get("number").asLong();
        patchIssue(ctx, ctx.token(), other, "{\"assigneeId\":\"" + worker.user().getId() + "\"}")
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== fixtures

    /** A workspace member holding a <em>built-in</em> project role by key. */
    private Actor actorWithBuiltInProjectRole(Ctx ctx, String key) throws Exception {
        var actor = actorWith(ctx, WorkspaceRole.MEMBER, null);
        var member = new ProjectMember();
        member.setProject(projectRepository.findById(ctx.projectId()).orElseThrow());
        member.setUser(actor.user());
        member.setRole(roleCatalog.reference(roleCatalog.builtIn(RoleScope.PROJECT, key).id()));
        projectMemberRepository.save(member);
        return actor;
    }

}
