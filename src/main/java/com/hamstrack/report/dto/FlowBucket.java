package com.hamstrack.report.dto;

import java.time.LocalDate;

/**
 * One point on the flow chart (reports-proposal §2.1).
 *
 * @param date      the bucket's <strong>start</strong> date, on a UTC boundary. For
 *                  {@code interval=WEEK} this is a Monday, which for the first and last
 *                  bucket of an unaligned window may lie outside {@code [from, to]} —
 *                  those buckets are partial by construction and count only what
 *                  happened inside the window ({@link ReportInterval#truncate}).
 * @param created   issues whose {@code created_at} falls in this bucket.
 * @param resolved  issues whose {@code closed_at} falls in this bucket. <strong>Read
 *                  this as "issues that are closed NOW, dated by their most recent
 *                  closure"</strong> — {@code closed_at} is cleared when an issue leaves
 *                  a DONE status, so reopening an issue removes it from the bucket it
 *                  used to sit in, and closing it again puts it in a new one. Past
 *                  buckets are therefore mutable. There is no resolution-event ledger in
 *                  v1 (§2.1, §9 OQ 5) and the UI is required to footnote this.
 * @param openAtEnd how many issues were open at the END of this bucket: the count open
 *                  when the window opened, plus every {@code created} minus every
 *                  {@code resolved} up to and including this bucket. Same
 *                  "as-of-now" caveat as {@code resolved}, one integral further on.
 * @param partial   whether this bucket covers <strong>less calendar than a full
 *                  interval</strong>, because the window starts or ends inside it. Its bar
 *                  is legitimately shorter than its neighbours' and a reader comparing
 *                  them is being misled unless the UI says so.
 *                  <p>Computed here rather than in the client (HD-28 R1 round 2, item 7):
 *                  deriving it browser-side means re-implementing Monday truncation in
 *                  TypeScript — one calendar rule in two languages, which is the same
 *                  arrangement {@link ReportInterval} exists to prevent between Java and
 *                  PostgreSQL. At {@code interval=DAY} nothing is ever partial.
 */
public record FlowBucket(
        LocalDate date,
        long created,
        long resolved,
        long openAtEnd,
        boolean partial
) {}
