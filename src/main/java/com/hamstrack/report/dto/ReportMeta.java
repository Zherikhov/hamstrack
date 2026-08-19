package com.hamstrack.report.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The provenance block carried by <strong>every</strong> report response
 * (reports-proposal §4.3). Established by R1 and reused unchanged by every later
 * report; adding a report means adding one of these, not inventing a variant.
 *
 * <p>It exists for one reason, and the reason is documented rather than assumed: the
 * recurring support question about competitors' reports is <em>"these numbers don't
 * match what I expected"</em>, and the mechanism behind it is a report that quietly
 * left data out (§1.5, §1.7). A response that states when it was computed, how many
 * issues it saw, whether a cap bit, how far back the data goes and whether a filter
 * matched nothing at all cannot fail that way silently.
 *
 * @param computedAt       when the server computed these numbers. The reader's anchor —
 *                         reports are live reads, so two tabs open at different times
 *                         legitimately disagree, and this is how a reader tells which is
 *                         which (§6, "Two tabs").
 * @param basedOnIssues    how many <strong>distinct issues</strong> the numbers were
 *                         computed from. Not the sum of the series: an issue created and
 *                         closed inside the same window contributes to both lines and is
 *                         counted here once.
 * @param truncated        whether {@code cap} actually bit. When true the report is a
 *                         partial view and the UI must say so above the chart.
 * @param cap              the row cap that <em>would</em> bite ({@code app.reports.max-rows}).
 *                         Always reported, including by reports that cannot hit it, so a
 *                         client never has to guess which number it was measured against.
 * @param firstIssueAt     when the earliest issue the report's own filters admit was
 *                         created, or {@code null} if there is none (HD-28 R1 round 2,
 *                         item 7). This is what "we only have N days of history" must be
 *                         measured from: the project's {@code createdAt} is a different
 *                         date — often years off — and getting it required a second
 *                         request for a number that was never the right one. Filtered like
 *                         everything else in the response, so on a filtered chart it reads
 *                         "the first issue this chart could ever have shown".
 *                         <p><strong>Filters yes, window NO — on every report, without
 *                         exception</strong> (HD-138 R3 round 3). It is a property of the
 *                         PROJECT, not of the sample the report happens to be describing,
 *                         and this record is shared by every report while the SPA reads
 *                         the field through a single shared helper. Each report computed
 *                         it its own way once and the field silently meant three things:
 *                         project-wide on flow, the windowed completed sample on cycle
 *                         time, currently-open issues on aging — three populations, one
 *                         name, and every one of them a plausible date, so nothing looked
 *                         wrong. Applying the window is also circular: the earliest issue
 *                         inside a fortnight is at most a fortnight old, so a five-year-old
 *                         project reported a fortnight of history and the thin-data banner
 *                         fired on every project. Any later report on this base path
 *                         computes it the same way or does not populate it.
 * @param unmatchedFilters the names of the supplied filter parameters that match
 *                         <strong>no issue in this project at all</strong> — never null,
 *                         empty when every filter matched something or none was sent.
 *                         Without it a typo'd or stale filter id renders a complete,
 *                         plausible, all-zero chart, and "no bugs were created in Q1" and
 *                         "your filter matched nothing" are the same picture. Saying
 *                         "nothing in <em>your</em> project carries this id" discloses
 *                         nothing about any other tenant. Note the deliberately weak
 *                         claim: this is about the project's issues, not about whether the
 *                         id exists somewhere in the taxonomy — a valid type nobody has
 *                         ever used is reported here too, and "no issue here carries it"
 *                         is exactly the true and useful statement.
 */
public record ReportMeta(
        OffsetDateTime computedAt,
        long basedOnIssues,
        boolean truncated,
        int cap,
        OffsetDateTime firstIssueAt,
        List<String> unmatchedFilters
) {}
