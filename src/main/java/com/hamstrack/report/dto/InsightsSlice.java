package com.hamstrack.report.dto;

import java.math.BigDecimal;

/**
 * One bar: a bucket of the slice dimension plus its <strong>exact</strong> totals.
 *
 * <p><strong>Exact even when the segment breakdown is truncated</strong>, and that is why this
 * record exists instead of letting the client sum the cells. The cells are capped
 * ({@code InsightsService.MAX_CELLS}); a bar whose height was the sum of its surviving cells would
 * silently shrink when the cap bit, which is a wrong number with no symptom. These totals come
 * from their own {@code GROUP BY} over the slice alone, so the bar is right whatever happened to
 * the breakdown.
 *
 * <p>All three numbers are always populated regardless of the requested measure — see
 * {@link InsightsMeasure} for why the measure ranks rather than filters.
 *
 * @param bucket           which value of the slice dimension this is
 * @param count            distinct issues in this bucket
 * @param points           sum of {@code issues.story_points}; unestimated issues contribute 0.
 *                         Never null — an all-unestimated bucket is {@code 0}, which is a fact,
 *                         with {@code unestimatedCount} beside it saying how much of that zero is
 *                         missing data rather than free work (§1.2 failure mode #4)
 * @param unestimatedCount how many of {@code count} carry no estimate
 */
public record InsightsSlice(
        InsightsBucket bucket,
        long count,
        BigDecimal points,
        long unestimatedCount
) {}
