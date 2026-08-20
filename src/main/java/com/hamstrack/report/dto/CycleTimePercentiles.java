package com.hamstrack.report.dto;

/**
 * The two reference lines of the finished-work half, one pair per measure
 * (reports-proposal §2.2). Both are always present as objects; either may hold
 * {@code null}s when its own sample is too small — see {@link ReportPercentiles}.
 *
 * <p><strong>The two pairs are suppressed independently, and that is not an oversight.</strong>
 * They are computed over different sample sets: {@link #lead} over every completed issue in the
 * window, {@link #cycle} over only those that also have a {@code started_at}. On an install that
 * upgraded recently the second set can be a small fraction of the first, so a window can
 * legitimately produce a usable lead pair and a suppressed cycle pair. Suppressing both when
 * either is thin would hide a number we have; suppressing neither would print a p85 computed
 * from two issues. The client can derive the cycle sample exactly —
 * {@code sampleSize - missingStartCount} — and both are on the response for that reason.
 *
 * @param cycle p50/p85 of {@code closed_at - started_at}, over completed issues that have a
 *              start. Never over a substituted {@code created_at} — see {@link CycleTimeItem}.
 * @param lead  p50/p85 of {@code closed_at - created_at}, over every completed issue in the
 *              window.
 */
public record CycleTimePercentiles(ReportPercentiles cycle, ReportPercentiles lead) {

    /** Both pairs suppressed — an empty or too-small window. */
    public static final CycleTimePercentiles NONE =
            new CycleTimePercentiles(ReportPercentiles.NONE, ReportPercentiles.NONE);
}
