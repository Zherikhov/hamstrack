package com.hamstrack.common.security;

import com.hamstrack.common.config.CspProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <strong>The CSP report sink's own volume bound: per-IP, and per instance</strong> (HD-264).
 *
 * <h2>Its own pot, and in particular NOT the auth pot</h2>
 * The obvious economy — charge these to {@code app.rate-limit.auth-ip-requests-per-minute}, which
 * is already a per-IP minute window — is the one mistake this class exists to not make. That budget
 * is shared across {@code /login}, {@code /register}, {@code /forgot-password} and their siblings,
 * it is keyed per IP, and shared IPs are ordinary: a browser (anyone's browser, on a page we serve,
 * behaving correctly) that starts violating the policy would spend somebody's login budget and lock
 * them out of the door they need most. That is HD-233's refund defect arriving from a new direction
 * — a refusal the instance provoked, charged to the caller — and it is cheaper to refuse the shape
 * than to build a second refund.
 *
 * <p>The positive form of the rule is the project's own: <em>a throttle is earned by the work a
 * handler does</em>. This handler's work is one parse, one log line and one counter increment — no
 * repository call, no transaction, no connection, no mail — so what it earns is a bound on
 * <strong>volume</strong>, which is nobody else's.
 *
 * <h2>Deliberately outside {@code app.rate-limit.enabled}</h2>
 * The master switch turns off every limiter that has an off switch, and this one is not among them.
 * The reason is the door rather than the budget: this is the only bound on an endpoint that
 * requires no account at all, so an operator flipping the master switch would silently convert the
 * sink into an unbounded public log-fill primitive. <strong>The off switch for this door is the
 * door</strong> — {@code CSP_REPORT_SINK_ENABLED=false} removes the endpoint entirely — which is a
 * better answer than an unbounded one and is why no third state is offered. Both artefacts that
 * enumerate what the master switch does <em>not</em> reach name it.
 *
 * <h2>Two windows, and the per-IP one is charged first</h2>
 * A request refused by the instance ceiling has already spent its per-IP token, and there is no
 * refund. That is deliberate: nothing else spends this pot, so an over-charge costs its owner only
 * more of the same refusal, and a refund path would be machinery guarding an anonymous sender's
 * right to fill a log faster. The order is chosen so the sharper, per-sender bound is the one that
 * usually answers, which is also the one whose {@code Retry-After} is honest for the sender that
 * reads it.
 */
@RequiredArgsConstructor
public class CspReportBudget {

    private final CspProperties properties;

    /** key: client IP → reports in the current epoch-minute. */
    private final Map<String, Window> perIp = new ConcurrentHashMap<>();

    /** The whole instance's window, replaced wholesale when the minute rolls. */
    private final AtomicReference<Window> instance = new AtomicReference<>(new Window(-1));

    /**
     * Spends one report's worth of both budgets.
     *
     * @return {@code 0} when the report may be processed, otherwise the number of seconds until
     *         the window that refused it rolls — i.e. a {@code Retry-After} value, never below 1.
     */
    public long spend(String clientIp) {
        long nowSecond = Instant.now().getEpochSecond();
        long nowMinute = nowSecond / 60;

        var ipWindow = perIp.compute(clientIp, (ip, w) ->
                (w == null || w.epochMinute != nowMinute) ? new Window(nowMinute) : w);
        if (ipWindow.count.incrementAndGet() > properties.reportsPerMinutePerIp()) {
            return retryAfterSeconds(nowMinute, nowSecond);
        }

        var instanceWindow = currentInstanceWindow(nowMinute);
        if (instanceWindow.count.incrementAndGet() > properties.reportsPerMinute()) {
            return retryAfterSeconds(nowMinute, nowSecond);
        }
        return 0;
    }

    private Window currentInstanceWindow(long nowMinute) {
        var window = instance.get();
        while (window.epochMinute != nowMinute) {
            var fresh = new Window(nowMinute);
            if (instance.compareAndSet(window, fresh)) {
                return fresh;
            }
            window = instance.get();
        }
        return window;
    }

    private static long retryAfterSeconds(long nowMinute, long nowSecond) {
        return Math.max((nowMinute + 1) * 60 - nowSecond, 1);
    }

    /**
     * The per-IP map is keyed by attacker-controlled input, so it would otherwise grow without
     * bound. Same sweep, same cadence and same reasoning as {@code RateLimitService}'s.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void evictStaleEntries() {
        long nowMinute = Instant.now().getEpochSecond() / 60;
        perIp.values().removeIf(window -> window.epochMinute < nowMinute - 1);
    }

    private static final class Window {
        final long epochMinute;
        final AtomicInteger count = new AtomicInteger();

        Window(long epochMinute) {
            this.epochMinute = epochMinute;
        }
    }
}
