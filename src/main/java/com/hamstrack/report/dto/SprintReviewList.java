package com.hamstrack.report.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One of the sprint review's five labelled lists (reports-proposal §2.4), with the count and point
 * sum its heading prints.
 *
 * <p>The label is the field name on {@link SprintReviewResponse} — a {@code label} string inside
 * the record would be a second name for the same thing, and the two would eventually disagree.
 *
 * <p><strong>The five lists are labelled views, not a partition.</strong> An issue is in
 * {@code committed} and, if it landed, in {@code completed} too — that overlap is the whole point
 * of the retro's headline. Only {@code committed} and {@code addedAfterStart} are disjoint, and
 * only they add up to everything the sprint ever held.
 *
 * @param count            issues in the list
 * @param points           sum of their points, unestimated issues contributing nothing (never
 *                         zero-and-silent — see {@link #unestimatedCount}). Normalised, so a sum
 *                         is a clean JSON number rather than {@code 4.00} or {@code 1E+2}.
 *                         <p><strong>{@code null} when NOTHING in the list carried an
 *                         estimate</strong>, including for an empty list — "this list has no
 *                         points to show" and "these issues are worth zero points" are different
 *                         statements, and a sum of nothing rendered as {@code 0} is the same
 *                         silent zero {@code unestimatedCount} exists to refuse one row at a time.
 *                         Structural rather than derived: the alternative left every client
 *                         inferring emptiness from {@code count > unestimatedCount}, i.e.
 *                         re-deriving a fact the server already knew, in each of the five places
 *                         a list is rendered
 * @param unestimatedCount how many of {@code count} carried no estimate. Stated per list because
 *                         "committed 55 points" means something quite different when six of the
 *                         twenty-three issues were never estimated
 * @param issues           the rows, in the list's own order
 */
public record SprintReviewList(
        int count,
        BigDecimal points,
        int unestimatedCount,
        List<SprintReviewIssue> issues
) {
    /** A list with nothing in it — a sprint that never started has five of these. */
    public static final SprintReviewList EMPTY = new SprintReviewList(0, null, 0, List.of());
}
