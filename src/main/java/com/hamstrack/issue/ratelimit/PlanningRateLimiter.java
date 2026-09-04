package com.hamstrack.issue.ratelimit;

import com.hamstrack.common.config.PlanningProperties;
import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.ratelimit.PerPrincipalMinuteBudget;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The per-principal budget for the whole planning surface (HD-174).
 *
 * <h2>Why the biggest read in the product arrived without one</h2>
 * Because nobody decided. The reports budget was earned by O(project history); the search budget
 * by 50 leaf predicates run twice; the planning reads were spared by the observation that their
 * response is bounded by {@code MAX_PLANNING_VIEW_ROWS} — and that bound is <strong>20 000
 * assembled issues in one unpaged response</strong>, the same number as {@code app.reports.max-rows}.
 * What earns the budget is not the size: it is that {@code planningStats} is
 * <em>unconditional and cap-blind</em>, the same property {@code …/storage/projects} is on the
 * reports pot for.
 *
 * <p>Its own pot rather than the reports pot, for {@link com.hamstrack.search.ratelimit.SearchRateLimiter}'s
 * reason applied to a different behaviour: a card dragged across sections costs two section
 * refreshes, so an ordinary grooming session would reach 60/min in three minutes, and a 429 in the
 * middle of a drag-and-drop gesture reads as a product defect rather than as protection. See
 * {@link PlanningProperties} for how 240 is derived — the derivation is kept there, once.
 *
 * <p><strong>This is the cheaper half of HD-174.</strong> What actually protects the connection
 * pool is the occupancy bound the same registration carries
 * ({@code PlanningRateLimitConfig} injects {@code ExpensiveReadConcurrencyLimit}): a planning
 * aggregate holds one connection across up to 32 statements, and a rate budget spends the same
 * unit whether a request takes 8 ms or 8 s. This class catches a client in a loop; it does not
 * bound occupancy and never could.
 *
 * <p>Everything else — the fixed window, the per-principal key, the 429 + {@code Retry-After}, the
 * metric-not-a-log-line refusal, the master switch — is {@link PerPrincipalMinuteBudget}'s, shared
 * with the reports, search and write budgets rather than copied beside them.
 */
@Service
public class PlanningRateLimiter extends PerPrincipalMinuteBudget {

    private final PlanningProperties planningProperties;
    private final RateLimitProperties rateLimitProperties;

    public PlanningRateLimiter(PlanningProperties planningProperties,
                               RateLimitProperties rateLimitProperties,
                               ProductMetrics metrics) {
        super(metrics);
        this.planningProperties = planningProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected boolean enabled() {
        return rateLimitProperties.enabled();
    }

    @Override
    protected long limit() {
        return planningProperties.requestsPerMinute();
    }

    @Override
    protected RateLimitKind kind() {
        return RateLimitKind.PLANNING_REQUESTS;
    }

    @Override
    protected String surface() {
        return "planning requests";
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void sweep() {
        evictStaleEntries();
    }
}
