package com.hamstrack.issue.dto;

import java.math.BigDecimal;

/**
 * Roll-up of ONE planning section — a sprint or the backlog (HD-22,
 * agile-sprints-proposal §7).
 *
 * <p><strong>Always computed over the WHOLE section</strong> by the single grouped
 * query behind the planning view, never over the truncated page: a section capped at
 * {@code app.agile.section-max-issues} still shows honest totals (§4.5).
 *
 * @param issueCount       issues in the section after the request's filters
 * @param doneIssueCount   of those, the ones in a DONE-category status
 * @param points           sum of {@code storyPoints}; unestimated issues contribute
 *                         nothing (they are counted separately, not as zero)
 * @param donePoints       sum of {@code storyPoints} over the DONE-category issues
 * @param unestimatedCount issues with no {@code storyPoints} — "we didn't estimate
 *                         it" is a different statement from "it's free"
 */
public record SectionStats(
        int issueCount,
        int doneIssueCount,
        BigDecimal points,
        BigDecimal donePoints,
        int unestimatedCount
) {
    public static final SectionStats EMPTY =
            new SectionStats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
}
