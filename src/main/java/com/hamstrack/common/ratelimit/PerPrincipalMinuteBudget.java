package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-principal, per-minute fixed window — the mechanism behind every expensive-surface budget
 * in the app (reports, and as of HD-140 R6 round 2 the search surface as well).
 *
 * <p><strong>Shared rather than copied.</strong> The second budget was originally going to be a
 * second copy of this loop; a throttle whose counting, eviction, refusal shape and
 * {@code Retry-After} arithmetic exist twice is a throttle where a fix lands in one of them. What
 * differs between two budgets is a number, a metric tag and a noun — so those are the abstract
 * methods, and nothing else is.
 *
 * <p><strong>Per principal, not per resource</strong> (the reasoning is {@code ReportRateLimiter}'s
 * and it generalises): the cost is paid on behalf of whoever asks, a per-resource key would let one
 * colleague's open tab throttle a whole team, and — the load-bearing half — these budgets are spent
 * in an interceptor, i.e. <em>before</em> the controller resolves anything, so a per-resource key
 * would answer differently depending on whether a resource exists. Keyed on the caller, the 429 is
 * identical for a real workspace, a nonexistent one and somebody else's, and the tenancy contract
 * (404 for all three) is untouched.
 *
 * <p>In-memory and per-instance on purpose (the {@code RateLimitService} precedent): this is a
 * bound on abuse, not an invariant, and a multi-instance deployment gets one budget per instance.
 * The refusal is a <strong>metric, not a log line</strong> — a client that keeps polling would
 * otherwise be an authenticated log-flooding vector (disk on DC, ingest cost on Cloud);
 * {@link ProductMetrics#rateLimitHit} has bounded cardinality and is alertable, and the per-request
 * detail stays at DEBUG naming only ids.
 */
@Slf4j
public abstract class PerPrincipalMinuteBudget {

    private final ProductMetrics metrics;

    /** key: user id → requests against this surface in the current epoch-minute. */
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    protected PerPrincipalMinuteBudget(ProductMetrics metrics) {
        this.metrics = metrics;
    }

    /** Whether limiting is on at all — {@code app.rate-limit.enabled}, the app-wide master switch. */
    protected abstract boolean enabled();

    /** How many requests one principal may make against this surface per minute. */
    protected abstract int limit();

    /** The metric tag this surface's refusals are counted under. */
    protected abstract RateLimitKind kind();

    /** What is being counted, as it appears in the 429 detail — e.g. {@code "report requests"}. */
    protected abstract String surface();

    /**
     * Spend one unit of {@code userId}'s budget, or refuse with a 429 naming how long to wait.
     *
     * <p>A {@code null} user cannot happen on these paths (the security filter chain has already
     * rejected anonymous {@code /api/**}) and is treated as unthrottled rather than as a shared
     * key, which would let one anonymous request exhaust everybody's budget.
     */
    public void require(UUID userId) {
        if (!enabled() || userId == null) {
            return;
        }
        long nowMinute = Instant.now().getEpochSecond() / 60;
        var window = windows.compute(userId, (id, existing) ->
                (existing == null || existing.epochMinute != nowMinute) ? new Window(nowMinute) : existing);
        if (window.count.incrementAndGet() <= limit()) {
            return;
        }
        long retryAfter = Math.max((nowMinute + 1) * 60 - Instant.now().getEpochSecond(), 1);
        metrics.rateLimitHit(kind());
        log.debug("{} throttled for user {} — {}s remaining", surface(), userId, retryAfter);
        throw new RateLimitedException(
                "Too many " + surface() + " — retry in " + retryAfter + "s", retryAfter);
    }

    /**
     * The map is keyed by user id, so it is bounded by the number of people who have used this
     * surface on this instance — small, but not self-limiting in a long-running process (the
     * {@code RateLimitService} precedent). Subclasses schedule the sweep.
     */
    protected void evictStaleEntries() {
        long nowMinute = Instant.now().getEpochSecond() / 60;
        windows.values().removeIf(w -> w.epochMinute < nowMinute - 1);
    }

    private static final class Window {
        final long epochMinute;
        final AtomicInteger count = new AtomicInteger();

        Window(long epochMinute) {
            this.epochMinute = epochMinute;
        }
    }
}
