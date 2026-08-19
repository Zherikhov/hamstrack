package com.hamstrack.report.dto;

import com.hamstrack.issue.dto.SprintRef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET …/reports/sprint-burnup} (reports-proposal §2.3, §4.3) — will this sprint land, and
 * what happened to the plan.
 *
 * <p>Two lines and a log: {@link #series} carries the scope and completed values per UTC day, and
 * {@link #scopeChanges} explains every step in the first of them. The client draws the faint ideal
 * guide itself, from {@code (startAt, 0)} to {@code (endAt, committedAtStart)} — <strong>to the
 * COMMITTED scope, not the current one</strong> (§2.3), which is what makes it a guide rather than
 * a verdict: a sprint that took on more work has a line above its guide, and that is information,
 * not failure.
 *
 * @param sprint            the subject. <strong>{@code null} when the project has no ACTIVE sprint
 *                          and the caller named none</strong> — an empty answer to a well-formed
 *                          question, not a missing resource, so it is a 200 with an empty series
 *                          rather than a 404 (a 404 here would be indistinguishable from "that
 *                          sprint id is not in this project", which is a different and genuinely
 *                          404 case). A named {@code sprintId} that does not resolve inside this
 *                          project is always a 404: it is the report's <em>subject</em>, not a
 *                          filter — see {@code ReportController}
 * @param startAt           when the sprint started, or {@code null} for one that never has (a
 *                          FUTURE sprint has no burn-up: commitment is an event, and it has not
 *                          happened yet)
 * @param endAt             the sprint's planned end. May be before the last series point — a
 *                          sprint that overran is drawn to today, and the guide simply ends
 *                          earlier than the lines do
 * @param measure           the measure actually applied, echoed so a client can never mislabel a
 *                          chart it did not explicitly parameterise
 * @param committedAtStart  the scope at {@code startAt}: the sum over the commitment batch the
 *                          sprint's start wrote. Zero for a sprint that never started, and the
 *                          anchor of the ideal guide
 * @param unestimatedCount  issues with no estimate that were in the sprint <strong>at the last
 *                          plotted point</strong> — the completion instant for a finished sprint,
 *                          now for a running one, and the end of {@link #seriesTruncatedAt} for a
 *                          clipped one. That is the same instant the last point of {@link #series}
 *                          is measured at, deliberately: this number is the footnote to the chart
 *                          that was returned, so on a clipped response it counts the scope of the
 *                          clipped chart rather than a scope no point in the response shows.
 *                          <p>They contribute 0 to a {@code POINTS} series and are counted here,
 *                          never silently treated as zero alone (§6, and the same rule
 *                          {@code SectionStats} already follows on the backlog). Always populated,
 *                          including under {@code COUNT}, where it is the honest footnote for the
 *                          toggle the reader is about to flip
 * @param series            one point per UTC day, start day first. Empty when the sprint never
 *                          started or when there is no sprint at all
 * @param scopeChanges      every membership change after the start, oldest first. Empty is a real
 *                          and common answer: it means the plan held.
 *                          <p><strong>Two regimes, and the ordinary one is the second.</strong>
 *                          When {@link #seriesTruncatedAt} is set the log is bounded to the same
 *                          day the chart stops on, so a clipped response cannot pair a chart
 *                          ending in one year with a log running to another. When it is
 *                          {@code null} — which is very nearly always — the log runs to the end of
 *                          the ledger and therefore <em>past</em> the last plotted point for a
 *                          COMPLETED sprint: a completion stamps {@code completed_at} and only
 *                          then writes the {@code REMOVED} rows for the work it moves out, so
 *                          those carry-out moves are dated a moment after the final point.
 *                          <p>Deliberate. Bounding the log at the last point would drop exactly
 *                          those rows, leaving a chart that ends "scope 23, completed 18" with
 *                          nothing in the response saying where the five went — and naming where
 *                          scope went is what this log is for
 * @param seriesTruncatedAt <strong>the last day the chart covers when the sprint is longer than
 *                          {@code app.reports.max-window-days}</strong>, and {@code null} — the
 *                          ordinary case — when the whole sprint is drawn. The FIRST days are the
 *                          ones kept, because they carry the commitment.
 *                          <p>Its own field rather than {@code meta.truncated}, which means one
 *                          specific thing: the {@code app.reports.max-rows} ledger cap bit, the
 *                          number printed beside it in {@code meta.cap}. Folding the day clip into
 *                          that flag made a twelve-issue sprint answer
 *                          {@code basedOnIssues: 12, truncated: true, cap: 20000} and put "20 000"
 *                          in a banner about a report that dropped no rows at all. Two limits, two
 *                          signals; this one names the day it stopped on so the UI can say it.
 *                          <p>{@code unestimatedCount} is measured at this day too, and the sprint
 *                          review — which returns lists rather than a day series and is not
 *                          clipped — legitimately covers more of the sprint than a clipped chart
 *                          does
 * @param meta              the shared provenance block. {@code basedOnIssues} is the distinct
 *                          issues that have ever been in this sprint — counted in the database and
 *                          independent of both bounds, so it is the true total even when
 *                          {@code truncated} is set or the series was clipped, exactly as the
 *                          field means on every other report; {@code firstIssueAt} is the
 *                          project's earliest issue, likewise
 */
public record SprintBurnupResponse(
        SprintRef sprint,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        ReportMeasure measure,
        BigDecimal committedAtStart,
        int unestimatedCount,
        List<BurnupPoint> series,
        List<ScopeChange> scopeChanges,
        LocalDate seriesTruncatedAt,
        ReportMeta meta
) {}
