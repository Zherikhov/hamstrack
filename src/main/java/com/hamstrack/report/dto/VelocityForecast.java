package com.hamstrack.report.dto;

/**
 * The band (reports-proposal §2.5) — <em>"Recent sprints delivered between 14 and 23 issues; plan
 * for ~18 (p50) and treat 23 (p85) as a stretch. Based on 6 sprints."</em>
 *
 * <p>This record <strong>is</strong> the refusal in §1.4. Velocity's documented harm is not the
 * arithmetic, it is the audience: a single number labelled "velocity" is read as a productivity
 * score, compared between teams, and answered by inflating estimates. A p50/p85 pair that always
 * arrives with its own sample size is much harder to misuse that way — it has no single value to
 * quote, it is explicitly a range, and it says how little it is based on.
 *
 * <h2>Suppressed below three sprints, and that is the feature</h2>
 * Under {@code VelocityService.MIN_FORECAST_SPRINTS} completed sprints both percentiles are
 * {@code null} while {@link #sampleSize} still states what there was, and the client prints
 * <em>"Not enough completed sprints to forecast (need 3, have 2)"</em>. Exactly R3's percentile rule
 * one level up: a p85 over two sprints is arithmetically defined and informationally worthless — it
 * is one sprint's number wearing a statistic's name — and printing noise is worse than printing
 * nothing (§1.7, §6). The object is always <em>present</em>, so a client reads
 * {@code forecast.p50 == null} rather than testing the parent for existence; suppression is a fact
 * about the data, not a change of shape.
 *
 * <h2>Percentiles, never a rolling average, and never a mean</h2>
 * §1.7 refuses rolling averages on evidence (Jira's is computed over a share of displayed
 * <em>items</em>, so it moves when throughput moves even if the process does not). A mean is refused
 * for a plainer reason: one washed-out sprint drags it somewhere no sprint ever was, and a forecast
 * that names an outcome the team has never had is the "these numbers don't match what I expected"
 * failure the whole {@code meta} convention exists to prevent.
 *
 * @param p50        the median of the sampled sprints' {@code completed} values, in the requested
 *                   measure — <em>plan for about this</em> — or {@code null} when suppressed.
 *                   A {@code Double} rather than the {@code BigDecimal} the bars carry, and the
 *                   difference is meant: a bar is an exact sum of things that happened, a
 *                   percentile is an interpolated estimate of something that has not
 * @param p85        the 85th percentile — <em>a stretch, not a target</em> — on the same terms. 85
 *                   rather than 90 or 95 for the reason {@link ReportPercentiles} gives: it is the
 *                   number the flow-metrics literature uses for a "most work finishes within"
 *                   statement, and at six-to-twelve samples a p95 is one sprint wide
 * @param sampleSize how many completed sprints these numbers came from. <strong>Always stated, and
 *                   stated even when the band is suppressed</strong> — it is the sentence's last
 *                   clause, and a band whose sample size a reader has to go and count is the
 *                   scoreboard this record exists to avoid being
 */
public record VelocityForecast(Double p50, Double p85, int sampleSize) {

    /** The suppressed band, for a sample too small to say anything with. */
    public static VelocityForecast suppressed(int sampleSize) {
        return new VelocityForecast(null, null, sampleSize);
    }
}
