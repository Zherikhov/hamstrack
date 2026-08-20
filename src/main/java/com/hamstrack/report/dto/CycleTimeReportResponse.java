package com.hamstrack.report.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET …/reports/cycle-time} — the finished-work half of the cycle time page
 * (reports-proposal §2.2, §4.3).
 *
 * <h2>What the numbers are about, precisely</h2>
 * The window is a range on <strong>{@code closed_at}</strong>: every issue here finished inside
 * {@code [from, to]}, whenever it may have been created. {@link #items} are the individual
 * dots; {@link #percentiles} are the two reference lines; {@link #sampleSize} and
 * {@link #missingStartCount} are what makes both readable rather than merely present.
 *
 * <h2>Three counts, three different questions — read them together</h2>
 * <ul>
 *   <li>{@link #sampleSize} — completed issues in the window matching the filters. This is what
 *       the <em>lead</em> percentiles were computed from, and it is the number the suppression
 *       message quotes ("need 5, have 3").</li>
 *   <li>{@link #missingStartCount} — how many of those have no {@code started_at} and therefore
 *       contribute to lead time, to this count, and to <strong>nothing else</strong>. The
 *       <em>cycle</em> percentiles were computed from {@code sampleSize - missingStartCount}
 *       issues, which is the number the UI prints as <em>"cycle time available for 812 of 940
 *       completed issues"</em>.</li>
 *   <li>{@code items.length} — how many dots are actually shipped. Equal to
 *       {@code sampleSize} unless {@code meta.truncated}, in which case it is
 *       {@code meta.cap} and the UI says so above the chart.</li>
 * </ul>
 *
 * <p><strong>When the cap bites, the lines still describe the whole window.</strong> The
 * percentiles are computed by PostgreSQL over every matching issue, not over the page of rows
 * that survived the cap — an aggregate costs the same either way, and a p85 computed from "the
 * most recent 20 000" would be a different statistic wearing the same label. So on a truncated
 * report the dots are a recent sample and the lines are the population; {@code sampleSize}
 * versus {@code items.length} is what tells a reader that, and it is why both are here.
 *
 * @param from             first day of the window, inclusive (echoed because the server may
 *                         have chosen it — see {@link CycleTimeQuery#withDefaultWindow(int)}).
 * @param to               last day of the window, inclusive.
 * @param items            one entry per completed issue, most recently closed first. That order
 *                         is what makes the cap's truncation meaningful: what survives is "the
 *                         most recent N", which is the sentence §6 requires the UI to print.
 * @param percentiles      the reference lines, per measure, each independently suppressible.
 * @param sampleSize       completed issues in the window, after filters. Not
 *                         {@code items.length} — see above.
 * @param missingStartCount how many of those have no {@code started_at}. The honesty rule's
 *                         disclosure; never zero because a fallback filled the gap.
 * @param meta             the shared provenance block ({@link ReportMeta}).
 */
public record CycleTimeReportResponse(
        LocalDate from,
        LocalDate to,
        List<CycleTimeItem> items,
        CycleTimePercentiles percentiles,
        long sampleSize,
        long missingStartCount,
        ReportMeta meta
) {}
