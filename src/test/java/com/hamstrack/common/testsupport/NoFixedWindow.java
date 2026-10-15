package com.hamstrack.common.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <strong>This test method asserts a throttle refusal and does <em>not</em> need
 * {@link FixedWindow}, for one of three enumerated reasons</strong> (HD-334).
 *
 * <h2>Why a marker and not a name shape</h2>
 * A population keyed on a name loses a member the day somebody renames it and reports the same
 * green afterwards (HD-326). So membership in the exempt set is declared, and
 * {@code FixedWindowCoverageTest} holds it to three things: the reason is one of the three below
 * rather than prose, {@link #refusedBy()} names a production method that <em>exists</em>, and that
 * method's own body is checked against the fixed-window shape — in the direction the reason claims.
 * An exclusion that asserts nothing outlives its subject; this one is re-derived on every run.
 *
 * <h2>Why three reasons and not a boolean</h2>
 * The tree already contained three different legitimate discharges when this was written, and one
 * of them is better than the fixture for its member and must be allowed to stand.
 *
 * @see FixedWindow
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoFixedWindow {

    /** Why this member's verdict cannot turn on where in a wall-clock window it started. */
    enum Why {

        /**
         * There is no clock on the refusal path at all — a semaphore, a counter, a constant
         * {@code Retry-After}. {@code ExpensiveReadConcurrencyLimit} and the
         * {@code TOO_MANY_IN_FLIGHT} family are this: two integers in memory, and
         * {@code ConcurrencyLimitedException.RETRY_AFTER_SECONDS} is the literal {@code 1}.
         */
        CLOCK_FREE_MECHANISM,

        /**
         * The refusal is decided by {@code now.minus(window)} or {@code now - last}: the cutoff
         * moves with the clock and <strong>nothing resets on a boundary</strong>. A delay can only
         * strengthen such a refusal, which is the safe direction. {@code RecipientMailThrottle},
         * {@code IssueRankService}'s rebalance cooldown and {@code RateLimitService}'s login
         * backoff are this — note that the last one lives in a class whose <em>other</em> method is
         * a fixed window, which is why the exemption names a method and not a class.
         */
        SLIDING_WINDOW,

        /**
         * The mechanism <em>is</em> a fixed window and a fresh key would not help — an
         * instance-wide budget hands a retry the window it was already spending — so the member
         * asserts a bound that survives a <strong>single roll</strong>, i.e. any split of its
         * requests between <strong>two</strong> windows, instead of the exact count. Per roll and
         * not per split, which is a different and unachievable claim: the same twenty reports
         * refuse 5 across three windows and 0 across four.
         * {@code CspReportInstanceBudgetTest} is the live
         * example and predates this fixture: twenty reports against a ceiling of five refuse
         * fifteen in one window and, at the worst split, ten across two, so it asserts
         * {@code >= 10} with the arithmetic written out. Strictly better than a retry here, and the
         * seal checks the named method really is a fixed window, so this reason cannot be used to
         * excuse a member that simply has no mechanism at all.
         */
        ROLL_INVARIANT_ASSERTION
    }

    Why why();

    /**
     * {@code Class.method} of the production code that issues the refusal this member asserts —
     * resolved against {@code src/main/java} by the seal, which then reads that method's body and
     * refuses a {@link Why#SLIDING_WINDOW} or {@link Why#CLOCK_FREE_MECHANISM} that names a method
     * keying a counter on {@code floor(wallclock / unit)}, and a {@link Why#ROLL_INVARIANT_ASSERTION}
     * that names one which does not.
     *
     * <p>A method and not a class, because a class is the wrong unit for this question twice over:
     * {@code RateLimitService} holds a fixed window and a sliding backoff side by side, and a
     * population keyed on the class satisfies several sites with one row (HD-306).
     */
    String refusedBy();

    /** What the reader needs to know that the enum constant does not say. Never blank. */
    String reason();
}
