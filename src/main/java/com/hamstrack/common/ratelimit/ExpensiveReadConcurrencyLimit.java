package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.ExpensiveReadProperties;
import com.hamstrack.common.config.ExpensiveReadShare;
import com.hamstrack.common.config.StatementTimeoutProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The occupancy bound over the <strong>expensive-read surface</strong> — every surface that holds
 * a pool connection while it works (HD-182, extended by HD-174).
 *
 * <p>Deliberately described as a CATEGORY rather than by its members: it used to name "the reports
 * base path, the HQL search paths, saved filters and the workspace storage breakdown", and that
 * enumeration was false one slice later, when the planning surface joined it — an enumeration goes
 * stale one entry before the property does. The property is the one {@link ExpensiveReadProperties}
 * states, and it is what a reader should reason from; the current membership is derived at runtime
 * by {@code ThrottleCoverageTest.expensiveReadSurface()} and sealed, per configurer, by
 * {@code theThrottledPathSetIsSealed}.
 *
 * <p>The one concrete {@link PerPrincipalInFlightLimit} today. It is spent by the same
 * {@link PrincipalThrottleInterceptor} instances, on the same registrations, in whichever
 * {@code *RateLimitConfig} carries a surface — so there is <strong>one</strong> pattern list per
 * configurer and never a second one for occupancy, and a new expensive read inherits <em>both</em>
 * controls in one edit. ADR-0031 is why a new surface joins THIS share rather than getting one of
 * its own: a second ceiling would turn {@link ExpensiveReadShare}'s derive-from-the-pool default
 * into a partition that is degenerate on small pools.
 *
 * <p>Because the write budget's interceptor is the same type and deliberately does NOT carry an
 * occupancy bound, "there is a {@code PrincipalThrottleInterceptor} in front of this handler" no
 * longer implies "this handler is concurrency-bounded" — which is why
 * {@code ThrottleCoverageTest.everyExpensiveReadHandlerIsAlsoConcurrencyBounded} asks the
 * <em>which-controls</em> question rather than the type question.
 *
 * <p><strong>Its own switch</strong> ({@code app.expensive-read.limit-enabled}), deliberately
 * outside {@code app.rate-limit.enabled} — see {@link ExpensiveReadProperties#limitEnabled()}.
 * There is nothing in this class that reads {@code RateLimitProperties}, and
 * {@code ExpensiveReadPropertiesTest} asserts that structurally: removing a bound on the
 * connection pool must not require disabling brute-force protection on the login page.
 */
@Component
public class ExpensiveReadConcurrencyLimit extends PerPrincipalInFlightLimit {

    /**
     * A permit may outlive {@code statement_timeout} legitimately — several statements, plus a
     * body assembled in Java — so the watchdog's ceiling is that bound plus a slack big enough
     * that no working request can reach it. It is a backstop against a permit held for ever, not
     * a request deadline: the value that matters is that it is FINITE.
     */
    private static final long STALE_PERMIT_SLACK_MS = 60_000;

    private final ExpensiveReadProperties properties;
    private final ExpensiveReadShare share;
    private final StatementTimeoutProperties statementTimeout;
    private final ProductMetrics metrics;

    public ExpensiveReadConcurrencyLimit(ExpensiveReadProperties properties,
                                         ExpensiveReadShare share,
                                         StatementTimeoutProperties statementTimeout,
                                         ProductMetrics metrics) {
        super(metrics);
        this.properties = properties;
        this.share = share;
        this.statementTimeout = statementTimeout;
        this.metrics = metrics;
    }

    /**
     * The gauge is registered here rather than in the primitive because a meter NAME belongs to
     * one surface, and {@link ProductMetrics} is where every name in this product lives.
     */
    @PostConstruct
    void registerOccupancyGauge() {
        metrics.registerExpensiveReadInFlight(this::inFlight);
    }

    @Override
    protected boolean enabled() {
        return properties.limitEnabled();
    }

    /**
     * From {@link ExpensiveReadShare}, not straight from the record: either ceiling may have been
     * derived from the connection pool because the operator configured neither, and the number the
     * bulkhead enforces has to be the number in force.
     */
    @Override
    protected int maxPerPrincipal() {
        return share.maxInFlightPerPrincipal();
    }

    @Override
    protected int maxTotal() {
        return share.maxInFlight();
    }

    @Override
    protected long acquireWaitMs() {
        return properties.acquireWaitMs();
    }

    /**
     * {@code DB_STATEMENT_TIMEOUT_MS} plus {@link #STALE_PERMIT_SLACK_MS}, so the backstop moves
     * with the bound an operator actually tuned rather than being a second number to keep in step.
     */
    @Override
    protected long maxPermitAgeMs() {
        return statementTimeout.statementTimeoutMs() + STALE_PERMIT_SLACK_MS;
    }

    @Override
    protected void countForcedRelease() {
        metrics.expensiveReadPermitForceReleased();
    }

    /**
     * <strong>Every 30 s, which is far below the ceiling it enforces</strong> — a sweep is a walk
     * over at most {@code max-in-flight} entries with no lock, no allocation worth naming and no
     * database work, so it costs one of the FEW {@code @Scheduled} threads a few microseconds.
     * Sweeping rarely would be worse than useless: the point is that a permit's occupancy has a
     * finite upper bound, and the sweep interval is added to it.
     */
    @Scheduled(fixedDelay = 30 * 1000, initialDelay = 30 * 1000)
    void sweep() {
        sweepStalePermits();
    }

    @Override
    protected RateLimitKind perPrincipalKind() {
        return RateLimitKind.EXPENSIVE_READ_IN_FLIGHT;
    }

    @Override
    protected RateLimitKind surfaceKind() {
        return RateLimitKind.EXPENSIVE_READ_SURFACE_FULL;
    }

    /**
     * Plain {@code "requests"}: the sentence it lands in is about the reader's own conduct, and
     * calling them "expensive requests" there would read as a judgement of what they asked for
     * rather than of how many at once.
     */
    @Override
    protected String callerNoun() {
        return "requests";
    }

    @Override
    protected String surfaceNoun() {
        return "expensive requests";
    }
}
