package com.hamstrack.report.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/workspaces/{ws}/projects/{p}/reports/flow} — created vs resolved over a
 * window (reports-proposal §2.1), the first report of the epic and the one that fixes the
 * conventions the rest inherit.
 *
 * <p>The window is echoed back <strong>exactly as requested</strong>. It is never
 * narrowed: a window wider than {@code app.reports.max-window-days} is refused with a 400
 * naming the cap rather than silently clamped, so {@code from}/{@code to} here are always
 * the caller's own numbers and a client can compare them to what it asked for.
 *
 * <p><strong>What "resolved" means, and why it can change under you.</strong> The series
 * are built from {@code issues.created_at} and {@code issues.closed_at} and from nothing
 * else — there is no resolution-event history in v1. {@code closed_at} is <em>cleared</em>
 * when an issue leaves a DONE status, so the resolved line means "issues that are closed
 * <em>now</em>, dated by their most recent closure". A reopened issue disappears from the
 * bucket it used to occupy; reclosed, it appears in a new one. Past buckets are therefore
 * mutable, and the UI is required to say so ({@code "Resolved counts issues that are
 * closed now, dated by their latest closure. Reopened issues move."}). {@code openAtEnd}
 * inherits the same caveat. This is disclosed rather than fixed on purpose (§9 OQ 5).
 *
 * @param buckets one entry per bucket in the window, ascending, <strong>zero-filled</strong>
 *                — a bucket in which nothing happened is present with zeros rather than
 *                absent, so the chart has no invisible gaps and the CSV has no missing
 *                rows. First and last buckets may be partial when the window does not
 *                start/end on a bucket boundary ({@link ReportInterval#truncate}).
 * @param totals  the three window-wide numbers under the chart.
 * @param meta    the shared provenance block ({@link ReportMeta}).
 */
public record FlowReportResponse(
        LocalDate from,
        LocalDate to,
        ReportInterval interval,
        List<FlowBucket> buckets,
        FlowTotals totals,
        ReportMeta meta
) {}
