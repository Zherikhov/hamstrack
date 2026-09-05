package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory counters behind auth abuse protection (see {@link RateLimitProperties}).
 * Two independent mechanisms:
 *
 * <ul>
 *   <li><b>Per-IP fixed window</b> — a shared per-minute budget across the
 *       sensitive auth endpoints, enforced by {@code AuthRateLimitFilter}.</li>
 *   <li><b>Per-account login backoff</b> — after N consecutive failures the
 *       account is blocked for an exponentially growing delay, enforced in
 *       {@code AuthService.login}. Keyed by the submitted email whether or not
 *       the account exists, so the limiter itself cannot be used to probe
 *       which emails are registered.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitProperties properties;
    private final ProductMetrics metrics;

    // key: client IP → requests in the current epoch-minute
    private final Map<String, IpWindow> ipWindows = new ConcurrentHashMap<>();
    // key: lowercased email → consecutive login failures
    private final Map<String, FailureState> loginFailures = new ConcurrentHashMap<>();

    /**
     * What {@link #checkAuthRequestAllowed} returns when the limiter is off: there is no window, so
     * there is nothing to give back. A sentinel rather than an {@code Optional} because it is read
     * once per auth request and lives in a local for the length of one {@code doFilter}.
     */
    public static final long NOT_CHARGED = Long.MIN_VALUE;

    /**
     * Per-IP budget check for an auth request; throws 429 when exhausted.
     *
     * <p>Returns <strong>the window the token was charged to</strong>, which is the only window a
     * refund may be applied to — see {@link #refundAuthRequest}. {@link #NOT_CHARGED} when the
     * limiter is off and nothing was spent.
     */
    public long checkAuthRequestAllowed(String clientIp) {
        if (!properties.enabled()) return NOT_CHARGED;
        long nowMinute = Instant.now().getEpochSecond() / 60;
        var window = ipWindows.compute(clientIp, (ip, w) ->
                (w == null || w.epochMinute != nowMinute) ? new IpWindow(nowMinute) : w);
        if (window.count.incrementAndGet() > properties.authIpRequestsPerMinute()) {
            long retryAfter = (nowMinute + 1) * 60 - Instant.now().getEpochSecond();
            metrics.rateLimitHit(RateLimitKind.IP_WINDOW);
            throw new RateLimitedException(Math.max(retryAfter, 1));
        }
        return nowMinute;
    }

    /**
     * <strong>Gives back a token the instance spent on itself, and no more of them per window than
     * that window issues</strong> (HD-233).
     *
     * <p>The budget is consumed <em>before</em> {@code chain.doFilter}, so a request the instance
     * then refuses costs the caller exactly what a real attempt costs. That is right for every
     * refusal the caller earned and wrong for one the instance issued about its own state: a client
     * obeying the {@code Retry-After: 1} that {@code 503 DATABASE_BUSY} prescribes spends one token
     * per second, so it empties {@code app.rate-limit.auth-ip-requests-per-minute} in as many
     * seconds as the budget has tokens and is then locked out with a 429 for the rest of the
     * minute — on
     * {@code /api/auth/login}, during an incident, which is the moment it needs that door most. A
     * refusal may only prescribe an action its reader can perform.
     *
     * <p><strong>Capped, because an uncapped refund is this budget switching itself off in the one
     * condition an attacker can produce on purpose.</strong> The token did not buy nothing — it
     * bought a park. The request reaches a repository call, enters
     * {@code HikariPool.getConnection()} and holds a Tomcat worker until the pool gives up: the
     * whole acquisition bound, and up to twice it when the borrow ends on a dead-connection probe
     * ({@code HikariPool} re-derives the remaining budget only <em>after</em> the probe, so the park
     * overruns the bound by as much as {@code validation-timeout} — held equal to it, which is what
     * makes that overshoot a figure this product knows). That is ~3 to ~6 thread-seconds at the
     * shipped 3000 ms, more worker time than a successful login costs, and it makes the cap below
     * dearer to give up rather than cheaper. So an unbounded refund leaves one IP with <em>no</em> per-IP
     * bound at all across the six auth endpoints for as long as the pool stays starved — and the
     * flood is itself what keeps it starved, because threads that never reach the front of the
     * queue cannot drain it. A window therefore gives back at most as many tokens as it issues,
     * which is <strong>2x the budget per minute and not an incident-long exemption</strong>: at one
     * request per second the first {@code B} are refunded, the next {@code B} spend the window, and
     * request {@code 2B + 1} is a 429 — at the shipped 15, that is roughly 31 seconds into the
     * minute, after which the caller waits out the rest of it and the next window starts clean with
     * a fresh cap. So what a compliant client actually gets is <em>double</em> the door it would
     * otherwise have during an incident, renewed every minute; a flood regains a hard ceiling
     * rather than an open lane. Stated that way on purpose: this door is written for the caller who
     * obeys {@code Retry-After}, and a claim that is false for exactly the reader it is written for
     * is worse than no claim.
     *
     * <p><strong>And only into the window the token was charged to</strong>, never into whichever
     * one happens to be current when the request finishes. Those differ routinely here: a request
     * that enters at {@code M:59.x}, parks out the acquisition bound and answers 503 in
     * {@code M+1} would otherwise decrement a window it never paid into, driving that one toward
     * its zero floor and handing out tokens nobody spent. Never below zero, and the caller cannot
     * choose to be refused.
     */
    public void refundAuthRequest(String clientIp, long chargedToMinute) {
        if (!properties.enabled() || chargedToMinute == NOT_CHARGED) return;
        ipWindows.computeIfPresent(clientIp, (ip, window) -> {
            // Inside computeIfPresent, whose mapping function ConcurrentHashMap runs under the bin
            // lock — which is what makes the cap's read-then-increment atomic for this key.
            if (window.epochMinute == chargedToMinute
                && window.refunds < properties.authIpRequestsPerMinute()) {
                window.refunds++;
                window.count.updateAndGet(spent -> spent > 0 ? spent - 1 : 0);
            }
            return window;
        });
    }

    /** Backoff check before verifying credentials; throws 429 while blocked. */
    public void checkLoginAllowed(String email) {
        if (!properties.enabled()) return;
        var state = loginFailures.get(key(email));
        if (state == null) return;
        long blockedUntil = state.blockedUntilEpochMs(properties);
        long now = System.currentTimeMillis();
        if (now < blockedUntil) {
            metrics.rateLimitHit(RateLimitKind.LOGIN_BACKOFF);
            throw new RateLimitedException(Math.max((blockedUntil - now) / 1000, 1));
        }
    }

    public void recordLoginFailure(String email) {
        if (!properties.enabled()) return;
        loginFailures.compute(key(email), (k, s) -> {
            var state = s != null ? s : new FailureState();
            state.failures++;
            state.lastFailureEpochMs = System.currentTimeMillis();
            return state;
        });
    }

    public void resetLoginFailures(String email) {
        loginFailures.remove(key(email));
    }

    private String key(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    // Both maps are keyed by attacker-controlled input — without eviction they
    // would grow unboundedly.
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void evictStaleEntries() {
        long nowMinute = Instant.now().getEpochSecond() / 60;
        ipWindows.values().removeIf(w -> w.epochMinute < nowMinute - 1);
        long cutoff = System.currentTimeMillis() - 2 * properties.loginBackoffMaxSeconds() * 1000;
        loginFailures.values().removeIf(s -> s.lastFailureEpochMs < cutoff);
    }

    private static final class IpWindow {
        final long epochMinute;
        final AtomicInteger count = new AtomicInteger();
        /**
         * How many tokens this window has already given back. Touched only inside
         * {@code ipWindows.computeIfPresent}, so the bin lock already serialises it for this key,
         * and an atomic here would only make a check-then-increment look safer than the plain
         * field already is.
         */
        int refunds;

        IpWindow(long epochMinute) {
            this.epochMinute = epochMinute;
        }
    }

    private static final class FailureState {
        int failures;
        long lastFailureEpochMs;

        long blockedUntilEpochMs(RateLimitProperties props) {
            if (failures < props.loginFailureThreshold()) return 0;
            // threshold-th failure → base delay, doubling per further failure
            long delaySec = props.loginBackoffBaseSeconds()
                    << Math.min(failures - props.loginFailureThreshold(), 30);
            return lastFailureEpochMs + Math.min(delaySec, props.loginBackoffMaxSeconds()) * 1000;
        }
    }
}
