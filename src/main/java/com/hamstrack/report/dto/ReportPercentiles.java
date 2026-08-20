package com.hamstrack.report.dto;

/**
 * A p50/p85 pair (reports-proposal §2.2, §1.7) — <strong>percentiles, never a rolling
 * average</strong>, and both may legitimately be {@code null}.
 *
 * <h2>Why percentiles at all</h2>
 * The research this epic is built on names Jira's control-chart rolling average as a specific
 * defect: it is issue-count-based ("20% of the total items displayed, centred on each item"),
 * so <em>"if throughput changes, your rolling average behaviour changes even if the process
 * doesn't"</em> (§1.7). A p50/p85 pair over a stated window has no such property, and it
 * carries its own sample size in the response beside it.
 *
 * <h2>Why they are nullable, and why that is the feature</h2>
 * Below {@code CycleTimeReportService.MIN_PERCENTILE_SAMPLES} completed issues, both fields
 * are {@code null} and the client prints <em>"Not enough completed work to compute percentiles
 * (need 5, have 3)"</em>. A p85 computed from three issues is arithmetically defined and
 * informationally worthless — it is one issue's duration wearing a statistic's name — and
 * printing noise is worse than printing nothing (§2.2, §6).
 *
 * <p>The pair is always <em>present</em> as an object even when suppressed, so a client reads
 * {@code percentiles.cycle.p50 == null} rather than having to test the parent for existence
 * first. Suppression is a fact about the data, not a change of response shape.
 *
 * @param p50 the median, in days, or {@code null} when suppressed for want of samples
 * @param p85 the 85th percentile, in days, or {@code null} on the same terms. 85 rather than
 *            90 or 95 because it is the number the flow-metrics literature the proposal cites
 *            uses for a "most work finishes within" statement, and because at realistic team
 *            sample sizes a p95 is two issues wide
 */
public record ReportPercentiles(Double p50, Double p85) {

    /** The suppressed pair — used wherever the sample is too small to say anything. */
    public static final ReportPercentiles NONE = new ReportPercentiles(null, null);
}
