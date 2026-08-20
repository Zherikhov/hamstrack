package com.hamstrack.report.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The request half of {@code GET …/reports/flow} — the window, the bucket width and the
 * three optional filters of reports-proposal §2.1 / §4.3.
 *
 * <p>Every field is nullable on the wire (they are query parameters, all optional). The two
 * defaults that depend on nothing are applied here, in the compact constructor; the one that
 * depends on configuration is applied by {@link #withDefaultWindow(int)}. Note the ordering
 * dependency: {@code to} defaults before {@code from} is derived from it.
 *
 * <p><strong>The window is inclusive of both endpoints and interpreted in UTC</strong>:
 * {@code from=2026-01-01&to=2026-01-01} is one whole UTC day, not an empty range. Dates
 * rather than instants, because a report window is a human range ("the last quarter"),
 * and because bucket boundaries are UTC days regardless (§6).
 *
 * <p>Bounds are NOT checked here — {@code from > to}, "wider than
 * {@code app.reports.max-window-days}" and "outside {@link #MIN_DATE}..{@link #MAX_DATE}"
 * are all 400s raised by the service, which is where the configured cap is known and where
 * the message that names it is written.
 *
 * @param from        first day of the window, inclusive. Null until
 *                    {@link #withDefaultWindow(int)} has run.
 * @param to          last day of the window, inclusive. Defaults to today in UTC.
 * @param interval    bucket width. Defaults to {@code WEEK} (§2.1).
 * @param typeId      optional issue-type filter. An id that does not exist, or belongs to
 *                    another tenant, matches nothing: a filter is a predicate applied
 *                    inside an already project-scoped statement, not an addressed
 *                    resource, so it can only ever subtract. See {@code ReportController}
 *                    for the subject-vs-filter rule this follows from.
 * @param componentId optional component filter, same semantics.
 * @param labelId     optional label filter, same semantics.
 */
public record FlowQuery(
        LocalDate from,
        LocalDate to,
        ReportInterval interval,
        UUID typeId,
        UUID componentId,
        UUID labelId
) {

    /**
     * The window constants and arithmetic moved to {@link ReportWindow} when R3 (cycle time)
     * became the second report to need them; these are aliases so this record still reads as
     * a self-contained description of the flow request. There is exactly one definition, and
     * it is over there — see that class for why a second copy is the bug being prevented.
     */
    public static final int DEFAULT_WINDOW_DAYS = ReportWindow.DEFAULT_WINDOW_DAYS;

    /** @see ReportWindow#MIN_DATE */
    public static final LocalDate MIN_DATE = ReportWindow.MIN_DATE;

    /** @see ReportWindow#MAX_DATE */
    public static final LocalDate MAX_DATE = ReportWindow.MAX_DATE;

    public FlowQuery {
        interval = interval == null ? ReportInterval.WEEK : interval;
        to = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    }

    /**
     * The one definition of "the default report window", applied only when the caller sent
     * no {@code from} at all.
     *
     * <p><strong>This is defaulting, not clamping — do not "fix" it back.</strong> The 90 is
     * capped by {@code maxWindowDays} because otherwise the endpoint's own default request
     * is refused by the endpoint's own cap: {@code REPORTS_MAX_WINDOW_DAYS=30} is inside the
     * documented range 1–3650, and before round 2 it turned the parameterless call the
     * reports page makes on load into a permanent 400 (HD-28 R1 round 2, item 2).
     *
     * <p>The never-silently-narrow rule is untouched by this, and the distinction is exact:
     * a clamp narrows a window the caller <em>asked for</em>, and that is still a 400 naming
     * the cap ({@code FlowReportService.validateWindow}). Here the caller asked for nothing,
     * so there is no window to narrow — the server is picking its own default and must pick
     * one it will actually serve.
     */
    public FlowQuery withDefaultWindow(int maxWindowDays) {
        if (from != null) {
            return this;
        }
        var start = to.minusDays(defaultWindowDays(maxWindowDays) - 1L);
        return new FlowQuery(start, to, interval, typeId, componentId, labelId);
    }

    /** The default window length actually used, in days, given the configured cap. */
    public static int defaultWindowDays(int maxWindowDays) {
        return ReportWindow.defaultWindowDays(maxWindowDays);
    }

    /**
     * Whether any of the three optional filters is set — the switch between the report's
     * filtered and unfiltered statements.
     *
     * <p>Not a micro-optimisation: with the filters present as {@code (CAST(:x AS uuid) IS
     * NULL OR …)} catch-alls, a server-prepared statement (pgjdbc goes server-prepared after
     * {@code prepareThreshold=5} executions per connection) <em>can</em> take a generic plan
     * in which those predicates are no longer const-folded away, and the unfiltered opening
     * balance loses its index-only scan — ~14 ms → ~110 ms on a 100k-issue project. Today's
     * planner does not make that choice; the split means it never gets to. See
     * {@code FlowReportRepository} for the measurements and the caveat.
     */
    public boolean hasFilters() {
        return typeId != null || componentId != null || labelId != null;
    }

    /** Window length in days, counting both endpoints. Negative when {@code from > to}. */
    public long windowDays() {
        return ReportWindow.windowDays(from, to);
    }

    /** Window start as an instant — {@code from} at 00:00 UTC, inclusive. */
    public OffsetDateTime startInclusive() {
        return ReportWindow.startInclusive(from);
    }

    /**
     * Window end as an instant — the start of the day AFTER {@code to}, exclusive.
     * Half-open so that no timestamp can be counted twice or missed, and so that both
     * range predicates stay index-friendly ({@code >=} … {@code <}).
     *
     * <p>{@code plusDays(1)} is the overflow {@link #MAX_DATE} exists to prevent; the
     * service validates the band before calling this.
     */
    public OffsetDateTime endExclusive() {
        return ReportWindow.endExclusive(to);
    }
}
