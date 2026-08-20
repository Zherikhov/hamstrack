package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET …/reports/velocity} — HD-139 / R5, reports-proposal §2.5.
 *
 * <p>The bars are the sprint review's headline figures for sprints nobody has to open, so the
 * assertions that matter most are the ones proving the two reports agree: a velocity bar and the
 * review of the same sprint are computed from one ledger through one definition of membership, and
 * the day they disagree both are discredited.
 *
 * <p>The rest of the suite is the refusals of §1.4 as behaviour — the band suppressed below three
 * sprints, the cap refused rather than clamped. {@code VelocityRefusalTest} pins the two that are
 * structural (no person, no aggregate above the project) because those cannot be observed from one
 * response.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VelocityApiTest extends SprintReportTestBase {

    @Test
    void everyBarIsTheSprintReviewsHeadlineForThatSprint() throws Exception {
        var ctx = newProject();
        // 4 issues, 3 finished; the 4th is moved out by the completion, i.e. carried over.
        var sprint = completedSprint(ctx, "S1", "2025-06-02T00:00:00Z", "2025-06-13T10:00:00Z",
                4, 3, null);

        var bar = bar(velocity(ctx, null), "S1");
        var totals = review(ctx, "?sprintId=" + sprint).get("totals");

        assertThat(bar.get("committed").asInt()).isEqualTo(4);
        assertThat(bar.get("completed").asInt()).isEqualTo(3);
        assertThat(bar.get("carriedOver").asInt()).isEqualTo(1);
        assertThat(bar.get("addedAfterStart").asInt()).isZero();
        assertThat(bar.get("sprintId").asText()).isEqualTo(sprint.toString());
        assertThat(bar.get("completedAt").asText()).startsWith("2025-06-13");

        assertThat(bar.get("committed").asInt())
                .as("the bar and the review are two views of one ledger; if they can disagree "
                    + "about what a finished sprint committed to, neither is worth reading")
                .isEqualTo(totals.get("committedCount").asInt());
        assertThat(bar.get("completed").asInt())
                .isEqualTo(totals.get("completedCount").asInt());
        assertThat(bar.get("completed").asInt() + bar.get("carriedOver").asInt())
                .as("completed + carriedOver is what the sprint held at its end — the same "
                    + "denominator the review's headline uses")
                .isEqualTo(totals.get("atEndCount").asInt());
    }

    /**
     * Work that arrives mid-sprint is counted, labelled and kept out of the commitment — the clause
     * that turns "we missed the plan" into "the plan changed" (§1.2).
     */
    @Test
    void workAddedAfterTheStartIsItsOwnNumberAndNotPartOfTheCommitment() throws Exception {
        var ctx = newProject();
        var planned = createIssue(ctx, "planned");
        var late = createIssue(ctx, "late");
        var sprint = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(planned)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(late)).andExpect(status().isOk());
        movedAt(sprint, idOf(late), "ADDED", "2025-06-04T09:00:00Z");
        closedAt(ctx, numberOf(planned), idOf(planned), "2025-06-05T12:00:00Z");
        closedAt(ctx, numberOf(late), idOf(late), "2025-06-06T12:00:00Z");
        completedAt(ctx, sprint, "2025-06-13T10:00:00Z");

        var bar = bar(velocity(ctx, null), "S1");

        assertThat(bar.get("committed").asInt()).isEqualTo(1);
        assertThat(bar.get("addedAfterStart").asInt()).isEqualTo(1);
        assertThat(bar.get("completed").asInt())
                .as("a sprint that took late work on and finished it delivered two — the bar is "
                    + "the outcome, and the commitment is context beside it, never its ceiling")
                .isEqualTo(2);
        assertThat(bar.get("carriedOver").asInt()).isZero();
    }

    /**
     * An issue somebody pulled OFF a running sprint is in neither outcome column. It is not
     * carry-over (nothing carried it), and counting it as delivered would be an outright lie —
     * this is the ordering inside {@code LedgerIssue.outcomeAt}, observed from outside.
     */
    @Test
    void workRemovedWhileTheSprintRanIsNeitherCompletedNorCarriedOver() throws Exception {
        var ctx = newProject();
        var kept = createIssue(ctx, "kept");
        var pulled = createIssue(ctx, "pulled");
        var sprint = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(kept), idOf(pulled))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        // Finished AND then taken off the sprint: the record must say it left, not that it landed.
        closedAt(ctx, numberOf(pulled), idOf(pulled), "2025-06-03T12:00:00Z");
        removeIssueFromSprint(ctx, ctx.token(), sprint, idOf(pulled))
                .andExpect(status().isNoContent());
        movedAt(sprint, idOf(pulled), "REMOVED", "2025-06-04T09:00:00Z");
        closedAt(ctx, numberOf(kept), idOf(kept), "2025-06-05T12:00:00Z");
        completedAt(ctx, sprint, "2025-06-13T10:00:00Z");

        var bar = bar(velocity(ctx, null), "S1");

        assertThat(bar.get("committed").asInt()).isEqualTo(2);
        assertThat(bar.get("completed").asInt())
                .as("closed and then removed is removed — asking closure before membership would "
                    + "quietly inflate every bar in the chart")
                .isEqualTo(1);
        assertThat(bar.get("carriedOver").asInt()).isZero();
    }

    @Test
    void theBandIsThePercentilesOfWhatWasDeliveredWithTheSampleSizeStated() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-01-06T00:00:00Z", "2025-01-17T10:00:00Z", 4, 2, null);
        completedSprint(ctx, "S2", "2025-01-20T00:00:00Z", "2025-01-31T10:00:00Z", 4, 4, null);
        completedSprint(ctx, "S3", "2025-02-03T00:00:00Z", "2025-02-14T10:00:00Z", 4, 3, null);

        var forecast = velocity(ctx, null).get("forecast");

        // Sample {2,3,4}: percentile_cont(0.5) = 3, percentile_cont(0.85) = 3.7.
        assertThat(forecast.get("p50").asDouble()).isEqualTo(3.0);
        assertThat(forecast.get("p85").asDouble()).isEqualTo(3.7);
        assertThat(forecast.get("sampleSize").asInt()).isEqualTo(3);
    }

    /**
     * §2.5, §6: fewer than three completed sprints and the band is suppressed <em>with the sample
     * size stated</em>. The bars still render — they are facts — and the client prints "need 3,
     * have 2" rather than a p85 that is one sprint's number wearing a statistic's name.
     */
    @Test
    void belowThreeCompletedSprintsTheBandIsSuppressedButTheSampleSizeIsStated() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-01-06T00:00:00Z", "2025-01-17T10:00:00Z", 3, 3, null);
        completedSprint(ctx, "S2", "2025-01-20T00:00:00Z", "2025-01-31T10:00:00Z", 3, 1, null);

        var report = velocity(ctx, null);

        assertThat(barNames(report)).containsExactly("S1", "S2");
        assertThat(report.get("forecast").get("p50").isNull()).isTrue();
        assertThat(report.get("forecast").get("p85").isNull()).isTrue();
        assertThat(report.get("forecast").get("sampleSize").asInt())
                .as("suppression states what it had; a band whose sample size the reader must go "
                    + "and count is the scoreboard this report exists not to be")
                .isEqualTo(2);
    }

    /** A project that has never completed a sprint: a 200 with an empty chart, never a 404. */
    @Test
    void aProjectWithNoCompletedSprintsIsAnEmptyChartNotAnError() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "work");
        startedSprint(ctx, "running");           // ACTIVE: it has not delivered anything yet

        var report = velocity(ctx, null);

        assertThat(report.get("sprints")).isEmpty();
        assertThat(report.get("forecast").get("sampleSize").asInt()).isZero();
        assertThat(report.get("forecast").get("p50").isNull()).isTrue();
        assertThat(report.get("meta").get("basedOnIssues").asInt()).isZero();
        assertThat(report.get("meta").get("firstIssueAt").isNull())
                .as("firstIssueAt is a property of the PROJECT, not of the sample — a project with "
                    + "issues and no completed sprints still has a first issue")
                .isFalse();
    }

    /** A running sprint has not delivered yet; counting it would pull every band down. */
    @Test
    void theActiveSprintIsNotSampled() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "done", "2025-01-06T00:00:00Z", "2025-01-17T10:00:00Z", 2, 2, null);
        startedSprint(ctx, "running");

        assertThat(barNames(velocity(ctx, null))).containsExactly("done");
    }

    @Test
    void barsComeBackOldestFirstAndTheCapKeepsTheMostRecent() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-01-06T00:00:00Z", "2025-01-17T10:00:00Z", 1, 1, null);
        completedSprint(ctx, "S2", "2025-01-20T00:00:00Z", "2025-01-31T10:00:00Z", 1, 1, null);
        completedSprint(ctx, "S3", "2025-02-03T00:00:00Z", "2025-02-14T10:00:00Z", 1, 1, null);

        assertThat(barNames(velocity(ctx, null)))
                .as("a bar chart reads left to right in time, so the server sorts once and the "
                    + "client never has to")
                .containsExactly("S1", "S2", "S3");
        assertThat(barNames(velocity(ctx, "?sprints=2")))
                .as("the cap keeps the most RECENT sprints — a forecast from the oldest two is a "
                    + "forecast for a team that may no longer exist")
                .containsExactly("S2", "S3");
    }

    /**
     * §4.3, §6: over the cap is a 400 <strong>naming the cap</strong>. Never a clamp — a sample
     * silently different from the one asked for is how a report earns "these numbers don't match
     * what I expected".
     */
    @Test
    void aSampleOutsideTheCapIsRefusedWithTheCapNamedRatherThanClamped() throws Exception {
        var ctx = newProject();

        getVelocity(ctx, ctx.token(), "?sprints=13")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("12")));
        getVelocity(ctx, ctx.token(), "?sprints=0").andExpect(status().isBadRequest());
        getVelocity(ctx, ctx.token(), "?sprints=-1").andExpect(status().isBadRequest());
        // The boundary itself is fine, and so is the default.
        getVelocity(ctx, ctx.token(), "?sprints=12").andExpect(status().isOk());
        getVelocity(ctx, ctx.token(), null).andExpect(status().isOk());
    }

    /**
     * Points are the ledger's ENTRY SNAPSHOT, not today's estimate — the sprint review's rule, and
     * the opposite of the burn-up's. A completed sprint is frozen, so re-pointing an old issue must
     * not move a bar drawn four months ago or the band computed from it.
     */
    @Test
    void pointsAreTheEntrySnapshotSoARepointDoesNotRewriteAFinishedSprint() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "three points", "\"storyPoints\":3");
        var sprint = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        closedAt(ctx, numberOf(issue), idOf(issue), "2025-06-04T12:00:00Z");
        completedAt(ctx, sprint, "2025-06-13T10:00:00Z");

        var before = bar(velocity(ctx, "?measure=POINTS"), "S1");
        assertThat(before.get("committed").asInt()).isEqualTo(3);
        assertThat(before.get("completed").asInt()).isEqualTo(3);

        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"storyPoints\":13}").andExpect(status().isOk());

        var after = bar(velocity(ctx, "?measure=POINTS"), "S1");
        assertThat(after.get("completed").asInt())
                .as("a re-estimate today may not change what a finished sprint delivered, or "
                    + "tomorrow's plan moves because somebody tidied yesterday's backlog")
                .isEqualTo(3);
        assertThat(bar(velocity(ctx, null), "S1").get("completed").asInt())
                .as("COUNT is unaffected either way")
                .isEqualTo(1);
    }

    /**
     * §6, documented failure mode #4: an unestimated issue contributes 0 to a points bar
     * <strong>and</strong> a tick on {@code unestimatedCount}. Never the zero on its own — that is
     * the burndown line that cannot move and nobody can say why.
     */
    @Test
    void unestimatedIssuesContributeZeroAndAreCountedRatherThanSilentlyZero() throws Exception {
        var ctx = newProject();
        var estimated = createIssue(ctx, "estimated", "\"storyPoints\":5");
        var bare = createIssue(ctx, "unestimated");
        var sprint = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(estimated), idOf(bare))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        closedAt(ctx, numberOf(estimated), idOf(estimated), "2025-06-04T12:00:00Z");
        closedAt(ctx, numberOf(bare), idOf(bare), "2025-06-04T12:00:00Z");
        completedAt(ctx, sprint, "2025-06-13T10:00:00Z");

        var points = bar(velocity(ctx, "?measure=POINTS"), "S1");
        assertThat(points.get("completed").asInt()).isEqualTo(5);
        assertThat(points.get("unestimatedCount").asInt()).isEqualTo(1);

        assertThat(bar(velocity(ctx, null), "S1").get("unestimatedCount").asInt())
                .as("stated under COUNT too: a reader switching measures needs the same disclosure "
                    + "either way, not one that appears only once the number is already wrong")
                .isEqualTo(1);
    }

    /**
     * Rule A: no status code and no number depends on a delivery capability. A KANBAN project that
     * has completed sprints still gets its chart, and {@code measure=POINTS} answers with points in
     * a project whose {@code estimation} is off. The UI hides the controls; the API hides nothing.
     */
    @Test
    void capabilitiesGateTheUiAndNeverThisEndpoint() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-06-02T00:00:00Z", "2025-06-13T10:00:00Z", 2, 2, 5);
        disableEveryCapability(ctx);

        var report = velocity(ctx, "?measure=POINTS");

        assertThat(barNames(report)).containsExactly("S1");
        assertThat(bar(report, "S1").get("completed").asInt())
                .as("existing points still render with estimation off (Rule B)")
                .isEqualTo(10);
    }

    @Test
    void theResponseCarriesTheFamilysMetaBlockAndCacheHeaders() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-06-02T00:00:00Z", "2025-06-13T10:00:00Z", 3, 2, null);

        getVelocity(ctx, ctx.token(), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, private"))
                .andExpect(header().string("Vary", "Authorization"));

        var meta = velocity(ctx, null).get("meta");
        assertThat(meta.get("basedOnIssues").asInt())
                .as("distinct issues the sampled ledgers hold, counted in the database")
                .isEqualTo(3);
        assertThat(meta.get("truncated").asBoolean()).isFalse();
        assertThat(meta.get("cap").asInt()).isEqualTo(20000);
        assertThat(meta.get("computedAt").isNull()).isFalse();
        assertThat(meta.get("unmatchedFilters")).isEmpty();
    }

    /**
     * An issue that appears in two of the sampled sprints is one issue, counted once — the same
     * "distinct issues this report saw" every other {@code meta.basedOnIssues} in the family means.
     */
    @Test
    void anIssueCarriedIntoASecondSprintIsCountedOnceInBasedOnIssues() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "long runner");
        var first = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), first, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), first).andExpect(status().isOk());
        startedAt(first, "2025-01-06T00:00:00Z");
        completedAt(ctx, first, "2025-01-17T10:00:00Z");

        var second = createSprint(ctx, "S2");
        addIssuesToSprint(ctx, ctx.token(), second, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), second).andExpect(status().isOk());
        startedAt(second, "2025-01-20T00:00:00Z");
        completedAt(ctx, second, "2025-01-31T10:00:00Z");

        var report = velocity(ctx, null);

        assertThat(barNames(report)).containsExactly("S1", "S2");
        assertThat(report.get("meta").get("basedOnIssues").asInt()).isEqualTo(1);
    }

    /**
     * §5.2, consequence 1: a completed sprint whose issues were later deleted keeps its figures.
     * The ledger's issue reference is nullable and the join is LEFT precisely so that a delete
     * cannot rewrite a finished sprint — and here that matters twice over, because a wrong bar also
     * moves the band drawn beside it.
     *
     * <p>The departed issue lands in <strong>carried over</strong>, never in completed: its
     * {@code closed_at} died with it, and a report may not assert what it cannot show.
     */
    @Test
    void aDeletedIssueKeepsItsPlaceInAFinishedSprintsBar() throws Exception {
        var ctx = newProject();
        var kept = createIssue(ctx, "kept", "\"storyPoints\":3");
        var doomed = createIssue(ctx, "doomed", "\"storyPoints\":5");
        var sprint = createSprint(ctx, "S1");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(kept), idOf(doomed))
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        closedAt(ctx, numberOf(kept), idOf(kept), "2025-06-04T12:00:00Z");
        completedAt(ctx, sprint, "2025-06-13T10:00:00Z");

        deleteIssue(ctx, numberOf(doomed));

        var bar = bar(velocity(ctx, "?measure=POINTS"), "S1");
        assertThat(bar.get("committed").asInt())
                .as("the commitment is a fact about the past; deleting an issue afterwards may "
                    + "not shrink it, and the ledger's snapshot is the only weight left")
                .isEqualTo(8);
        assertThat(bar.get("completed").asInt()).isEqualTo(3);
        assertThat(bar.get("carriedOver").asInt())
                .as("a departed issue cannot be proven completed, so it lands here rather than "
                    + "being dropped or claimed")
                .isEqualTo(5);
    }
}
