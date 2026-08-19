package com.hamstrack.report.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.ratelimit.RateLimitedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <h2>Per principal, not per project</h2>
 * Two reasons, and the second is the load-bearing one.
 * <ol>
 *   <li>It is the correct unit: the cost is paid on behalf of whoever asks, and a per-project
 *       key would let one colleague's open dashboard tab throttle everybody else on the
 *       project — a worse outage than the one being prevented.</li>
 *   <li>It keeps the refusal free of tenancy information. The limiter runs in an interceptor,
 *       i.e. <em>before</em> the controller resolves the project, so a per-project key would
 *       answer differently depending on whether a project exists. Keyed on the caller, the
 *       429 is identical for a real project, a nonexistent one and somebody else's, and the
 *       tenancy contract (404 for all three) is untouched.</li>
 * </ol>
 *
 * <h2>Shape</h2>
 * A per-minute fixed window, the same mechanism {@code RateLimitService}'s per-IP budget
 * uses, and refused the same way the per-project rank-rebalance cooldown is: a
 * {@link RateLimitedException}, so the shared handler adds {@code Retry-After} and a client
 * can back off instead of hammering. In-memory and per-instance on purpose (the
 * {@code RateLimitService} precedent): this is a bound on abuse, not an invariant, and a
 * multi-instance deployment simply gets one budget per instance.
 *
 * <p>The refusal is a <strong>metric, not a log line</strong> — same reasoning as
 * {@code IssueRankService}: a client that keeps polling would otherwise be an authenticated
 * log-flooding vector (disk on DC, ingest cost on Cloud). {@link ProductMetrics#rateLimitHit}
 * has bounded cardinality and is alertable; the per-request detail stays at DEBUG and names
 * only ids.
 *
 * <p>{@code app.rate-limit.enabled} is honoured as the master switch, because it already is
 * one for every other limiter in the app and because a test suite that turns limiting off
 * expects it off everywhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportRateLimiter {

    private final ReportProperties reportProperties;
    private final RateLimitProperties rateLimitProperties;
    private final ProductMetrics metrics;

    /** key: user id → report requests in the current epoch-minute. */
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    /**
     * Spend one unit of {@code userId}'s report budget, or refuse with a 429 naming how long
     * to wait. A {@code null} user cannot happen on this path (the security filter chain has
     * already rejected anonymous {@code /api/**}) and is treated as unthrottled rather than
     * as a shared key, which would let one anonymous request exhaust everybody's budget.
     */
    public void require(UUID userId) {
        if (!rateLimitProperties.enabled() || userId == null) {
            return;
        }
        long nowMinute = Instant.now().getEpochSecond() / 60;
        var window = windows.compute(userId, (id, existing) ->
                (existing == null || existing.epochMinute != nowMinute) ? new Window(nowMinute) : existing);
        if (window.count.incrementAndGet() <= reportProperties.requestsPerMinute()) {
            return;
        }
        long retryAfter = Math.max((nowMinute + 1) * 60 - Instant.now().getEpochSecond(), 1);
        metrics.rateLimitHit(RateLimitKind.REPORT_REQUESTS);
        log.debug("Report requests throttled for user {} — {}s remaining", userId, retryAfter);
        throw new RateLimitedException(
                "Too many report requests — retry in " + retryAfter + "s", retryAfter);
    }

    /**
     * The map is keyed by user id, so it is bounded by the number of people who have asked
     * for a report on this instance — small, but not self-limiting in a long-running process
     * (the {@code RateLimitService} precedent).
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void evictStaleEntries() {
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
