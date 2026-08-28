package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.InviteProperties;
import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * <strong>The per-sender volume budget — the quota control</strong> (HD-190 section 6.1).
 *
 * <p>The ticket asked for a per-minute ceiling "like the other limiters". That does not protect what
 * is at risk: the provider quota is <em>3000 messages a month</em>, and any per-minute ceiling loose
 * enough to let a team lead work through twenty addresses is 28 800/day — a monthly quota spent
 * before lunch. A rate window and a quota are different units, so there are two windows here and no
 * per-minute one, and <strong>they are independent rather than one being a multiple of the
 * other</strong>: an account that spends its hourly allowance five times over is done for the day,
 * and the daily refusal does not lift when the hour rolls.
 *
 * <p>Constructed directly rather than autowired, because the properties are what is under test:
 * each case needs a different pair of ceilings, and the shipped pair (20/100) cannot show that the
 * two windows are separate without an hour of wall clock.
 *
 * <p>Two contexts this class deliberately does <em>not</em> assert. Whether the budget is spent
 * before the recipient ceilings — that is {@link InviteThrottle}'s ordering property, argued there:
 * a sender-budget refusal must never consume a <em>victim's</em> daily allowance. And whether a
 * refusal reaches the caller as a 429 with {@code Retry-After} — that is
 * {@code InviteThrottleBehaviourTest}, on the real endpoint.
 */
class InviteSenderVolumeBudgetTest {

    /** Long enough that the day never binds while the hour is under test, and vice versa. */
    private static final int UNBOUNDED = 10_000;

    /**
     * The hourly window bounds a burst. Its {@code Retry-After} names the roll of the fixed window,
     * so it is at most an hour and never a full window restated — a ceiling that answered "try
     * again in one hour" every time would over-state the wait on every refusal but the first, and a
     * refusal that lies about its own remedy is how a retryable 429 gets read as a wall.
     */
    @Test
    void theHourlyWindowRefusesAtItsCeilingAndNamesTheRoll() {
        var metrics = mock(ProductMetrics.class);
        var budget = budget(3, UNBOUNDED, metrics);
        var sender = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            int spend = i + 1;
            assertThatCode(() -> budget.require(sender))
                    .as("send %d of 3 is inside the allowance", spend)
                    .doesNotThrowAnyException();
        }

        var refusal = refuse(budget, sender, 3600);

        assertThat(refusal.getMessage())
                .as("the reader is an admin mid-onboarding: name both allowances, name the wait, "
                    + "and — the sentence that matters in practice — stop them re-sending the "
                    + "invitations that already went out")
                .contains("3 invitations an hour")
                .contains(UNBOUNDED + " a day")
                .contains("already sent are unaffected");
        assertThat(refusal.getMessage())
                .as("a refusal may only prescribe an action its reader can perform, and 'ask an "
                    + "administrator to raise the limit' is not one on Cloud, where there is no "
                    + "administrator to ask, nor on DC, where the reader may not be the operator")
                .doesNotContain("administrator");

        verify(metrics, times(1)).rateLimitHit(RateLimitKind.INVITE_SENDER_VOLUME);
    }

    /**
     * <strong>The two windows are independent, and this is the property worth testing.</strong> With
     * a generous hour and a tight day the refusal is the DAILY one — a bound a per-minute or
     * per-hour limiter cannot express, and the one that actually protects a monthly quota. Its
     * {@code Retry-After} runs to the roll of the day, which can be hours; that is honest, and the
     * SPA renders it as a duration rather than as a countdown.
     */
    @Test
    void theDailyWindowBindsIndependentlyOfTheHourly() {
        var budget = budget(UNBOUNDED, 2, mock(ProductMetrics.class));
        var sender = UUID.randomUUID();

        budget.require(sender);
        budget.require(sender);

        // The DAILY window's roll, not the hour's. If the daily ceiling were merely a multiple of
        // the hourly it could not be reached with the hourly one wide open, and the quota control
        // — the only one of the three that protects a monthly provider allowance — would not exist.
        refuse(budget, sender, 86_400);
    }

    /**
     * <strong>A refused request must not spend the OTHER window's allowance.</strong> The checks
     * happen before the increments, inside one {@code compute}, and the reason is concrete: if the
     * increments came first, an admin bouncing off the hourly ceiling would burn their whole day
     * retrying it — the ceiling meant to make them wait an hour would instead end their day, which
     * is precisely the "first thing a large new customer meets is a refusal" failure the numbers are
     * sized to avoid.
     *
     * <p>Read off the internal counter, because it is not observable any other way inside one hour:
     * the day's remaining allowance can only be shown by rolling the hour.
     */
    @Test
    void aRefusedRequestDoesNotSpendTheOtherWindow() throws Exception {
        var budget = budget(2, 50, mock(ProductMetrics.class));
        var sender = UUID.randomUUID();

        budget.require(sender);
        budget.require(sender);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> budget.require(sender))
                    .isInstanceOf(RateLimitedException.class);
        }

        assertThat(dayCountOf(budget, sender))
                .as("ten refused retries must leave the DAY where the two accepted sends left it. "
                    + "Incrementing before checking would have this at 12, and an admin who "
                    + "hammered a one-hour refusal would find themselves locked out until midnight")
                .isEqualTo(2);
    }

    /**
     * <strong>The master switch turns this off</strong> (acceptance criterion 6). It is a rate limit
     * and {@code app.rate-limit.enabled} is the only way to turn it off — none of the individual
     * properties accepts an "unlimited" value, because {@code 0} is out of range on all of them.
     *
     * <p>Nothing is counted either, and nothing is metered: unlike the recipient ceilings, whose
     * bookkeeping keeps running because it is the operator's forensic trail, there is nothing here
     * worth keeping — the state is a per-node map that a deploy resets anyway.
     */
    @Test
    void theMasterSwitchTurnsItOff() {
        var metrics = mock(ProductMetrics.class);
        var budget = new InviteSenderVolumeBudget(properties(1, 1),
                rateLimitProperties(false), metrics);
        var sender = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            budget.require(sender);
        }

        verifyNoInteractions(metrics);
    }

    /**
     * A {@code null} principal cannot happen on this path — {@code /api/**} is
     * {@code authenticated()} — and is treated as unthrottled rather than as a shared key.
     *
     * <p>The distinction is the {@link PerPrincipalMinuteBudget} reasoning and it is not
     * hypothetical: a shared key would let one request exhaust <em>everybody's</em> budget, turning
     * an anonymous edge case into an instance-wide denial of invitations. Unthrottled is the safe
     * failure here precisely because it is unreachable; a shared bucket is not.
     */
    @Test
    void anAnonymousPrincipalIsUnthrottledAndNotAsharedBucket() {
        var metrics = mock(ProductMetrics.class);
        var budget = budget(1, 1, metrics);

        for (int i = 0; i < 5; i++) {
            budget.require(null);
        }
        verify(metrics, never()).rateLimitHit(RateLimitKind.INVITE_SENDER_VOLUME);

        assertThatCode(() -> budget.require(UUID.randomUUID()))
                .as("and it must not have spent anybody else's allowance on the way")
                .doesNotThrowAnyException();
    }

    /** One sender's budget is their own; exhausting it must not refuse anybody else. */
    @Test
    void theBudgetIsPerPrincipal() {
        var budget = budget(1, UNBOUNDED, mock(ProductMetrics.class));
        var first = UUID.randomUUID();

        budget.require(first);
        assertThatThrownBy(() -> budget.require(first)).isInstanceOf(RateLimitedException.class);

        assertThatCode(() -> budget.require(UUID.randomUUID()))
                .as("keyed on the principal, so one abusive account cannot deny invitations to "
                    + "every other admin on the instance — the failure mode an IP key has")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ fixture

    private static InviteSenderVolumeBudget budget(int perHour, int perDay, ProductMetrics metrics) {
        return new InviteSenderVolumeBudget(properties(perHour, perDay),
                rateLimitProperties(true), metrics);
    }

    /**
     * The two ceilings under test; the other three are the shipped defaults, since this class does
     * not read them.
     */
    private static InviteProperties properties(int perHour, int perDay) {
        return new InviteProperties(perHour, perDay, 60, 5, 7);
    }

    private static RateLimitProperties rateLimitProperties(boolean enabled) {
        return new RateLimitProperties(enabled, 15, 5, 30L, 900L, false);
    }

    /**
     * Spend one more than the allowance and assert the refusal's {@code Retry-After} names the roll
     * of a fixed window of {@code width} seconds — <em>which window</em> being the whole point of
     * this file, since the two are independent rather than multiples of each other.
     *
     * <p>Bracketed rather than compared to a single computed value: both the budget and this method
     * read the wall clock, and a second ticking between them would make an exact comparison fail for
     * a reason that has nothing to do with the property. The bracket is one second wide, which is
     * still far narrower than the 24× separating the two windows.
     */
    private static RateLimitedException refuse(InviteSenderVolumeBudget budget, UUID sender,
                                               long width) {
        long upper = secondsToNextBoundary(width);
        RateLimitedException refusal = null;
        try {
            budget.require(sender);
        } catch (RateLimitedException e) {
            refusal = e;
        }
        long lower = secondsToNextBoundary(width);

        assertThat(refusal)
                .as("the request past the allowance must be refused")
                .isNotNull();
        assertThat(refusal.getRetryAfterSeconds())
                .as("the wait runs to the roll of the %ds window, and is never that whole window "
                    + "restated — a ceiling that answered 'one full window' would over-state the "
                    + "wait on every refusal but the first", width)
                .isPositive()
                .isBetween(Math.min(lower, upper), Math.max(lower, upper));
        return refusal;
    }

    /** Seconds from now to the next roll of a fixed window of {@code width} seconds. */
    private static long secondsToNextBoundary(long width) {
        long now = Instant.now().getEpochSecond();
        return Math.max((now / width + 1) * width - now, 1);
    }

    /**
     * The day counter for one sender, out of the private per-principal map.
     *
     * <p>Reflection because there is no other way to see it inside one hour, and the property it
     * shows — that a refusal does not spend the other window — is the whole reason the class reads
     * and increments inside a single {@code compute} rather than incrementing first.
     */
    @SuppressWarnings("unchecked")
    private static int dayCountOf(InviteSenderVolumeBudget budget, UUID sender) throws Exception {
        var windowsField = InviteSenderVolumeBudget.class.getDeclaredField("windows");
        windowsField.setAccessible(true);
        var state = ((Map<UUID, ?>) windowsField.get(budget)).get(sender);
        assertThat(state).as("no window state for a sender that has spent its allowance").isNotNull();
        var dayCount = state.getClass().getDeclaredField("dayCount");
        dayCount.setAccessible(true);
        return dayCount.getInt(state);
    }
}
