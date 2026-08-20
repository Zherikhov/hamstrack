package com.hamstrack.report.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One completed sprint's output — one bar of the velocity chart (reports-proposal §2.5).
 *
 * <p>Four numbers, all in the requested {@link ReportMeasure}: what the sprint committed to, what it
 * delivered, what arrived after the start, and what carried over. They are the sprint review's
 * headline figures (§2.4) for a sprint nobody has to open, which is the whole reason velocity can be
 * a forecast input rather than a scoreboard — a bar that says "18" beside a marker that says "21"
 * and a note that four issues arrived late is a description of what happened, not a score.
 *
 * <h2>The four numbers share the measure, deliberately</h2>
 * Under {@code POINTS} all four are point sums; under {@code COUNT} all four are issue counts. A
 * bar chart that mixed "41 points delivered" with "4 issues added" would be two units in one
 * tooltip, and the reader has no way to see which is which.
 *
 * <h2>Nothing here is keyed by a person, and nothing here may become so</h2>
 * §1.4's refusal, in the type system: this record has no assignee, no actor, no owner, and none may
 * be added — not as a field, not as a nested breakdown, not as a tooltip extra, not in a later CSV.
 * The velocity statement does not even select a person column, so the refusal holds one layer down
 * as well. {@code VelocityRefusalTest} fails if either changes.
 *
 * @param sprintId         the sprint, so the client can link to its review — the report that shows
 *                         the issues behind these four numbers, which is the only per-issue
 *                         breakdown velocity offers
 * @param name             its name, as the bar's label
 * @param startAt          when it started — the instant {@link #committed} is measured at
 * @param completedAt      when it was completed. Never null: only COMPLETED sprints are sampled,
 *                         and {@code sprints_completed_ck} guarantees the column. This is the
 *                         instant every other number here is measured at, and it is what orders the
 *                         bars
 * @param committed        what was in the sprint at {@link #startAt} — the commitment batch
 *                         {@code SprintService.start} wrote. Marked ON the bar rather than drawn as
 *                         a second bar: it is the plan, and the plan is context for the outcome,
 *                         not a competing outcome
 * @param completed        what was still in the sprint at {@link #completedAt} and closed by then.
 *                         <strong>The bar's height, and the only number the forecast band is
 *                         computed from</strong>
 * @param addedAfterStart  everything that entered after {@link #startAt}. The clause that turns "we
 *                         missed the plan" into "the plan changed" (§1.2) — without it a bar below
 *                         its own commitment marker reads as a failure whatever actually happened
 * @param carriedOver      what was still in the sprint at {@link #completedAt} and not closed. With
 *                         {@link #completed} it partitions what the sprint held at its end, so
 *                         {@code completed + carriedOver} is the denominator the review's headline
 *                         uses — never {@link #committed}, which counts a different population
 * @param unestimatedCount how many of the issues the sprint held at its end carried no estimate.
 *                         Reported under BOTH measures, because a {@code POINTS} bar built from a
 *                         sprint where nine of twenty-three issues were never estimated is a
 *                         silent zero nine times over — documented failure mode #4 (§1.2, §6) — and
 *                         a reader switching measures needs to see the same disclosure either way
 */
public record VelocitySprint(
        UUID sprintId,
        String name,
        OffsetDateTime startAt,
        OffsetDateTime completedAt,
        BigDecimal committed,
        BigDecimal completed,
        BigDecimal addedAfterStart,
        BigDecimal carriedOver,
        int unestimatedCount
) {}
