package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sprint burn-up's <strong>day bound</strong> — HD-29 / R4 round 2, items B1–B3.
 *
 * <p>A sprint's day count is normally bounded by real time, but {@code start_at} can be backdated
 * arbitrarily when a sprint is started, so the series is capped at
 * {@code app.reports.max-window-days} and the FIRST days are the ones kept. Nothing exercised that
 * path before this class: the bound shipped, was reported through the wrong field, and clipped the
 * chart while leaving the scope-change log running to today — three numbers describing three
 * different windows in one response — and no test would have gone red for any of it.
 *
 * <p>The cap is three days here, which is what makes the whole thing assertable in a suite that
 * cannot wait a year. It is a property of this context, not of the report.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.reports.max-window-days=3"
})
@AutoConfigureMockMvc
class SprintBurnupWindowTest extends SprintReportTestBase {

    /**
     * The clip, and — the part that was wrong — <strong>everything in the response clipped with
     * it</strong>.
     *
     * <p>The sprint ran nine days; three are drawn. The response says which day it stops on, the
     * change log stops there too, and {@code unestimatedCount} is measured there: the unestimated
     * issue joins on day four, so a clipped chart that still counted it would be reporting on an
     * issue that appears nowhere in the data it returned.
     */
    @Test
    void anOverlongSprintIsClippedToItsFirstDaysAndSaysWhereItStopped() throws Exception {
        var ctx = newProject();
        var committed = createIssue(ctx, "committed", "\"storyPoints\":5");
        var late = createIssue(ctx, "joined on day four");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(committed)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(late)).andExpect(status().isOk());
        movedAt(sprint, idOf(late), "ADDED", "2025-06-05T09:00:00Z");
        completedAt(ctx, sprint, "2025-06-10T10:00:00Z");

        var report = burnup(ctx, "?sprintId=" + sprint + "&measure=POINTS");

        assertThat(report.get("series")).hasSize(3);
        assertThat(point(report, "2025-06-04").get("scope").asInt()).isEqualTo(5);
        assertThat(report.get("seriesTruncatedAt").asText())
                .as("the response names the day the chart stops on, so the UI can say it")
                .isEqualTo("2025-06-04");
        assertThat(changeKeys(report))
                .as("the log covers the same span as the chart — a 2019 chart beside a 2026 "
                    + "change log is the 'numbers don't match' failure meta exists to prevent")
                .isEmpty();
        assertThat(report.get("unestimatedCount").asInt())
                .as("measured at the clipped boundary, where the unestimated issue is not yet a "
                    + "member")
                .isZero();
    }

    /**
     * The clip is NOT {@code meta.truncated}, which means one specific thing: the
     * {@code app.reports.max-rows} ledger cap bit, the number printed beside it in
     * {@code meta.cap}. Folding the day bound into that flag made this two-issue sprint answer
     * {@code truncated: true, cap: 20000} — a banner quoting twenty thousand rows at a report that
     * dropped none.
     */
    @Test
    void theDayClipIsNotTheRowCapAndDoesNotBorrowItsFlag() throws Exception {
        var ctx = newProject();
        var sprint = clippedSprint(ctx);

        var report = burnup(ctx, "?sprintId=" + sprint);

        assertThat(report.get("seriesTruncatedAt").isNull()).isFalse();
        assertThat(report.get("meta").get("truncated").asBoolean())
                .as("no row cap bit: two issues came back out of a cap of %s",
                        report.get("meta").get("cap"))
                .isFalse();
        assertThat(report.get("meta").get("basedOnIssues").asInt()).isEqualTo(2);
    }

    /**
     * The sprint review is not clipped — it returns lists, not a day series — so on a clipped
     * sprint the two reports legitimately describe different spans. That is exactly what
     * {@code seriesTruncatedAt} announces, and it is asserted here so the asymmetry is a stated
     * contract rather than something a reader discovers by diffing two screens.
     */
    @Test
    void theReviewIsNotBoundedByTheDayCapAndStillSeesTheWholeSprint() throws Exception {
        var ctx = newProject();
        var sprint = clippedSprint(ctx);

        var review = review(ctx, "?sprintId=" + sprint);

        assertThat(review.get("totals").get("atEndCount")).isNotNull();
        assertThat(listKeys(review, "committed")).hasSize(1);
        assertThat(listKeys(review, "addedAfterStart"))
                .as("the late arrival is outside the clipped chart and inside the record")
                .hasSize(1);
    }

    /** A sprint short enough to fit reports no clip at all — the ordinary case, and a null field. */
    @Test
    void aSprintThatFitsReportsNoClip() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "work");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        completedAt(ctx, sprint, "2025-06-03T10:00:00Z");

        var report = burnup(ctx, "?sprintId=" + sprint);

        assertThat(report.get("series")).hasSize(2);
        assertThat(report.get("seriesTruncatedAt").isNull()).isTrue();
        assertThat(report.get("meta").get("truncated").asBoolean()).isFalse();
    }

    /** Two issues, nine days, three drawn: one committed on 2 June and one that joins on the 5th. */
    private java.util.UUID clippedSprint(Ctx ctx) throws Exception {
        var committed = createIssue(ctx, "committed", "\"storyPoints\":5");
        var late = createIssue(ctx, "joined on day four");
        var sprint = createSprint(ctx, "S");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(committed)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, "2025-06-02T00:00:00Z");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(late)).andExpect(status().isOk());
        movedAt(sprint, idOf(late), "ADDED", "2025-06-05T09:00:00Z");
        completedAt(ctx, sprint, "2025-06-10T10:00:00Z");
        return sprint;
    }
}
