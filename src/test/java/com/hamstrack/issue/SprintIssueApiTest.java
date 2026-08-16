package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §4.5 / §4.9 — the issue-facing half of the release: the {@code sprint} and
 * {@code storyPoints} payload fields, the {@code ?sprintId=}/{@code ?noSprint=} filters,
 * the planning view's shape, and sprint-name/date validation.
 *
 * <p>Two contract details that are easy to lose and expensive to notice later:
 * {@code position} (the shared rank) must <strong>never</strong> appear on
 * {@code IssueResponse} — it is server-written only — and {@code storyPoints} must
 * serialize as a clean JSON number ({@code 5}, not {@code 5.00}) on every surface.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintIssueApiTest extends SprintTestBase {

    // ===================================================== story points

    @Test
    void storyPointsAcceptsTheEstimationScaleAndRejectsEverythingElse() throws Exception {
        var ctx = newProject();

        // 0 (free), a half point, and the ceiling all land — and come back normalized.
        var zero = createIssue(ctx, "zero", "\"storyPoints\":0");
        var half = createIssue(ctx, "half", "\"storyPoints\":0.5");
        var max = createIssue(ctx, "max", "\"storyPoints\":999");
        assert zero.get("storyPoints").asDouble() == 0.0 : zero;
        assert half.get("storyPoints").asDouble() == 0.5 : half;
        assert max.get("storyPoints").asDouble() == 999.0 : max;
        assert max.get("storyPoints").asText().equals("999")
                : "a point value must be a plain number, not scientific notation: " + max;

        // null is UNESTIMATED, which is deliberately not 0.
        var none = createIssue(ctx, "unestimated");
        assert none.get("storyPoints").isNull() : none;

        // …and the out-of-range / over-precise ones are 422, on create AND on update.
        for (String bad : List.of("-1", "1000", "1.234")) {
            postIssue(ctx, ctx.token(), "bad " + bad, "\"storyPoints\":" + bad)
                    .andExpect(status().isUnprocessableContent());
            patchIssue(ctx, ctx.token(), numberOf(zero), "{\"storyPoints\":" + bad + "}")
                    .andExpect(status().isUnprocessableContent());
        }
        assert getIssue(ctx, numberOf(zero)).get("storyPoints").asDouble() == 0.0
                : "a rejected estimate half-applied";
    }

    @Test
    void changingStoryPointsIsAuditedOnceAndANoOpIsNot() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "work");
        long number = numberOf(issue);

        // create-time values write no history (the existing convention)
        var estimated = createIssue(ctx, "estimated at birth", "\"storyPoints\":3");
        assert historyFields(ctx, numberOf(estimated)).isEmpty()
                : "create-time values must not be audited";

        patchIssue(ctx, ctx.token(), number, "{\"storyPoints\":5}").andExpect(status().isOk());
        assert count(historyFields(ctx, number), "storyPoints") == 1 : "the change was not audited";

        // 5 and 5.00 are the same estimate — a no-op writes nothing
        patchIssue(ctx, ctx.token(), number, "{\"storyPoints\":5.00}").andExpect(status().isOk());
        assert count(historyFields(ctx, number), "storyPoints") == 1
                : "a re-statement of the same estimate was audited as a change";

        // …and clearing it marks the issue unestimated again
        patchIssue(ctx, ctx.token(), number, "{\"clearStoryPoints\":true}").andExpect(status().isOk());
        assert getIssue(ctx, number).get("storyPoints").isNull() : "clearStoryPoints did not unset";
        assert count(historyFields(ctx, number), "storyPoints") == 2 : "the clear was not audited";

        // a second clear is a no-op
        patchIssue(ctx, ctx.token(), number, "{\"clearStoryPoints\":true}").andExpect(status().isOk());
        assert count(historyFields(ctx, number), "storyPoints") == 2;
    }

    // ===================================================== the issue payload

    @Test
    void sprintAndStoryPointsRideEverySurfaceAndPositionRidesNone() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        // The parent has to be exactly one hierarchy level above the child, so it is an
        // Epic (level 2) while `createIssue` files Tasks (level 1).
        var parent = createEpic(ctx, "parent");
        var child = createIssue(ctx, "child",
                "\"parentId\":\"" + idOf(parent) + "\",\"sprintId\":\"" + sprintId + "\"");

        // single GET
        var single = getIssue(ctx, numberOf(child));
        assertShape(single);
        assert "Sprint 1".equals(sprintName(single)) : single;

        // the board (capped list)
        for (var row : board(ctx, null).get("issues")) assertShape(row);
        // the paged backlog list
        for (var row : backlog(ctx, null).get("content")) assertShape(row);
        // the children list
        var children = json.readTree(mockMvc.perform(
                        get(ctx.issuesBase() + "/" + numberOf(parent) + "/children")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assert !children.isEmpty() : "the parent must have a child row to assert on";
        for (var row : children) assertShape(row);
        // the planning view
        var view = backlogView(ctx);
        for (var row : sprintSection(view, sprintId).get("issues")) assertShape(row);
        for (var row : view.get("backlog").get("issues")) assertShape(row);
    }

    /** Every issue payload must carry the two new fields and must NOT carry the rank. */
    private static void assertShape(JsonNode issue) {
        assert issue.has("sprint") : "IssueResponse lost `sprint`: " + issue;
        assert issue.has("storyPoints") : "IssueResponse lost `storyPoints`: " + issue;
        assert !issue.has("position")
                : "`position` is the server-written rank and must never be exposed: " + issue;
    }

    // ===================================================== filters

    @Test
    void theSprintFiltersComposeWithTheExistingOnesAndContradictThemselvesLoudly()
            throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var componentId = createComponent(ctx, "Billing");
        var inSprint = createIssue(ctx, "in sprint", "\"componentId\":\"" + componentId + "\"");
        var alsoInSprint = createIssue(ctx, "also in sprint");
        createIssue(ctx, "in the backlog");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(inSprint), idOf(alsoInSprint))
                .andExpect(status().isOk());

        assert titles(board(ctx, "?sprintId=" + sprintId))
                .equals(java.util.Set.of("in sprint", "also in sprint"));
        assert titles(board(ctx, "?noSprint=true")).equals(java.util.Set.of("in the backlog"));
        // composes (AND) with the pre-existing filters
        assert titles(board(ctx, "?sprintId=" + sprintId + "&componentId=" + componentId))
                .equals(java.util.Set.of("in sprint"));
        // …and on the paged variant too
        assert pageTitles(backlog(ctx, "&sprintId=" + sprintId))
                .equals(java.util.Set.of("in sprint", "also in sprint"));

        // "in this sprint" AND "in no sprint" can never both hold — a malformed request,
        // not a silently empty result the caller would misread as "nothing matches".
        mockMvc.perform(get(ctx.issuesBase() + "?sprintId=" + sprintId + "&noSprint=true")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not both")));
    }

    // ===================================================== the planning view

    @Test
    void thePlanningViewIsActiveFirstThenFutureWithTheBacklogHidingDoneWork() throws Exception {
        var ctx = newProject();
        var future = createSprint(ctx, "Sprint 2");
        var active = startedSprint(ctx, "Sprint 1");

        var committed = createIssue(ctx, "committed", "\"storyPoints\":3");
        var delivered = createIssue(ctx, "delivered", "\"storyPoints\":2");
        var planned = createIssue(ctx, "planned next");
        var loose = createIssue(ctx, "loose");
        var doneInBacklog = createIssue(ctx, "done in the backlog");
        addIssuesToSprint(ctx, ctx.token(), active, idOf(committed), idOf(delivered))
                .andExpect(status().isOk());
        addIssuesToSprint(ctx, ctx.token(), future, idOf(planned)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));
        markDone(ctx, numberOf(doneInBacklog));

        var view = backlogView(ctx);
        assert view.get("sectionCap").asInt() > 0 : view;
        var sections = view.get("sprints");
        assert sections.size() == 2 : "only OPEN sprints get a section: " + view;
        assert sections.get(0).get("sprint").get("id").asText().equals(active.toString())
                : "the ACTIVE sprint must come first: " + view;
        assert sections.get(1).get("sprint").get("id").asText().equals(future.toString()) : view;

        // The active section keeps its DONE issue — that is the sprint's record — and its
        // stats report both halves.
        var activeSection = sections.get(0);
        assert activeSection.get("issues").size() == 2 : activeSection;
        var stats = activeSection.get("stats");
        assert stats.get("issueCount").asInt() == 2 : stats;
        assert stats.get("doneIssueCount").asInt() == 1 : stats;
        assert stats.get("points").asDouble() == 5.0 : stats;
        assert stats.get("donePoints").asDouble() == 2.0 : stats;
        assert stats.get("unestimatedCount").asInt() == 0 : stats;
        assert !activeSection.get("truncated").asBoolean() : activeSection;
        assert activeSection.get("totalAvailable").asInt() == 2 : activeSection;

        // The BACKLOG hides done work by default (a done, unranked issue is planning noise)…
        var backlogTitles = new ArrayList<String>();
        for (var row : view.get("backlog").get("issues")) backlogTitles.add(row.get("title").asText());
        assert backlogTitles.equals(List.of("loose")) : backlogTitles;
        assert view.get("backlog").get("stats").get("issueCount").asInt() == 1 : view.get("backlog");

        // …and includeDone brings it back.
        var withDone = backlogView(ctx, ctx.token(), "?includeDone=true");
        var withDoneTitles = new ArrayList<String>();
        for (var row : withDone.get("backlog").get("issues")) withDoneTitles.add(row.get("title").asText());
        assert withDoneTitles.equals(List.of("loose", "done in the backlog")) : withDoneTitles;
        assert withDone.get("backlog").get("stats").get("doneIssueCount").asInt() == 1
                : withDone.get("backlog");
    }

    // ===================================================== sprint validation

    @Test
    void sprintNamesAreNormalizedBoundedAndUniquePerProject() throws Exception {
        var ctx = newProject();
        createSprint(ctx, "Sprint 1");

        // case-insensitive uniqueness inside one project
        postSprint(ctx, ctx.token(), "{\"name\":\"sprint 1\"}").andExpect(status().isConflict());
        // …including against a COMPLETED sprint (it keeps its name slot)
        var running = startedSprint(ctx, "Sprint 9");
        completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());
        postSprint(ctx, ctx.token(), "{\"name\":\"Sprint 9\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("completed sprint")));

        // blank after normalization → the sequence-based default (never a 400 on create)
        var defaulted = createSprint(ctx, ctx.token(), "{\"name\":\"   \"}");
        assert sprintNode(ctx, defaulted).get("name").asText().startsWith("Sprint ") : "not defaulted";

        // 61 characters → 400
        postSprint(ctx, ctx.token(), "{\"name\":\"" + "x".repeat(61) + "\"}")
                .andExpect(status().isBadRequest());
        // …and a rename to blank IS a 400 (a PATCH that names it has to name it something)
        patchSprint(ctx, ctx.token(), defaulted, "{\"name\":\"  \"}")
                .andExpect(status().isBadRequest());

        // …but a sibling PROJECT may reuse the name: uniqueness is per project.
        var sibling = siblingProject(ctx);
        postSprint(sibling, sibling.token(), "{\"name\":\"Sprint 1\"}").andExpect(status().isCreated());
    }

    @Test
    void anInvertedDateRangeIs422OnCreatePatchAndStart() throws Exception {
        var ctx = newProject();
        String start = "2026-09-01T09:00:00Z";
        String beforeStart = "2026-08-01T09:00:00Z";

        postSprint(ctx, ctx.token(),
                "{\"name\":\"Bad\",\"startAt\":\"" + start + "\",\"endAt\":\"" + beforeStart + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("after its start date")));

        var sprintId = createSprint(ctx, ctx.token(),
                "{\"name\":\"Planned\",\"startAt\":\"" + start + "\"}");
        patchSprint(ctx, ctx.token(), sprintId, "{\"endAt\":\"" + beforeStart + "\"}")
                .andExpect(status().isUnprocessableContent());
        startSprint(ctx, ctx.token(), sprintId,
                "{\"startAt\":\"" + start + "\",\"endAt\":\"" + beforeStart + "\"}")
                .andExpect(status().isUnprocessableContent());

        assert sprintNode(ctx, sprintId).get("state").asText().equals("FUTURE")
                : "a rejected start half-applied";
    }

    // ===================================================== assignment rules

    @Test
    void aCompletedSprintRefusesNewIssuesButKeepsTheOnesItDelivered() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var delivered = createIssue(ctx, "delivered");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(delivered)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        var latecomer = createIssue(ctx, "latecomer");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(latecomer))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("completed")));
        patchIssue(ctx, ctx.token(), numberOf(latecomer), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isUnprocessableContent());
        postIssue(ctx, ctx.token(), "born late", "\"sprintId\":\"" + sprintId + "\"")
                .andExpect(status().isUnprocessableContent());
        rank(ctx, ctx.token(), numberOf(latecomer), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isUnprocessableContent());

        // …while the issue it delivered is still editable, sprint and all.
        patchIssue(ctx, ctx.token(), numberOf(delivered), "{\"title\":\"delivered, renamed\"}")
                .andExpect(status().isOk());
        assert sprintId.toString().equals(sprintId(getIssue(ctx, numberOf(delivered))))
                : "editing a delivered issue detached it from its completed sprint";
    }

    /**
     * The mirror of the rule above, added by the 0.13.0 review: a COMPLETED sprint is
     * frozen on the <strong>removal</strong> side too.
     *
     * <p>Freezing only the assignment side left the delivery record {@code complete}
     * reported silently rewritable — any member could strip the DONE issues out of a
     * closed sprint and its "8 of 10 points delivered" would quietly become a different
     * number. All three detach routes must refuse with the same 422:
     * {@code DELETE …/sprints/{id}/issues/{issueId}}, {@code clearSprint}/a re-assignment
     * on {@code PATCH …/issues/{number}}, and a sprint change on the rank endpoint.
     */
    @Test
    void aCompletedSprintIsFrozenOnTheRemovalSideToo() throws Exception {
        var ctx = newProject();
        var closed = startedSprint(ctx, "Sprint 1");
        var delivered = createIssue(ctx, "delivered");
        var alsoDelivered = createIssue(ctx, "delivered too");
        addIssuesToSprint(ctx, ctx.token(), closed, idOf(delivered), idOf(alsoDelivered))
                .andExpect(status().isOk());
        // Only a DONE issue stays behind when the sprint closes — that is the record.
        markDone(ctx, numberOf(delivered));
        markDone(ctx, numberOf(alsoDelivered));
        completeToBacklog(ctx, ctx.token(), closed).andExpect(status().isOk());
        var open = createSprint(ctx, "Sprint 2");
        long historyBefore = historyFields(ctx, numberOf(delivered)).size();

        // 1) the explicit removal endpoint
        removeIssueFromSprint(ctx, ctx.token(), closed, idOf(delivered))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Sprint 'Sprint 1' is completed"));

        // 2) the sprint branch of the issue PATCH — both "take it out" and "move it on"
        patchIssue(ctx, ctx.token(), numberOf(delivered), "{\"clearSprint\":true}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is completed")));
        patchIssue(ctx, ctx.token(), numberOf(delivered), "{\"sprintId\":\"" + open + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is completed")));

        // 3) the sprint-change branch of the rank endpoint (a drag out of the section)
        rank(ctx, ctx.token(), numberOf(delivered), "{\"clearSprint\":true}")
                .andExpect(status().isUnprocessableContent());
        rank(ctx, ctx.token(), numberOf(delivered), "{\"sprintId\":\"" + open + "\"}")
                .andExpect(status().isUnprocessableContent());

        // Nothing moved and nothing was audited: a refused detach must not half-apply.
        assert closed.toString().equals(sprintId(getIssue(ctx, numberOf(delivered))))
                : "a refused detach still took the issue out of its completed sprint";
        assert historyFields(ctx, numberOf(delivered)).size() == historyBefore
                : "a refused detach wrote history";

        // …while a PURE rank move INSIDE the completed section is still allowed: it
        // changes no membership, so the delivery record is untouched. Only the sprint
        // change is frozen, not the ordering of what the sprint delivered.
        rankAfter(ctx, numberOf(alsoDelivered), idOf(delivered)).andExpect(status().isOk());
        assert closed.toString().equals(sprintId(getIssue(ctx, numberOf(alsoDelivered))))
                : "a rank move inside a completed section changed its membership";
    }

    /**
     * The <strong>fourth</strong> detach route, and the one the L1 fix was actually
     * written for: {@code POST …/sprints/{openId}/issues} with an issue that currently
     * sits in a COMPLETED sprint. Assigning INTO a sprint detaches from whatever sprint
     * the issue is in today, so this endpoint is a removal path too — freezing only
     * {@code DELETE …/issues/{id}}, the PATCH branch and the rank branch would leave a
     * closed sprint's delivery record rewritable by "just add it to the next sprint".
     *
     * <p>The batch carries an innocent backlog issue alongside the frozen one, which pins
     * the second half of the contract: {@code SprintService.addIssues} validates the whole
     * {@code changing} set BEFORE it mutates anything, so one frozen member fails the
     * request atomically instead of moving the rest and 422ing afterwards.
     */
    @Test
    void addingToAnOpenSprintCannotPullAnIssueOutOfACompletedOne() throws Exception {
        var ctx = newProject();
        var closed = startedSprint(ctx, "Sprint 1");
        var delivered = createIssue(ctx, "delivered");
        addIssuesToSprint(ctx, ctx.token(), closed, idOf(delivered)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));
        completeToBacklog(ctx, ctx.token(), closed).andExpect(status().isOk());

        var open = createSprint(ctx, "Sprint 2");
        var innocent = createIssue(ctx, "still in the backlog");
        long deliveredHistoryBefore = historyFields(ctx, numberOf(delivered)).size();

        addIssuesToSprint(ctx, ctx.token(), open, idOf(delivered), idOf(innocent))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is completed")))
                .andExpect(jsonPath("$.detail").value("Sprint 'Sprint 1' is completed"));

        // The frozen one never left the sprint that delivered it, and nothing was audited.
        assert closed.toString().equals(sprintId(getIssue(ctx, numberOf(delivered))))
                : "an add-to-another-sprint pulled an issue out of its completed sprint";
        assert historyFields(ctx, numberOf(delivered)).size() == deliveredHistoryBefore
                : "a refused batch wrote history for the frozen issue";

        // …and the innocent member of the same batch did NOT move: the validation runs
        // over the whole set before the first write, so the request is all-or-nothing.
        assert sprintName(getIssue(ctx, numberOf(innocent))) == null
                : "a batch rejected for one frozen member still moved the others";
        assert historyFields(ctx, numberOf(innocent)).isEmpty()
                : "a refused batch wrote history for an issue that never moved";
        assert backlogKeys(backlogView(ctx)).contains(getIssue(ctx, numberOf(innocent)).get("key").asText())
                : "the innocent issue left the backlog section of the planning view";

        // The same batch minus the frozen member is an ordinary success — the refusal is
        // about the completed sprint, not about batches.
        addIssuesToSprint(ctx, ctx.token(), open, idOf(innocent)).andExpect(status().isOk());
        assert open.toString().equals(sprintId(getIssue(ctx, numberOf(innocent))))
                : "the innocent issue could not be added on its own afterwards";
    }

    /**
     * The guard sits <strong>after</strong> the membership check: an issue that is not in
     * the sprint at all is still the idempotent 204 it always was, even when that sprint
     * is completed. Otherwise a retried DELETE — or the SPA cleaning up after a drag —
     * would start failing with a 422 about a sprint the issue was never in.
     */
    @Test
    void removingAnIssueThatIsNotInACompletedSprintIsStillANoOp() throws Exception {
        var ctx = newProject();
        var closed = startedSprint(ctx, "Sprint 1");
        completeToBacklog(ctx, ctx.token(), closed).andExpect(status().isOk());
        var loose = createIssue(ctx, "never committed");

        removeIssueFromSprint(ctx, ctx.token(), closed, idOf(loose))
                .andExpect(status().isNoContent());
        assert historyFields(ctx, numberOf(loose)).isEmpty() : "a no-op removal wrote history";
        assert sprintName(getIssue(ctx, numberOf(loose))) == null : "a no-op removal moved the issue";
    }

    /**
     * {@code sprintId} and {@code clearSprint} in one PATCH used to let {@code sprintId}
     * silently win — a client bug that looked like a successful move. It is now a 400 on
     * both write paths (the rank endpoint's half is in {@code IssueRankTest}).
     */
    @Test
    void sprintIdAndClearSprintTogetherIs400() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "work");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());

        patchIssue(ctx, ctx.token(), numberOf(issue),
                "{\"sprintId\":\"" + sprintId + "\",\"clearSprint\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not both")));

        // The contradictory request applied NEITHER interpretation.
        assert sprintId.toString().equals(sprintId(getIssue(ctx, numberOf(issue))))
                : "a contradictory sprint payload was half-applied";
        assert count(historyFields(ctx, numberOf(issue)), "sprint") == 1
                : "a rejected PATCH wrote history";
    }

    /** Re-assigning an issue to the sprint it is already in is a no-op: no history, no version bump. */
    @Test
    void assigningAnIssueToTheSprintItIsAlreadyInChangesNothing() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "work");

        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());
        int version = getIssue(ctx, numberOf(issue)).get("version").asInt();
        assert count(historyFields(ctx, numberOf(issue)), "sprint") == 1 : "the move was not audited";

        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());
        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isOk());

        assert getIssue(ctx, numberOf(issue)).get("version").asInt() == version
                : "a no-op assignment bumped @Version";
        assert count(historyFields(ctx, numberOf(issue)), "sprint") == 1
                : "a no-op assignment wrote a history row";
    }

    /** Removing an issue that is not in the sprint stays idempotent (204, no history). */
    @Test
    void removingAnIssueThatIsNotInTheSprintIsANoOp() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "never committed");

        removeIssueFromSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isNoContent());
        assert historyFields(ctx, numberOf(issue)).isEmpty() : "a no-op removal wrote history";
    }

    // ===================================================== helpers

    /** An Epic (hierarchy level 2) — the only thing a Task may hang under. */
    private JsonNode createEpic(Ctx ctx, String title) throws Exception {
        var body = mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.post(ctx.issuesBase())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":" + json.writeValueAsString(title)
                                + ",\"typeId\":\"" + ctx.typeId("Epic") + "\""
                                + ",\"statusId\":\"" + ctx.todoStatusId() + "\""
                                + ",\"storyPoints\":8}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private List<String> historyFields(Ctx ctx, long number) throws Exception {
        var out = new ArrayList<String>();
        for (var row : issueHistory(ctx, number).get("content")) out.add(row.get("field").asText());
        return out;
    }

    private static long count(List<String> fields, String field) {
        return fields.stream().filter(field::equals).count();
    }
}
