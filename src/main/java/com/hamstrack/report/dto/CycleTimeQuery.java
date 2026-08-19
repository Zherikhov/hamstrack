package com.hamstrack.report.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The request half of {@code GET …/reports/cycle-time} — the window and the three optional
 * filters of reports-proposal §2.2 / §4.3. The same shape as {@link FlowQuery} without the
 * bucket interval, because a scatter of individual issues has no buckets.
 *
 * <p>Every field is nullable on the wire (they are query parameters, all optional). The one
 * default that depends on nothing is applied in the compact constructor; the one that depends
 * on configuration is applied by {@link #withDefaultWindow(int)}. All window arithmetic and
 * both refusals live in {@link ReportWindow} — there is no second copy here, deliberately.
 *
 * <p><strong>The window is inclusive of both endpoints, interpreted in UTC, and is a window on
 * {@code closed_at}</strong>: this report is about work that <em>finished</em> in the window,
 * so an issue created two years ago and closed yesterday is in yesterday's report. That is the
 * only reading under which the x-axis (completion date) is the window, and it is what makes
 * the p50/p85 a statement about the period the reader selected.
 *
 * @param from        first day of the window, inclusive. Null until
 *                    {@link #withDefaultWindow(int)} has run.
 * @param to          last day of the window, inclusive. Defaults to today in UTC.
 * @param typeId      optional issue-type filter. An id that does not exist, or belongs to
 *                    another tenant, matches nothing: a filter is a predicate applied inside
 *                    an already project-scoped statement, not an addressed resource, so it can
 *                    only ever subtract. See {@code ReportController} for the
 *                    subject-vs-filter rule this follows from — and {@code meta.unmatchedFilters}
 *                    for the disclosure that keeps an empty chart unambiguous.
 * @param componentId optional component filter, same semantics.
 * @param labelId     optional label filter, same semantics.
 */
public record CycleTimeQuery(
        LocalDate from,
        LocalDate to,
        UUID typeId,
        UUID componentId,
        UUID labelId
) {

    public CycleTimeQuery {
        to = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    }

    /**
     * Applies the server's own default window when the caller sent no {@code from} at all —
     * defaulting, not clamping. See {@link ReportWindow#defaultWindowDays(int)} for why the
     * default is itself capped, and why that is not the rule it looks like it contradicts.
     */
    public CycleTimeQuery withDefaultWindow(int maxWindowDays) {
        if (from != null) {
            return this;
        }
        var start = to.minusDays(ReportWindow.defaultWindowDays(maxWindowDays) - 1L);
        return new CycleTimeQuery(start, to, typeId, componentId, labelId);
    }

    /**
     * Whether any of the three optional filters is set — the switch between the report's
     * filtered and unfiltered statements.
     *
     * <p>Not a micro-optimisation, and the reasoning is R1's verbatim: with the filters present
     * as {@code (CAST(:x AS uuid) IS NULL OR …)} catch-alls, a server-prepared statement
     * (pgjdbc goes server-prepared after {@code prepareThreshold=5} executions per connection)
     * <em>can</em> take a generic plan in which those predicates are no longer const-folded
     * away, and the unfiltered scans lose their index range. The split means the planner never
     * gets the choice. See {@code CycleTimeReportRepository}.
     */
    public boolean hasFilters() {
        return typeId != null || componentId != null || labelId != null;
    }

    /** Window start as an instant — {@code from} at 00:00 UTC, inclusive. */
    public OffsetDateTime startInclusive() {
        return ReportWindow.startInclusive(from);
    }

    /** Window end as an instant — the start of the day AFTER {@code to}, exclusive. */
    public OffsetDateTime endExclusive() {
        return ReportWindow.endExclusive(to);
    }
}
