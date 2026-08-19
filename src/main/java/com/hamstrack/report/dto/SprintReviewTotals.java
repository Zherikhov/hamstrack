package com.hamstrack.report.dto;

import java.math.BigDecimal;

/**
 * The sprint review's one header line, as data (reports-proposal §2.4):
 * <em>"Sprint 12 · 14 Aug – 28 Aug · completed 18 of 25 issues (41 of 60 points) · 5 added after
 * start."</em>
 *
 * <p>Every number here is also derivable from the five lists. It is restated in one place anyway
 * so the sentence has a single source: composing it from five separate objects is how a header
 * ends up quoting one list and a subtitle another, and this is the line people screenshot into a
 * retro.
 *
 * <h2>The denominator is what the sprint held at its END, not what it committed to</h2>
 * {@code completed X of Y} reads {@link #completedCount} of {@link #atEndCount}, where "at end" is
 * completed plus carried over. Both are cuts of one population — everything that was in the sprint
 * when it stopped — so the numerator is a subset of its own denominator.
 *
 * <p>Against the <em>commitment</em> it is not, and that was this record's first shape: work added
 * after the start can be completed, so the numerator counted a population the denominator did not,
 * and a sprint that took on late work and finished it could report "completed 25 of 23". The
 * commitment is not lost by this — it is a labelled list of its own with its own count and points,
 * and {@link #addedAfterStartCount} is precisely the disclosure of how far the sprint drifted from
 * it. That clause is the one that turns "we missed the plan" into "the plan changed", the
 * disagreement §1.2 says a burndown cannot settle.
 *
 * @param committedCount        issues in the sprint when it started — the plan, shown beside the
 *                              outcome rather than used as its denominator
 * @param committedPoints       what they weighed on entry (the ledger's snapshot), or {@code null}
 *                              when none of them was estimated — the same rule
 *                              {@link SprintReviewList#points()} follows, and for the same reason
 * @param atEndCount            everything the sprint held when it stopped: completed plus carried
 *                              over. The denominator
 * @param atEndPoints           and what that weighed, or {@code null} when nothing in it was
 *                              estimated
 * @param completedCount        how much of {@link #atEndCount} was closed by then
 * @param completedPoints       and what that weighed, or {@code null} when none of it was
 *                              estimated
 * @param addedAfterStartCount  issues that joined after the commitment
 */
public record SprintReviewTotals(
        int committedCount,
        BigDecimal committedPoints,
        int atEndCount,
        BigDecimal atEndPoints,
        int completedCount,
        BigDecimal completedPoints,
        int addedAfterStartCount
) {}
