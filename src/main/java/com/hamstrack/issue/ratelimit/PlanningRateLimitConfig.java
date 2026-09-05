package com.hamstrack.issue.ratelimit;

import com.hamstrack.common.ratelimit.ExpensiveReadConcurrencyLimit;
import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import com.hamstrack.report.ratelimit.ReportRateLimitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the planning budget and the occupancy bound across the <strong>whole planning base
 * path</strong> (HD-174).
 *
 * <p>The fourth configurer, and the reports interceptor's argument applied to the surface that had
 * neither control: bind to the path, not to a handler, so a fourth planning read inherits both the
 * moment its {@code @GetMapping} exists instead of when somebody remembers. Until this existed,
 * {@code GET …/backlog} — the largest single response this product produces, assembled in one
 * read-only transaction holding one connection across up to 32 statements — answered with no
 * budget of any kind, and {@code BacklogController}'s own javadoc said so and deferred the
 * question here.
 *
 * <p><strong>Both controls on ONE interceptor</strong>, exactly as {@code ReportRateLimitConfig}
 * and {@code SearchRateLimitConfig} do it: the rate budget is spent first and the permit last, so a
 * request the budget will refuse never held a share of the connection pool. That ordering is
 * {@link PrincipalThrottleInterceptor}'s and is inherited, not re-implemented.
 *
 * <p><strong>The {@code @Order} is declared for determinism and is not load-bearing today</strong>,
 * stated as a condition rather than as a fact about today's paths: no other registered pattern
 * overlaps {@link #PLANNING_PATH}, so no request spends two budgets here and there is nothing for
 * an order to decide. <em>If a future pattern ever overlaps the planning path, these two orders
 * become load-bearing for exactly the reason {@link ReportRateLimitConfig#ORDER} is — a request
 * refused by one budget must not already have spent a unit of the other — and whoever adds it owes
 * that constant's argument a re-read.</em> Without an explicit order,
 * {@code AnnotationAwareOrderComparator} falls back to bean-registration order, which is stable
 * within a JVM and guaranteed by nothing across a Boot upgrade.
 */
@Configuration
@Order(ReportRateLimitConfig.ORDER + 2)
@RequiredArgsConstructor
public class PlanningRateLimitConfig implements WebMvcConfigurer {

    /**
     * The planning surface of every project of every workspace: the aggregate
     * {@code GET …/backlog} and every section read under {@code …/backlog/sections/…}.
     *
     * <p><strong>One pattern, and it is the surface rather than the endpoints.</strong> A
     * {@code PathPattern} ending in {@code /**} matches <em>zero or more</em> trailing segments, so
     * this covers the bare {@code …/backlog} as well as its sections — the same reading
     * {@code SEARCH_PATH} relies on, and the same silent failure if it were wrong: nothing would
     * break, the surface would simply keep working unbudgeted. {@code PlanningThrottleParityTest}
     * asserts it against the real handler mapping rather than trusting the reading.
     *
     * <p>It is deliberately NOT {@code …/projects/*}{@code /**}, which would charge one pot for
     * issues, versions, components and config that are bounded elsewhere or on a different axis.
     * And it deliberately does not reach {@code GET …/issues} (board + list) or
     * {@code GET …/sprints}: those are a <strong>weaker</strong> case — not because their work is
     * bounded by what they return (no filtered, ordered, capped query's work is) but because
     * neither runs an unconditional, cap-blind project-wide aggregation, which is the property this
     * budget is earned by. Extending the pattern to them is an argument to be made on their own
     * worst case, and this file deliberately does not pre-answer it.
     */
    static final String PLANNING_PATH = "/api/workspaces/*/projects/*/backlog/**";

    private final PlanningRateLimiter planningRateLimiter;

    /**
     * <strong>The existing expensive-read share, not a share of its own</strong> (ADR-0031) — and
     * this is the more important half of HD-174.
     *
     * <p>{@code BacklogService.view} is {@code @Transactional(readOnly = true)} over the whole
     * method, so one Hikari connection is held across {@code 12 + N} statements — 32 at
     * {@code AGILE_MAX_OPEN_SPRINTS=20}. {@code DB_STATEMENT_TIMEOUT_MS} bounds each of them and
     * nothing bounds their sum, so the worst-case hold for one planning aggregate is ~320 seconds
     * against a default pool of 10, while everybody else waits out the pool's acquisition bound and
     * then fails. A rate budget cannot bound that even in principle: it spends the same unit whether a request takes
     * 8 ms or 8 s, so its protection evaporates precisely as the instance slows down.
     *
     * <p><strong>Why not a second share.</strong> A {@code PlanningConcurrencyLimit} with its own
     * ceiling would turn {@code PoolShareConsistency}'s hard rule 2 into a rule about a SUM and
     * {@code ExpensiveReadShare}'s derive-from-the-pool default into a PARTITION — degenerate on
     * the small pools this project recommends, where a pool of 4 gives 1 permit per surface and
     * serialises a whole surface instance-wide. A literal that must sit below the pool crash-looped
     * every small self-host on the HD-182 upgrade; a pair of literals that must jointly sit below
     * it is that hazard squared. So HD-174 adds <strong>no</strong> number that has to sit below
     * {@code DB_POOL_MAX_SIZE}, and therefore no new way to fail a boot.
     *
     * <p><strong>The cost, stated rather than tolerated:</strong> planning, reports and search now
     * refuse each other under saturation, and a planning-heavy team can be the reason a colleague's
     * report is refused. Accepted, because the alternative is not "no refusal" — it is that
     * colleague waiting out the pool's acquisition bound behind a planning read that may
     * legitimately hold its connection for minutes, and then being refused anyway. The per-principal ceiling (3 of 6) is what
     * stops one planner being the whole cause, and it needed no re-argument: the Backlog page's
     * mount puts ONE request on this surface and {@code refreshSections} iterates with
     * {@code for … await}, so this surface does not raise the largest correct-client burst.
     */
    private final ExpensiveReadConcurrencyLimit expensiveReadConcurrency;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new PrincipalThrottleInterceptor(planningRateLimiter, expensiveReadConcurrency))
                .addPathPatterns(PLANNING_PATH);
    }
}
