package com.hamstrack.report.dto;

/**
 * What the Insights panel plots on the y axis (reports-proposal §2.6): issue count, story
 * points, or nothing.
 *
 * <p><strong>Not {@link ReportMeasure}, on purpose.</strong> That enum is bound straight off a
 * query string by {@code /sprint-burnup} and {@code /velocity}; adding a third constant to it
 * would make {@code ?measure=NONE} <em>bind</em> on both of those and then mean nothing there.
 * Two enums, each closed over what its own endpoint can answer, is cheaper than one enum with a
 * value that is legal on two endpoints and meaningless on them.
 *
 * <h2>What the measure actually decides — read this before assuming it filters the response</h2>
 * <strong>Every bucket carries both numbers, always.</strong> {@code count}, {@code points} and
 * {@code unestimatedCount} come out of one {@code GROUP BY}: the two extra aggregate columns ride
 * the same scan and cost nothing measurable, so computing one and discarding the other would buy
 * the server nothing and cost the client a round trip every time somebody flips the toggle.
 *
 * <p>What the measure decides is <strong>rank</strong> — which buckets survive the caps, and in
 * what order they arrive. A response is the top {@code InsightsService.MAX_SLICES} buckets
 * <em>by this measure</em>, so {@code COUNT} and {@code POINTS} can return different buckets from
 * the same query, and that is the whole point: "the ten statuses with the most issues" and "the
 * ten statuses with the most points" are different questions and a truncated top-N must know
 * which one it was asked.
 */
public enum InsightsMeasure {

    /** One issue is one unit. The default — the only measure every project has (see {@link ReportMeasure#COUNT}). */
    COUNT,

    /**
     * {@code issues.story_points}. Unestimated issues contribute <strong>0</strong> and are
     * counted beside the sum in {@code unestimatedCount}, exactly as everywhere else in this
     * epic — reporting the zero alone is documented failure mode #4 (§1.2, §6).
     *
     * <p>Accepted whatever the visible projects declare: {@code estimation} being off hides the
     * toggle in the UI and never changes a status code (Rule A), and points that already exist
     * still render (Rule B).
     */
    POINTS,

    /**
     * No y axis — the panel is being used as a breakdown table rather than a chart.
     *
     * <p>Server-side this ranks by {@code count}, because the counts are what the {@code GROUP BY}
     * produces and a bucket has to be ordered by <em>something</em> to be capped at all. The
     * distinction is real but it is entirely the client's: the response says {@code NONE} back, and
     * the panel draws no bars. An endpoint that pretended not to know the counts here would return
     * strictly less data for exactly the same work, and the table under the chart would be blank.
     */
    NONE
}
