package com.hamstrack.report.dto;

import com.hamstrack.issue.dto.SprintRef;

import java.time.OffsetDateTime;

/**
 * {@code GET …/reports/sprint-review} (reports-proposal §2.4, §4.3) — what did we commit to, what
 * arrived late, what did we finish, what carried over.
 *
 * <p><strong>Not a chart.</strong> Five labelled lists of issue rows, each with a count and a point
 * sum, plus the one header line's worth of {@link #totals}. §1.1 says this is the artefact teams
 * actually open, and §2.4 says it is the cheapest thing in the document to build; both turned out
 * to be true — it shares its single ledger query with the burn-up.
 *
 * <p><strong>The sprint's end is when it was COMPLETED, not when it was planned to end.</strong>
 * Every "before the end" boundary in this record is {@code completedAt} for a finished sprint and
 * "now" for a running one; {@link #endAt} is reported beside it but decides nothing. A sprint
 * completed three days late did not remove anything "after the end" for those three days, and a
 * sprint completed early did not carry over the work it had already stopped doing.
 *
 * <p>For a COMPLETED sprint this is a permanent, exact record: the ledger is append-only and
 * id-keyed, so it survives every rename, and it survives the deletion of the issues themselves —
 * those arrive as rows with {@code deleted: true} rather than vanishing (see
 * {@link SprintReviewIssue}).
 *
 * @param sprint          the subject, or {@code null} when the project has no ACTIVE sprint and the
 *                        caller named none (200 with empty lists — see
 *                        {@link SprintBurnupResponse#sprint()})
 * @param startAt         when the sprint started, {@code null} if it never did
 * @param endAt           the planned end. Informational: it bounds nothing in this record
 * @param completedAt     when it was actually completed, {@code null} while it is still running.
 *                        This is the boundary every list below is measured against
 * @param committed       what was in the sprint when it started — the commitment batch the start
 *                        wrote, one {@code ADDED} per member dated {@code startAt}
 * @param addedAfterStart what joined afterwards. The number that makes a missed sprint legible
 * @param removedBeforeEnd what was pulled out while the sprint was running. Distinct from
 *                        {@code carriedOver}: this is work someone took off the sprint, not work
 *                        the sprint ran out of time for
 * @param completed       what was in the sprint at its end and closed by then. Identical in
 *                        membership to the last point of the burn-up's completed line, on purpose:
 *                        two reports over one ledger that disagreed about what "done in this
 *                        sprint" means would discredit both
 * @param carriedOver     what was in the sprint at its end and not closed — for a completed sprint,
 *                        exactly what the completion moved to the backlog or to the next sprint;
 *                        for a running one, what is still open right now
 * @param totals          the header line
 * @param meta            the shared provenance block
 */
public record SprintReviewResponse(
        SprintRef sprint,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime completedAt,
        SprintReviewList committed,
        SprintReviewList addedAfterStart,
        SprintReviewList removedBeforeEnd,
        SprintReviewList completed,
        SprintReviewList carriedOver,
        SprintReviewTotals totals,
        ReportMeta meta
) {}
