package com.hamstrack.report.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.ratelimit.PerPrincipalMinuteBudget;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The per-principal budget for the whole reports surface (HD-28 R1 round 2, item 1).
 *
 * <h2>Why a report needs a throttle when no other read does</h2>
 * Every other read in this product is bounded by what it returns. A report is not: the flow
 * report's opening balance counts every issue the project ever created and closed
 * <em>before</em> the window, so its cost is O(project history) and does not shrink when the
 * caller asks for a narrower window. {@code app.reports.max-window-days} bounds the response
 * array, not the work. Meanwhile {@code Cache-Control: private} — which is correct, since a
 * report is one tenant's data — guarantees that no shared cache ever absorbs a repeat, so
 * every single request reaches PostgreSQL. The auth limiter does not help: it is registered
 * on six explicit auth URLs and skips every non-POST. The result before this class existed
 * was that one authenticated member in a {@code while} loop could saturate
 * {@code DB_POOL_MAX_SIZE} with entirely legal 200s. Deferrable on a self-hosted install,
 * not on Cloud, and worse in R3 where reports become row-level.
 *
 * <h2>Shape, and where the shape now lives</h2>
 * A per-minute fixed window keyed on the caller, refused with a {@link
 * com.hamstrack.common.ratelimit.RateLimitedException} so the shared handler adds
 * {@code Retry-After}. All of that — the counting, the eviction, the metric, the per-principal
 * argument — moved to {@link PerPrincipalMinuteBudget} when the search surface got a budget of its
 * own (HD-140 R6 round 2, security item 12), because two hand-copied throttles are a throttle
 * where a fix lands in one of them. What is left here is what is specific to reports: which
 * property sets the number, which metric tag counts the refusals, and the noun in the message.
 *
 * <p>{@code app.rate-limit.enabled} is honoured as the master switch, because it already is
 * one for every other limiter in the app and because a test suite that turns limiting off
 * expects it off everywhere.
 */
@Service
public class ReportRateLimiter extends PerPrincipalMinuteBudget {

    private final ReportProperties reportProperties;
    private final RateLimitProperties rateLimitProperties;

    public ReportRateLimiter(ReportProperties reportProperties,
                             RateLimitProperties rateLimitProperties,
                             ProductMetrics metrics) {
        super(metrics);
        this.reportProperties = reportProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected boolean enabled() {
        return rateLimitProperties.enabled();
    }

    @Override
    protected long limit() {
        return reportProperties.requestsPerMinute();
    }

    @Override
    protected RateLimitKind kind() {
        return RateLimitKind.REPORT_REQUESTS;
    }

    @Override
    protected String surface() {
        return "report requests";
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void sweep() {
        evictStaleEntries();
    }
}
