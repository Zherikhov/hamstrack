package com.hamstrack.common.util;

import java.math.BigDecimal;

/**
 * Wire normalization for the story-point decimals introduced by HD-22
 * (agile-sprints-proposal §7: "point values are JSON numbers, ≤2 decimals, trailing
 * zeros stripped").
 *
 * <p>Shared by every place a {@code BigDecimal} point value reaches the API —
 * {@code IssueResponse.storyPoints}, {@code SectionStats}, {@code SprintResponse} and
 * the completion report — because the two failure modes below are easy to reintroduce
 * one DTO at a time.
 */
public final class Points {

    private Points() {}

    /**
     * The largest estimate {@code issues.story_points NUMERIC(5,2)} and
     * {@code issues_story_points_ck} accept. The lower bound is 0 (an estimate is never
     * negative); {@code null} means "unestimated", which is deliberately NOT zero.
     */
    public static final BigDecimal MAX_STORY_POINTS = new BigDecimal("999");

    /** At most 2 decimals — 0.5, 1.5 and 13 are all valid scale values; 1.234 is not. */
    public static final int MAX_STORY_POINT_SCALE = 2;

    /**
     * Is this value inside the story-point domain, {@code 0 … 999}?
     *
     * <p>Shared by the WRITE path ({@code IssueService.requireValidStoryPoints} → 422)
     * and the QUERY path ({@code HqlValueResolver} → 422 on
     * {@code storyPoints > 1e999999999}). They must not drift: an operand the write path
     * would reject has no matching row anyway, and letting it through to PostgreSQL
     * turns a nonsense filter into a numeric-overflow 500 at parameter conversion
     * (SQLSTATE 22003) — a free stack trace for any member.
     *
     * <p>{@code compareTo} short-circuits on the adjusted exponent, so an absurd literal
     * is rejected without ever expanding it.
     */
    public static boolean inStoryPointRange(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(MAX_STORY_POINTS) <= 0;
    }

    /** Does this value carry at most {@link #MAX_STORY_POINT_SCALE} significant decimals? */
    public static boolean hasStoryPointScale(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= MAX_STORY_POINT_SCALE;
    }

    /**
     * Render a point value as a clean JSON number.
     *
     * <p>Two things are being fixed here:
     * <ul>
     *   <li><strong>Trailing zeros.</strong> The column is {@code NUMERIC(5,2)}, so
     *       JDBC hands back {@code 5.00} for an issue estimated at 5 — which Jackson
     *       writes as {@code 5.00}. Harmless to a JSON parser, noisy in a UI that
     *       renders the raw value.</li>
     *   <li><strong>Scientific notation.</strong> {@code new BigDecimal("100")
     *       .stripTrailingZeros()} yields {@code 1E+2}, and Jackson serializes a
     *       {@code BigDecimal} via {@code toString()} — so a 100-point sprint would go
     *       out as {@code 1E+2}. Forcing a negative scale back to zero is therefore not
     *       cosmetic; it is a wire-contract fix.</li>
     * </ul>
     *
     * @param value the stored/aggregated value, or null
     * @return the normalized value, or null when {@code value} is null (null means
     *         "unestimated", which is deliberately NOT zero)
     */
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        var stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** {@link #normalize(BigDecimal)} with null collapsed to {@code 0} — for SUMs, never for an estimate. */
    public static BigDecimal normalizeSum(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : normalize(value);
    }
}
