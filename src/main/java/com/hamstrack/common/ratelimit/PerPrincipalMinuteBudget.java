package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><strong>Not every budget built on this class is a path binding, and the seal for one that is
 * not lives elsewhere</strong> (HD-191). The reports, search and write budgets are spent by
 * {@link PrincipalThrottleInterceptor} against a registered pattern, and
 * {@code ThrottleCoverageTest} seals that set. {@code UploadByteBudget} cannot be: its cost is
 * {@code MultipartFile.getSize()}, and an interceptor has neither the parsed part nor a reason to
 * look at one — exactly as the invitation ceilings cannot be, because theirs is keyed on the
 * recipient. Its seal is {@code AttachmentDoorsTest}, on the axis of {@code FileStorage.store}
 * call sites rather than of paths.
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

    /**
     * How many UNITS of this surface one principal may spend per minute.
     *
     * <p><strong>{@code long}, and a "unit" is not always a request</strong> (HD-191). Three
     * budgets denominate in requests and one — {@code UploadByteBudget} — denominates in BYTES,
     * where a per-minute allowance runs to hundreds of millions and an {@code int} is a ceiling
     * of about 2 GB that nothing in the configuration would warn anybody about. One mechanism,
     * two denominations: the window arithmetic, the eviction sweep, the {@code Retry-After}
     * computation and the metric-not-a-log-line refusal are the same in both, which is this
     * class's own argument for not copying the loop.
     */
    protected abstract long limit();

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
        require(userId, 1);
    }

    /**
     * Spend {@code cost} units of {@code userId}'s budget, or refuse with a 429 naming how long
     * to wait.
     *
     * <p>The costed form exists for the upload-byte budget, whose unit is a byte and whose cost
     * is {@code MultipartFile.getSize()} — the PARSED size, never a client-declared
     * {@code Content-Length}. A budget that trusted the header would be a budget the client
     * sets.
     *
     * <p><strong>A single spend can exceed the whole budget, and that is the intended
     * behaviour.</strong> The comparison is against the running total, so an upload larger than
     * the per-minute allowance is refused outright rather than being admitted "because the
     * window was empty" — and the configuration that would make every legal file do that is
     * refused at startup instead ({@code StorageQuotaConsistency}).
     */
    public void require(UUID userId, long cost) {
        if (!enabled() || userId == null) {
            return;
        }
        long nowMinute = Instant.now().getEpochSecond() / 60;
        var window = windows.compute(userId, (id, existing) ->
                (existing == null || existing.epochMinute != nowMinute) ? new Window(nowMinute) : existing);
        if (window.count.addAndGet(cost) <= limit()) {
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
        final AtomicLong count = new AtomicLong();

        Window(long epochMinute) {
            this.epochMinute = epochMinute;
        }
    }
}
