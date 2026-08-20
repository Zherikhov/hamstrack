package com.hamstrack.report.ratelimit;

import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the reports budget across the <strong>whole reports base path</strong>
 * (HD-28 R1 round 2, item 1).
 *
 * <p><strong>An interceptor on a path, not a check inside a service.</strong> Five more
 * report slices are specified to land on this base path, plus the {@code .csv} variants of
 * R7. A per-service guard would be a line each of them has to remember; the one that forgets
 * is unbounded, and nothing fails until it is being abused. A path binding is inherited by
 * construction: a new report is throttled the moment its {@code @GetMapping} exists.
 *
 * <p>Not a servlet {@code Filter} (the {@code AuthRateLimitFilter} shape) because servlet URL
 * patterns cannot express {@code /api/workspaces/*}{@code /projects/*}{@code /reports/**} —
 * they allow a prefix or an extension and nothing else, so a filter would have to be mapped
 * to {@code /api/*} and re-implement the path test by hand. A {@code PathPattern} says it
 * exactly.
 *
 * <p>Adding a {@link WebMvcConfigurer} is additive — it does not switch off Boot's MVC
 * auto-configuration the way {@code @EnableWebMvc} would — so static-resource handling and
 * the SPA fallback are unaffected. The search surface adds a second one
 * ({@code SearchRateLimitConfig}) with its own budget; a handler under both is charged to both.
 */
@Configuration
@Order(ReportRateLimitConfig.ORDER)
@RequiredArgsConstructor
public class ReportRateLimitConfig implements WebMvcConfigurer {

    /**
     * <strong>Reports run FIRST, and the order is load-bearing rather than cosmetic</strong>
     * (HD-140 R6 round 3, item 1). Without an explicit order, {@code AnnotationAwareOrderComparator}
     * falls back to bean-registration order — stable within a JVM, guaranteed by nothing across a
     * Boot upgrade or a scan-order change.
     *
     * <p>The 200-or-429 decision is safe either way (if either budget is exhausted the request is
     * refused, so "the lower configured value binds" holds unconditionally). Two things are not. Which 429 a
     * caller reads and which metric counts it would be arbitrary — a diagnostic loss. And the
     * substantive one: <strong>a request refused by one budget may already have spent a unit of the
     * other</strong>. With search first, every insights request the reports budget refuses still
     * charges the search budget, so a client ignoring {@code Retry-After} at an exhausted reports
     * budget burns its search allowance too and eventually locks itself out of the plain search box
     * — a reports overrun bleeding into the search pot, which is precisely the independence the
     * two-pot design exists to have.
     *
     * <p>Report-first makes "insights is a report that happens to live on the search path"
     * literally true: a report-refused insights request never spends search budget, a successful
     * one correctly spends both, and both the message and the metric are deterministic.
     * {@code ThrottleCoverageTest} asserts the resulting chain order, so this is not an annotation
     * anyone can strip as decoration.
     */
    public static final int ORDER = 0;

    /**
     * Every report of every project of every workspace. Deliberately the base path rather
     * than {@code …/reports/flow}: the budget belongs to the surface, not to R1's one
     * endpoint.
     */
    static final String REPORTS_PATH = "/api/workspaces/*/projects/*/reports/**";

    /**
     * The Insights panel (HD-140 R6), which is a report that does NOT live under the reports base
     * path — its dataset is an HQL query, so it hangs off {@code /search} instead.
     *
     * <p><strong>A second pattern is needed precisely because the first one is a path
     * binding.</strong> Everything the class javadoc says about inheriting the budget by
     * construction holds only for reports that land under {@code /reports}; the moment one lands
     * elsewhere it is unthrottled, and nothing fails until it is being abused. Not hypothetical
     * here: the insights aggregate is O(matching issues) and, being a POST, is the one report no
     * HTTP cache anywhere will absorb a repeat of. So it is listed explicitly, and the pair of
     * patterns is the reminder that this file is where a new report surface gets its budget.
     *
     * <p><strong>Kept, not deleted, now that {@code SearchRateLimitConfig} covers
     * {@code …/search/**} and therefore covers this path too</strong> (HD-140 R6 round 2, security
     * item 12). The two budgets are sized for two different behaviours — search is looser because
     * a person typing is its normal traffic — so dropping this line would <em>raise</em> the
     * panel's allowance from the reports budget to the search one as a side effect of hardening
     * something else, and would break the property {@code InsightsThrottleTest} pins: one pot for
     * the whole reports surface, no doubled allowance from alternating between a project report
     * and the panel. Insights therefore spends both budgets and <strong>the lower configured value
     * binds</strong>, which is the correct reading of "this endpoint is a report that happens to
     * live on the search path".
     *
     * <p>That phrasing is deliberate and is the canonical one across every artefact describing this
     * redundancy. It is <em>not</em> "the tighter (reports) one binds": that names a budget, and it
     * is true only while 60 sits below 120. An operator who sets
     * {@code SEARCH_REQUESTS_PER_MINUTE=30} makes the search budget the binding one and turns every
     * sentence that identified reports as the tight side false at once — including, if it were
     * written that way here, the sentence a maintainer reads immediately before deciding whether
     * {@link #INSIGHTS_PATH} is deletable. "The lower configured value" holds under any
     * configuration, which is the only reason to prefer it.
     *
     * <p><strong>{@link #ORDER} is the mechanism that makes that reading exact.</strong> Spending
     * two budgets is only well defined if the spending order is: reports first means an insights
     * request refused as a report never touches the search pot. Do not remove the ordering while
     * keeping this pattern — together they are one design, and separately they are a leak from one
     * budget into the other.
     */
    static final String INSIGHTS_PATH = "/api/workspaces/*/search/insights";

    private final ReportRateLimiter reportRateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PrincipalThrottleInterceptor(reportRateLimiter))
                .addPathPatterns(REPORTS_PATH, INSIGHTS_PATH);
    }
}
