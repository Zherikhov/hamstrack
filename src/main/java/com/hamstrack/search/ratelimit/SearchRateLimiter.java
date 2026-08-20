package com.hamstrack.search.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.config.SearchProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.ratelimit.PerPrincipalMinuteBudget;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The per-principal budget for the HQL search surface (HD-140 R6 round 2, security item 12).
 *
 * <h2>Why search needed one, and why it is not the reports budget</h2>
 * The Insights panel arrived throttled because it is a report. The security review of that change
 * then asked the obvious next question and found the answer embarrassing: {@code POST …/search},
 * {@code GET …/search/schema} and {@code GET …/search/suggest} — the endpoints the panel's own
 * dataset comes from — had <strong>no budget at all</strong>. Nor, until one round later, did
 * saved-filter CRUD under {@code …/filters/**}, which validates HQL on every write and so pays the
 * same {@code ResolutionContext} build {@code /search/schema} does: the first sweep found the
 * endpoints named after search and missed the ones doing search's work under another name. And
 * search is the cheaper target only
 * in appearance: up to {@code HqlParser.MAX_PREDICATES} leaves, each of which may be two unanchored
 * unindexable {@code LIKE}s over a TEXT column or a correlated {@code EXISTS} with a JSONB cast, and
 * the endpoint runs the whole predicate <em>twice</em> per request (count + page). Anybody wanting
 * to saturate the connection pool had no reason to use the throttled door.
 *
 * <p>It gets its own pot rather than joining the reports pot because the two surfaces have
 * different normal behaviour — a person typing in a search box legitimately fires several requests
 * a minute, a person reading a chart does not — and starving the typist to protect the charts would
 * be the wrong trade. See {@link SearchProperties} for how the number is sized.
 *
 * <p>Everything else — the fixed window, the per-principal key, the 429 + {@code Retry-After}, the
 * metric-not-a-log-line refusal, the master switch — is {@link PerPrincipalMinuteBudget}'s, shared
 * with the reports budget rather than copied beside it.
 */
@Service
public class SearchRateLimiter extends PerPrincipalMinuteBudget {

    private final SearchProperties searchProperties;
    private final RateLimitProperties rateLimitProperties;

    public SearchRateLimiter(SearchProperties searchProperties,
                             RateLimitProperties rateLimitProperties,
                             ProductMetrics metrics) {
        super(metrics);
        this.searchProperties = searchProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected boolean enabled() {
        return rateLimitProperties.enabled();
    }

    @Override
    protected int limit() {
        return searchProperties.requestsPerMinute();
    }

    @Override
    protected RateLimitKind kind() {
        return RateLimitKind.SEARCH_REQUESTS;
    }

    @Override
    protected String surface() {
        return "search requests";
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void sweep() {
        evictStaleEntries();
    }
}
