package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.InviteProperties;
import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <strong>How much invitation mail one principal may cause — the quota control</strong>
 * (HD-190 §6.1).
 *
 * <h2>Two windows, and no per-minute one</h2>
 * The ticket asked for a per-minute ceiling "like the other limiters". That does not protect what
 * is actually at risk: the provider quota is <em>3000 messages a month</em>, and any per-minute
 * ceiling loose enough to let a team lead work through twenty addresses is 28 800/day — a monthly
 * quota spent before lunch. A rate window and a quota are different units. So the hourly window
 * bounds a burst and the <strong>daily window is the quota control</strong>: 100/day against
 * 3000/month means one abusive account consumes at most ~1/30 of the month per day, which is a
 * condition an operator has a month to notice rather than an afternoon.
 *
 * <p>The two windows are independent rather than one being a multiple of the other, which is the
 * property worth testing: an account that spends its hourly allowance five times over is done for
 * the day, and the daily refusal does not lift when the hour rolls.
 *
 * <h2>Not {@link PerPrincipalMinuteBudget}</h2>
 * That class is a <em>minute</em> window by construction — the epoch-minute is its key and its
 * {@code Retry-After} arithmetic — and it refuses on the first ceiling it has. Generalising it to N
 * windows to share ~30 lines would put a second shape into the mechanism every other budget in the
 * app depends on. The reasoning it carries is what is shared, and it is restated below rather than
 * inherited.
 *
 * <h2>In memory, and that is the deliberate half of ADR-0015</h2>
 * What gets persisted is what is keyed on the <em>victim</em>: a cooldown a deploy resets is a
 * cooldown an attacker waits out. This is keyed on the <em>sender</em> and it protects a spend, not
 * a person, so it lives in a map like the app's other five limiters and degrades the usual way —
 * N replicas allow up to N × the ceiling, and a restart re-arms the window. A bound on abuse, not
 * an invariant.
 *
 * <p>Exact per node, though: the counters are read and incremented inside one
 * {@link ConcurrentHashMap#compute} so concurrent requests cannot both pass, and — the reason that
 * matters — <strong>a refused request does not spend the other window's allowance</strong>. If the
 * increments happened before the checks, an admin bouncing off the hourly ceiling would burn their
 * whole day retrying it.
 *
 * <p>The refusal is a <strong>metric, not a log line</strong>, per the standing rule: a client that
 * keeps retrying must not be a log-flooding vector. The per-request detail stays at DEBUG and names
 * only ids.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteSenderVolumeBudget {

    private static final long HOUR_SECONDS = 3600L;
    private static final long DAY_SECONDS = 86_400L;

    private final InviteProperties inviteProperties;
    private final RateLimitProperties rateLimitProperties;
    private final ProductMetrics metrics;

    /** key: sender user id → their current hour and day windows. */
    private final Map<UUID, Windows> windows = new ConcurrentHashMap<>();

    /**
     * Spend one invitation from {@code senderUserId}'s allowance, or refuse with a 429.
     *
     * <p>A {@code null} principal cannot happen on this path ({@code /api/**} is
     * {@code authenticated()}) and is treated as unthrottled rather than as a shared key, which
     * would let one request exhaust everybody's budget — the {@link PerPrincipalMinuteBudget}
     * reasoning.
     */
    public void require(UUID senderUserId) {
        if (!rateLimitProperties.enabled() || senderUserId == null) {
            return;
        }
        long nowSeconds = Instant.now().getEpochSecond();
        long epochHour = nowSeconds / HOUR_SECONDS;
        long epochDay = nowSeconds / DAY_SECONDS;

        var refusal = new AtomicReference<Long>();
        windows.compute(senderUserId, (id, existing) -> {
            var state = existing == null ? new Windows() : existing;
            state.roll(epochHour, epochDay);
            if (state.hourCount >= inviteProperties.maxPerSenderPerHour()) {
                refusal.set(Math.max((epochHour + 1) * HOUR_SECONDS - nowSeconds, 1));
                return state;
            }
            if (state.dayCount >= inviteProperties.maxPerSenderPerDay()) {
                refusal.set(Math.max((epochDay + 1) * DAY_SECONDS - nowSeconds, 1));
                return state;
            }
            state.hourCount++;
            state.dayCount++;
            return state;
        });

        var retryAfter = refusal.get();
        if (retryAfter == null) {
            return;
        }
        metrics.rateLimitHit(RateLimitKind.INVITE_SENDER_VOLUME);
        log.debug("invite volume budget spent for user {} — {}s remaining", senderUserId, retryAfter);
        // The reader is an admin in the middle of onboarding somebody. The only thing they can do
        // is wait and continue, so the message says exactly that and names the wait. It does NOT say
        // "ask an administrator to raise the limit": on Cloud the reader has no administrator to
        // ask, and on DC the reader may well not be the operator — an unperformable prescription is
        // a mistake this project has shipped three times. The last sentence is the one that matters
        // in practice: it stops them re-sending the invitations that already went out.
        throw new RateLimitedException(
                "Invitation limit reached — you can send up to " + inviteProperties.maxPerSenderPerHour()
                + " invitations an hour and " + inviteProperties.maxPerSenderPerDay()
                + " a day. Try again in " + RetryWait.describe(retryAfter)
                + ". Invitations you have already sent are unaffected.",
                retryAfter);
    }

    /**
     * The map is keyed by user id, so it is bounded by the number of people who have invited anybody
     * on this node — small, but not self-limiting in a long-running process
     * ({@code RateLimitService}'s reasoning). An entry is dead once its DAY window has rolled, which
     * is the longer of the two.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    void evictStaleEntries() {
        long epochDay = Instant.now().getEpochSecond() / DAY_SECONDS;
        windows.values().removeIf(w -> w.epochDay < epochDay);
    }

    /**
     * Mutable, and safe because every read and write happens inside the {@code compute} above, which
     * holds the bin lock for that key.
     */
    private static final class Windows {
        private long epochHour = -1;
        private long epochDay = -1;
        private int hourCount;
        private int dayCount;

        void roll(long nowHour, long nowDay) {
            if (epochHour != nowHour) {
                epochHour = nowHour;
                hourCount = 0;
            }
            if (epochDay != nowDay) {
                epochDay = nowDay;
                dayCount = 0;
            }
        }
    }
}
