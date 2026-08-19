package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET …/reports/sprint-review} — HD-29 / R4, reports-proposal §2.4.
 *
 * <p>The five lists are the whole report, and the two that are easiest to get wrong are
 * <em>removed before end</em> and <em>carried over</em>: both are issues that are not in the sprint
 * afterwards, and only the completion's own moves belong to the second. This suite pins that
 * boundary with a fixture built the way production builds it — {@code completed_at} stamped by the
 * conditional UPDATE, the carry-over ledger rows written a moment later.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintReviewApiTest extends SprintReportTestBase {

    @Test
    void theFiveListsSeparateWhatHappenedToEachIssue() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "finished", "\"storyPoints\":3");
        var b = createIssue(ctx, "pulled out", "\"storyPoints\":5");
        var c = createIssue(ctx, "arrived late", "\"storyPoints\":8");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(a), idOf(b)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        closedAt(ctx, numberOf(a), idOf(a), "2025-06-03T12:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(c)).andExpect(status().isOk());
        movedAt(sprint, idOf(c), "ADDED", "2025-06-04T09:00:00Z");
        removeIssueFromSprint(ctx, ctx.token(), sprint, idOf(b)).andExpect(status().isNoContent());
        movedAt(sprint, idOf(b), "REMOVED", "2025-06-05T09:00:00Z");
        completedAt(ctx, sprint, "2025-06-06T10:00:00Z");

        var report = review(ctx, "?sprintId=" + sprint);

        assertThat(listKeys(report, "committed"))
                .containsExactlyInAnyOrder(a.get("key").asText(), b.get("key").asText());
        assertThat(listKeys(report, "addedAfterStart")).containsExactly(c.get("key").asText());
        assertThat(listKeys(report, "removedBeforeEnd")).containsExactly(b.get("key").asText());
        assertThat(listKeys(report, "completed")).containsExactly(a.get("key").asText());
        // Carried over, NOT "removed": the completion moved it out, and the record says which.
        assertThat(listKeys(report, "carriedOver")).containsExactly(c.get("key").asText());

        assertThat(report.get("completedAt").asText()).startsWith("2025-06-06");
        var totals = report.get("totals");
        assertThat(totals.get("committedCount").asInt()).isEqualTo(2);
        assertThat(totals.get("committedPoints").asInt()).isEqualTo(8);
        assertThat(totals.get("completedCount").asInt()).isEqualTo(1);
        assertThat(totals.get("completedPoints").asInt()).isEqualTo(3);
        assertThat(totals.get("addedAfterStartCount").asInt()).isEqualTo(1);
        // The headline's denominator is what the sprint HELD AT ITS END — completed plus carried
        // over, one population cut two ways — so "completed 1 of 2" compares like with like. Read
        // against the commitment it would be "1 of 2" here too, by coincidence, but the shapes
        // differ: b was committed and left, c arrived late and stayed.
        assertThat(totals.get("atEndCount").asInt()).isEqualTo(2);
        assertThat(totals.get("atEndPoints").asInt()).isEqualTo(11);
    }

    /**
     * The denominator cannot be smaller than the numerator, which is the whole reason it moved off
     * the commitment: work that arrives after the start can be completed, so a sprint that took
     * late work on and finished it used to report <em>"completed 3 of 1"</em>.
     */
    @Test
    void theHeadlineDenominatorIsWhatTheSprintHeldAtItsEndSoTheRatioCannotExceedOne()
            throws Exception {
        var ctx = newProject();
        var committed = createIssue(ctx, "the plan", "\"storyPoints\":2");
        var lateA = createIssue(ctx, "late but done", "\"storyPoints\":3");
        var lateB = createIssue(ctx, "later and done", "\"storyPoints\":5");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(committed)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(lateA), idOf(lateB))
                .andExpect(status().isOk());
        movedAt(sprint, idOf(lateA), "ADDED", "2025-06-03T09:00:00Z");
        movedAt(sprint, idOf(lateB), "ADDED", "2025-06-03T09:00:00Z");
        for (var issue : java.util.List.of(committed, lateA, lateB)) {
            closedAt(ctx, numberOf(issue), idOf(issue), "2025-06-04T12:00:00Z");
        }
        completedAt(ctx, sprint, "2025-06-05T10:00:00Z");

        var totals = review(ctx, "?sprintId=" + sprint).get("totals");

        assertThat(totals.get("completedCount").asInt()).isEqualTo(3);
        assertThat(totals.get("atEndCount").asInt())
                .as("three completed out of three held at the end — never three out of the one "
                    + "issue the sprint committed to")
                .isEqualTo(3);
        assertThat(totals.get("committedCount").asInt())
                .as("the commitment is still reported, beside the outcome rather than under it")
                .isEqualTo(1);
        assertThat(totals.get("addedAfterStartCount").asInt())
                .as("and this clause is the disclosure of the gap between them")
                .isEqualTo(2);
    }

    /**
     * A list nobody estimated reports <strong>no</strong> points, not zero of them. "We didn't
     * estimate this" and "this is worth nothing" are different statements that a bare {@code 0}
     * renders identically — the same distinction {@code unestimatedCount} draws one row further in,
     * and the same one the burn-up's {@code unestimatedCount} draws for its series.
     */
    @Test
    void aListWithNothingEstimatedReportsNoPointsRatherThanZero() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "never estimated");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        var report = review(ctx, "?sprintId=" + sprint);

        assertThat(report.get("committed").get("count").asInt()).isEqualTo(1);
        assertThat(report.get("committed").get("unestimatedCount").asInt()).isEqualTo(1);
        assertThat(report.get("committed").get("points").isNull())
                .as("a sum of nothing is not zero, and a client should not have to derive that "
                    + "from count > unestimatedCount in five places")
                .isTrue();
        assertThat(report.get("totals").get("committedPoints").isNull()).isTrue();
        assertThat(report.get("removedBeforeEnd").get("points").isNull())
                .as("an empty list has no points either")
                .isTrue();
    }

    /**
     * The row payload the retro reads, and the one field that is not the issue's current state:
     * points are what it weighed <strong>on entry</strong>, so a later re-estimate cannot rewrite
     * what the team committed to. The burn-up beside it does the opposite, on purpose.
     */
    @Test
    void pointsAreTheEntrySnapshotSoAReEstimateCannotRewriteTheCommitment() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "grew in the telling", "\"storyPoints\":3");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"storyPoints\":13}")
                .andExpect(status().isOk());

        var report = review(ctx, "?sprintId=" + sprint);
        assertThat(report.get("committed").get("points").asInt()).isEqualTo(3);
        assertThat(report.get("committed").get("issues").get(0).get("points").asInt()).isEqualTo(3);

        // …while the burn-up, asked about the same sprint at the same moment, says 13. Both are
        // right; they are answering different questions (§2.3).
        var burnup = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");
        assertThat(burnup.get("committedAtStart").asInt()).isEqualTo(13);
    }

    /**
     * The reason V18 chose a nulling foreign key over a cascade, asserted end to end: a completed
     * sprint's record survives the deletion of the issues it was about.
     *
     * <p>The row keeps its key and its entry points and is flagged {@code deleted}. It does NOT
     * keep its completion — {@code closed_at} lived on the issue, and a delete out of a COMPLETED
     * sprint writes no ledger row that could have carried it — so it moves to {@code carriedOver},
     * which is the one claim the record can still make honestly. Dropping the row instead would
     * silently shrink what the sprint committed to, which is the failure this design exists to
     * prevent.
     */
    @Test
    void aCompletedSprintSurvivesTheDeletionOfItsIssues() throws Exception {
        var ctx = newProject();
        var kept = createIssue(ctx, "kept", "\"storyPoints\":2");
        var doomed = createIssue(ctx, "doomed", "\"storyPoints\":5");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(kept), idOf(doomed))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        closedAt(ctx, numberOf(kept), idOf(kept), "2025-06-03T12:00:00Z");
        closedAt(ctx, numberOf(doomed), idOf(doomed), "2025-06-03T13:00:00Z");
        completedAt(ctx, sprint, "2025-06-06T10:00:00Z");
        var doomedKey = doomed.get("key").asText();

        deleteIssue(ctx, numberOf(doomed));

        var report = review(ctx, "?sprintId=" + sprint);
        assertThat(listKeys(report, "committed"))
                .as("the sprint still committed to two issues, and one of them no longer exists")
                .contains(doomedKey);
        assertThat(report.get("totals").get("committedPoints").asInt())
                .as("its entry snapshot is the only weight left, and it is still counted")
                .isEqualTo(7);

        var row = listIssue(report, "committed", doomedKey);
        assertThat(row.get("deleted").asBoolean()).isTrue();
        assertThat(row.get("issueId").isNull()).isTrue();
        assertThat(row.get("title").isNull()).isTrue();
        assertThat(row.get("points").asInt()).isEqualTo(5);
        assertThat(listKeys(report, "carriedOver")).containsExactly(doomedKey);
    }

    @Test
    void aRunningSprintReportsWhatIsStillOpenAsCarriedOver() throws Exception {
        var ctx = newProject();
        var done = createIssue(ctx, "done");
        var open = createIssue(ctx, "still going");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(done), idOf(open))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        markDone(ctx, numberOf(done));

        var report = review(ctx, "?sprintId=" + sprint);

        assertThat(report.get("completedAt").isNull()).isTrue();
        assertThat(listKeys(report, "completed")).containsExactly(done.get("key").asText());
        assertThat(listKeys(report, "carriedOver")).containsExactly(open.get("key").asText());
        assertThat(listKeys(report, "removedBeforeEnd")).isEmpty();
    }

    @Test
    void anIssueRemovedAndReAddedIsCommittedOnceAndNotRemoved() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "went away and came back");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");

        removeIssueFromSprint(ctx, ctx.token(), sprint, idOf(issue))
                .andExpect(status().isNoContent());
        movedAt(sprint, idOf(issue), "REMOVED", "2025-06-03T09:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        movedAt(sprint, idOf(issue), "ADDED", "2025-06-04T09:00:00Z");

        var report = review(ctx, "?sprintId=" + sprint);
        assertThat(listKeys(report, "committed")).containsExactly(issue.get("key").asText());
        assertThat(listKeys(report, "addedAfterStart")).isEmpty();
        assertThat(listKeys(report, "removedBeforeEnd")).isEmpty();
        assertThat(listKeys(report, "carriedOver")).containsExactly(issue.get("key").asText());

        // …and the scope line comes back to where it was: 1 → 0 → 1.
        var burnup = burnup(ctx, "?sprintId=" + sprint);
        assertThat(point(burnup, "2025-06-02").get("scope").asInt()).isEqualTo(1);
        assertThat(point(burnup, "2025-06-03").get("scope").asInt()).isZero();
        assertThat(point(burnup, "2025-06-04").get("scope").asInt()).isEqualTo(1);
    }

    @Test
    void aProjectWithNoSprintAnswersWithFiveEmptyLists() throws Exception {
        var ctx = newProject();

        var report = review(ctx, null);

        assertThat(report.get("sprint").isNull()).isTrue();
        for (var list : new String[]{"committed", "addedAfterStart", "removedBeforeEnd",
                "completed", "carriedOver"}) {
            assertThat(report.get(list).get("count").asInt()).isZero();
            assertThat(report.get(list).get("issues")).isEmpty();
        }
        assertThat(report.get("totals").get("committedCount").asInt()).isZero();
    }

    /**
     * The same cache posture as every other report: private, one minute, keyed on the credential.
     * Asserted here as well as on the burn-up because "the other endpoint has it" is how one of two
     * sibling endpoints ends up shipping a public cache header.
     */
    @Test
    void theResponseIsPrivatelyCacheableAndVariesByCredential() throws Exception {
        var ctx = newProject();

        getReview(ctx, ctx.token(), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, private"))
                .andExpect(header().string("Vary", "Authorization"));
    }

    @Test
    void anotherProjectsSprintIsNotFound() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var theirs = startedSprint(sibling, "theirs");

        getReview(ctx, ctx.token(), "?sprintId=" + theirs).andExpect(status().isNotFound());
        getReview(ctx, ctx.token(), "?sprintId=" + UUID.randomUUID())
                .andExpect(status().isNotFound());
    }
}
