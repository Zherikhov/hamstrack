package com.hamstrack.report.dto;

/**
 * What a sprint report counts: whole issues, or story points (reports-proposal §2.3).
 *
 * <p>The toggle exists on the burn-up and on velocity; the sprint review reports
 * <strong>both</strong> for every list and needs no parameter. Default {@link #COUNT}, because it
 * is the measure every project has: points are optional, team-relative and (per §2.5) never
 * comparable between teams, so the report a project sees before it has decided anything about
 * estimation must be the one that works without estimates.
 *
 * <p><strong>No status code depends on the delivery capabilities</strong> (Rule A). A project with
 * {@code estimation} off still answers {@code measure=POINTS} with a points series — the UI hides
 * the toggle, the API does not hide the data, and existing points still render (Rule B). Deciding
 * otherwise here would put a capability inside a status code, which is the one thing the
 * capability model forbids.
 */
public enum ReportMeasure {

    /** One issue is one unit. The default, and the only measure that needs no estimation. */
    COUNT,

    /**
     * {@code issues.story_points}. An unestimated issue contributes <strong>0</strong> to the
     * series and is counted in {@code unestimatedCount} beside it — "we didn't estimate it" is
     * not "it's free", and reporting the zero without the count is documented failure mode #4
     * (§1.2, §6).
     *
     * <p><strong>Which points, exactly, differs between the two R4 reports on purpose</strong> —
     * the burn-up reads the issue's CURRENT estimate, the sprint review reads the ledger's
     * SNAPSHOT. See {@code SprintBurnupService} and {@code SprintReviewService}; the asymmetry is
     * a decision (§2.3, 2026-08-19), not an inconsistency, and "fixing" either to match the other
     * breaks the question that one answers.
     */
    POINTS
}
