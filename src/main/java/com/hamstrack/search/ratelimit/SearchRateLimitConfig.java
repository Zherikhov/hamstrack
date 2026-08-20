package com.hamstrack.search.ratelimit;

import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import com.hamstrack.report.ratelimit.ReportRateLimitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the search budget across the <strong>whole search base path</strong>
 * (HD-140 R6 round 2, security item 12).
 *
 * <p>The reports interceptor's argument, applied to the surface the reports interceptor did not
 * cover: bind the budget to the path, not to a handler, so the next endpoint under it inherits the
 * bound by construction instead of by somebody remembering. Until this existed, the application had
 * exactly two limiters — six literal auth URLs and the reports interceptor — and the search
 * endpoints, which run the same predicate as the throttled Insights panel and run it twice per
 * request, had no budget at all.
 *
 * <p>{@link #SEARCH_PATH} deliberately ends in {@code /**}, which in a {@code PathPattern} matches
 * <strong>zero or more</strong> trailing segments — so it covers {@code POST …/search} itself as
 * well as {@code /schema}, {@code /suggest} and {@code /insights}. {@code SearchThrottleTest}
 * asserts that on the bare path rather than trusting the reading, because "the pattern does not
 * match the endpoint you thought" is a silent failure: everything keeps working, unthrottled.
 *
 * <p><strong>Ordered after the reports budget</strong> ({@link ReportRateLimitConfig#ORDER}), which
 * matters only where the two patterns overlap — the Insights panel — and matters a lot there: a
 * request refused by the reports budget must not have already spent a unit of this one. See that
 * constant for the full argument; the two annotations are one design.
 */
@Configuration
@Order(ReportRateLimitConfig.ORDER + 1)
@RequiredArgsConstructor
public class SearchRateLimitConfig implements WebMvcConfigurer {

    /**
     * The HQL surface of every workspace: {@code POST …/search}, {@code GET …/search/schema},
     * {@code GET …/search/suggest} — and {@code POST …/search/insights}, which is <em>also</em>
     * bound to the reports budget on purpose (see {@code ReportRateLimitConfig.INSIGHTS_PATH}: it
     * is a report that happens to live here, so it spends both, reports first, and the lower
     * configured value binds — never "the tighter (reports) one", which stops being true the moment
     * an operator sets {@code SEARCH_REQUESTS_PER_MINUTE} below the reports budget).
     */
    static final String SEARCH_PATH = "/api/workspaces/*/search/**";

    /**
     * Saved filters, which are <strong>not</strong> under {@code /search} and are here anyway
     * (HD-140 R6 round 3, item 3).
     *
     * <p>Round 2 excluded them with a reason that was true about the wrong half: they are ordinary
     * CRUD over a small table and their queries are bounded by what they return — correct, and
     * beside the point, because <strong>creating or editing one validates its HQL</strong>. That
     * validation needs a {@code ResolutionContext}, and building one is roughly eight statements
     * including a workspace-wide label projection and a full member scan: the same work
     * {@code GET …/search/schema} does, which was judged worth a budget. So {@code POST …/filters}
     * with a deliberately invalid query was an unthrottled eight-statement 422 loop.
     *
     * <p>The rule that resolves it is the one that produced this whole round: <em>HQL validation is
     * search-surface work wherever it is mounted</em>. It is charged to the search budget rather
     * than given a third pot, because it is the same person doing the same thing — a filter is a
     * saved search — and 120/minute is far above any human editing of saved filters, so the list
     * and detail reads that share the path are not the ones this is aimed at.
     */
    static final String FILTERS_PATH = "/api/workspaces/*/filters/**";

    private final SearchRateLimiter searchRateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PrincipalThrottleInterceptor(searchRateLimiter))
                .addPathPatterns(SEARCH_PATH, FILTERS_PATH);
    }
}
