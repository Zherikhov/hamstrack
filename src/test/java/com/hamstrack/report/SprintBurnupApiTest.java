package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET …/reports/sprint-burnup} — HD-29 / R4, reports-proposal §2.3.
 *
 * <p>One fixture carries most of this suite, because the report's whole claim is that four separate
 * facts land on the right days: a sprint committed to two issues on 2 June, one of them closed on
 * the 3rd, a third issue joined on the 4th, one left on the 5th, and it was completed on the 6th.
 * Every assertion below reads a named day out of that story rather than an index, so a change to
 * the shape of the window cannot make a wrong test pass.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintBurnupApiTest extends SprintReportTestBase {

    @Test
    void theScopeLineStepsOnMembershipAndTheCompletedLineFollowsClosures() throws Exception {
        var ctx = newProject();
        var sprint = sprintStory(ctx);

        var report = burnup(ctx, "?sprintId=" + sprint);

        assertThat(report.get("sprint").get("state").asText()).isEqualTo("COMPLETED");
        assertThat(report.get("committedAtStart").asInt()).isEqualTo(2);
        assertThat(point(report, "2025-06-02").get("scope").asInt()).isEqualTo(2);
        assertThat(point(report, "2025-06-02").get("completed").asInt()).isZero();
        assertThat(point(report, "2025-06-03").get("completed").asInt()).isEqualTo(1);
        assertThat(point(report, "2025-06-04").get("scope").asInt()).isEqualTo(3);
        assertThat(point(report, "2025-06-05").get("scope").asInt()).isEqualTo(2);
        // The line ends where it ends: the completion day is the last point, nothing is drawn past
        // it — no projection, no flat tail out to the planned end — and the last point is measured
        // AT the completion, not at the end of that day. Measured at midnight, the completion's
        // own carry-over removals would be inside it: the scope would dive to meet the completed
        // line and every finished sprint would render as having delivered all of its final scope.
        assertThat(report.get("series")).hasSize(5);
        assertThat(point(report, "2025-06-06").get("scope").asInt()).isEqualTo(2);
        assertThat(point(report, "2025-06-06").get("completed").asInt()).isEqualTo(1);
    }

    @Test
    void everyStepIsExplainedByTheScopeChangeLogAndTheCommitmentIsNotOneOfThem() throws Exception {
        var ctx = newProject();
        var sprint = sprintStory(ctx);

        var report = burnup(ctx, "?sprintId=" + sprint);

        // Three changes: the late arrival, the removal, and the carry-over the completion made.
        // NOT the two commitment rows — those are `committedAtStart`, and listing them would bury
        // the real changes under "the sprint started" twice.
        assertThat(changeKeys(report)).hasSize(3);
        var added = report.get("scopeChanges").get(0);
        assertThat(added.get("event").asText()).isEqualTo("ADDED");
        assertThat(added.get("delta").asInt()).isEqualTo(1);
        assertThat(added.get("at").asText()).startsWith("2025-06-04");
        assertThat(added.get("actorId").asText()).isEqualTo(ctx.owner().getId().toString());
        assertThat(report.get("scopeChanges").get(1).get("event").asText()).isEqualTo("REMOVED");
        assertThat(report.get("scopeChanges").get(1).get("delta").asInt()).isEqualTo(-1);

        // The ledger's own snapshot travels with the row, independently of the measure. Under
        // COUNT the delta is ±1, so without this the log carries no point value at all — and a
        // project with estimation switched off is shown exactly that measure, while delivery rule
        // B says the value it already recorded stays visible.
        assertThat(added.get("storyPoints").asInt()).isEqualTo(8);
        assertThat(report.get("scopeChanges").get(1).get("storyPoints").asInt()).isEqualTo(5);
    }

    /**
     * The measure toggle, and the consequence the decision of 2026-08-19 accepted with its eyes
     * open: <strong>a re-estimate moves the whole scope line, including its past</strong>.
     *
     * <p>Both halves are asserted, because each without the other is a different report. The
     * re-estimate is not a scope <em>event</em> — the log does not grow and the COUNT line does not
     * move — and it does move the POINTS line retroactively, which is exactly what "points are the
     * issue's current estimate" means and what the UI footnotes.
     */
    @Test
    void pointsAreCurrentSoAReEstimateMovesTheWholeLineButIsNeverAStep() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "three", "\"storyPoints\":3");
        var b = createIssue(ctx, "five", "\"storyPoints\":5");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(a), idOf(b)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");

        var before = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");
        assertThat(before.get("committedAtStart").asInt()).isEqualTo(8);
        assertThat(point(before, "2025-06-02").get("scope").asInt()).isEqualTo(8);
        int changesBefore = before.get("scopeChanges").size();

        patchIssue(ctx, ctx.token(), numberOf(b), "{\"storyPoints\":13}")
                .andExpect(status().isOk());

        var after = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");
        assertThat(after.get("scopeChanges")).hasSize(changesBefore);
        assertThat(point(after, "2025-06-02").get("scope").asInt())
                .as("the scope line's PAST moves with a re-estimate — the documented trade")
                .isEqualTo(16);
        assertThat(after.get("committedAtStart").asInt()).isEqualTo(16);

        // …and the count line is untouched by any of it.
        var counted = burnup(ctx, "?sprintId=" + sprint);
        assertThat(counted.get("committedAtStart").asInt()).isEqualTo(2);
        assertThat(point(counted, "2025-06-02").get("scope").asInt()).isEqualTo(2);
    }

    @Test
    void anUnestimatedIssueWeighsNothingAndIsCountedRatherThanSilentlyZero() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "estimated", "\"storyPoints\":3");
        var b = createIssue(ctx, "not estimated");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(a), idOf(b)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        var report = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");

        assertThat(report.get("committedAtStart").asInt()).isEqualTo(3);
        assertThat(report.get("unestimatedCount").asInt()).isEqualTo(1);
    }

    /**
     * Rule 1 of the ledger's design, seen from the report: a deleted issue keeps BOTH of its steps
     * and is still named in the log. An inner join to {@code issues} would have dropped it, and the
     * chart would have looked entirely healthy.
     */
    @Test
    void aDeletedIssueKeepsItsStepsAndItsName() throws Exception {
        var ctx = newProject();
        var doomed = createIssue(ctx, "doomed", "\"storyPoints\":5");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(doomed)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        // Deliberately NOT backdated: the delete writes its REMOVED now, and a start dragged back
        // past app.reports.max-window-days would clip the chart — and the log with it — leaving
        // this test asserting the day bound rather than the deleted issue it is about.
        var key = doomed.get("key").asText();

        deleteIssue(ctx, numberOf(doomed));

        var report = burnup(ctx, "?sprintId=" + sprint);
        assertThat(report.get("committedAtStart").asInt())
                .as("the sprint still committed to one issue — deleting it does not rewrite that")
                .isEqualTo(1);
        assertThat(changeKeys(report)).containsExactly(key);
        assertThat(report.get("scopeChanges").get(0).get("issueId").isNull())
                .as("there is nothing left to link to, and the response says so")
                .isTrue();
        // …and it no longer names who moved it. issue_history cascades away on delete, so keeping
        // the actor here would leave this log as the one surviving place in the product that says
        // who touched that issue and exactly when — a wider survival than the ledger was designed
        // for. The step, its instant and its key are the record; the person is not.
        assertThat(report.get("scopeChanges").get(0).get("actorId").isNull())
                .as("a delete is an erasure signal, and attribution goes with it")
                .isTrue();
        assertThat(report.get("scopeChanges").get(0).get("storyPoints").asInt())
                .as("what it weighed on entry is the record, and it survives")
                .isEqualTo(5);
        assertThat(report.get("meta").get("basedOnIssues").asInt()).isEqualTo(1);

        // The snapshot is the only weight left, and it is used rather than a silent zero.
        var points = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");
        assertThat(points.get("committedAtStart").asInt()).isEqualTo(5);
    }

    @Test
    void omittingTheSprintIdPicksTheActiveOne() throws Exception {
        var ctx = newProject();
        var done = startedSprint(ctx, "old");
        completeToBacklog(ctx, ctx.token(), done).andExpect(status().isOk());
        var running = startedSprint(ctx, "current");

        var report = burnup(ctx, null);

        assertThat(report.get("sprint").get("id").asText()).isEqualTo(running.toString());
        assertThat(report.get("sprint").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(report.get("measure").asText()).isEqualTo("COUNT");
    }

    /**
     * No ACTIVE sprint and no {@code sprintId} is a 200 with {@code sprint: null} — an empty answer
     * to a well-formed question, and the only shape that lets a client tell "between sprints" apart
     * from the 404 a bad sprint id earns.
     */
    @Test
    void aProjectBetweenSprintsAnswersWithNoSprintRatherThanNotFound() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "some work");

        var report = burnup(ctx, null);

        assertThat(report.get("sprint").isNull()).isTrue();
        assertThat(report.get("series")).isEmpty();
        assertThat(report.get("scopeChanges")).isEmpty();
        assertThat(report.get("committedAtStart").asInt()).isZero();
        assertThat(report.get("meta").get("firstIssueAt").isNull())
                .as("firstIssueAt is a property of the PROJECT, so it survives having no sprint")
                .isFalse();
    }

    @Test
    void aSprintThatNeverStartedHasNoBurnUpAndNoLedger() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "planned");
        var sprint = createSprint(ctx, "next");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());

        var report = burnup(ctx, "?sprintId=" + sprint);

        assertThat(report.get("sprint").get("state").asText()).isEqualTo("FUTURE");
        assertThat(report.get("startAt").isNull()).isTrue();
        assertThat(report.get("series")).isEmpty();
        assertThat(report.get("meta").get("basedOnIssues").asInt())
                .as("planning is not scope change: a FUTURE sprint has an empty ledger")
                .isZero();
    }

    /**
     * Rule A: no status code, and no field, depends on a delivery capability. A KANBAN project with
     * estimation off answers exactly as a Scrum one does — the UI hides the report and the points
     * toggle, the API hides nothing.
     */
    @Test
    void capabilitiesGateTheUiAndNothingHere() throws Exception {
        var ctx = newProject();
        enableEveryCapability(ctx);
        var issue = createIssue(ctx, "work", "\"storyPoints\":8");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        disableEveryCapability(ctx);

        var report = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");
        assertThat(report.get("committedAtStart").asInt()).isEqualTo(8);
        assertThat(report.get("series")).isNotEmpty();
        getReview(ctx, ctx.token(), "?sprintId=" + sprint).andExpect(status().isOk());
    }

    @Test
    void anotherProjectsSprintIsNotFoundBecauseItIsTheSubjectAndNotAFilter() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var theirs = startedSprint(sibling, "theirs");

        getBurnup(ctx, ctx.token(), "?sprintId=" + theirs).andExpect(status().isNotFound());
        getBurnup(ctx, ctx.token(), "?sprintId=" + UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    /**
     * A measure the server does not know is a <strong>400</strong>, not a silent fall back to
     * {@code COUNT}. Correct by inspection today — Spring's converter refuses the enum while it is
     * binding the parameter, before the controller runs — and unpinned until now, so an
     * {@code @InitBinder} or a custom converter could quietly turn it into a 500, or worse into a
     * chart labelled with a measure nobody asked for.
     *
     * <p>A malformed {@code sprintId} is the same 400 for the same reason, and is deliberately NOT
     * the 404 an unknown-but-well-formed sprint id earns: it fails before this endpoint is reached
     * and names no resource, so there is no existence for it to confirm.
     */
    @Test
    void anUnknownMeasureIsRefusedRatherThanQuietlyDefaulted() throws Exception {
        var ctx = newProject();

        getBurnup(ctx, ctx.token(), "?measure=STORY_HOURS").andExpect(status().isBadRequest());
        getBurnup(ctx, ctx.token(), "?measure=count").andExpect(status().isBadRequest());
        getBurnup(ctx, ctx.token(), "?sprintId=not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void theResponseIsPrivatelyCacheableAndVariesByCredential() throws Exception {
        var ctx = newProject();

        getBurnup(ctx, ctx.token(), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, private"))
                .andExpect(header().string("Vary", "Authorization"));
    }

    // ============================================================ the fixture

    /**
     * The story every day-level assertion in this suite reads: committed to two issues on 2 June,
     * one closed on the 3rd, a third joined on the 4th, one left on the 5th, completed on the 6th.
     *
     * <p>Driven entirely through the real endpoints, then backdated — so every ledger row was
     * written by {@code SprintScopeLedger} through the door production uses.
     */
    private UUID sprintStory(Ctx ctx) throws Exception {
        var a = createIssue(ctx, "a", "\"storyPoints\":3");
        var b = createIssue(ctx, "b", "\"storyPoints\":5");
        var c = createIssue(ctx, "c", "\"storyPoints\":8");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(a), idOf(b)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        endsAt(sprint, "2025-06-16T00:00:00Z");

        closedAt(ctx, numberOf(a), idOf(a), "2025-06-03T12:00:00Z");

        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(c)).andExpect(status().isOk());
        movedAt(sprint, idOf(c), "ADDED", "2025-06-04T09:00:00Z");

        removeIssueFromSprint(ctx, ctx.token(), sprint, idOf(b)).andExpect(status().isNoContent());
        movedAt(sprint, idOf(b), "REMOVED", "2025-06-05T09:00:00Z");

        completedAt(ctx, sprint, "2025-06-06T10:00:00Z");
        return sprint;
    }
}
