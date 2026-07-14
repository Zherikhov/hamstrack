package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

    // key: client IP → requests in the current epoch-minute
    private final Map<String, IpWindow> ipWindows = new ConcurrentHashMap<>();
    // key: lowercased email → consecutive login failures
    private final Map<String, FailureState> loginFailures = new ConcurrentHashMap<>();

    /** Per-IP budget check for an auth request; throws 429 when exhausted. */
    public void checkAuthRequestAllowed(String clientIp) {
        if (!properties.enabled()) return;
        long nowMinute = Instant.now().getEpochSecond() / 60;
        var window = ipWindows.compute(clientIp, (ip, w) ->
                (w == null || w.epochMinute != nowMinute) ? new IpWindow(nowMinute) : w);
        if (window.count.incrementAndGet() > properties.authIpRequestsPerMinute()) {
            long retryAfter = (nowMinute + 1) * 60 - Instant.now().getEpochSecond();
            throw new RateLimitedException(Math.max(retryAfter, 1));
        }
    }

    /** Backoff check before verifying credentials; throws 429 while blocked. */
    public void checkLoginAllowed(String email) {
        if (!properties.enabled()) return;
        var state = loginFailures.get(key(email));
        if (state == null) return;
        long blockedUntil = state.blockedUntilEpochMs(properties);
        long now = System.currentTimeMillis();
        if (now < blockedUntil) {
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
        return email.toLowerCase();
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
