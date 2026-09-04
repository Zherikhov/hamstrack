package com.hamstrack.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * <strong>The statement bound must stay comfortably above the lock bound, and this refuses the
 * boot if it does not</strong> (HD-151).
 *
 * <p>The two settings are independent PostgreSQL GUCs, but they are not independent
 * <em>controls</em>: {@code statement_timeout} measures total statement time <strong>including
 * time spent waiting for a lock</strong>, while {@code lock_timeout} measures only the wait. So
 * the smaller of the two always fires first. PostgreSQL's own documentation says it under
 * {@code lock_timeout} — "if {@code statement_timeout} is nonzero, it is rather pointless to set
 * {@code lock_timeout} to the same or larger value, since the statement timeout would always
 * trigger first".
 *
 * <p><strong>What that would break is a contract, not a preference.</strong> A lost lock race is
 * answered {@code 409} with {@code Retry-After} because it is genuinely retryable — the rival
 * transaction commits and the retry succeeds. A statement-budget refusal is a {@code 422} with no
 * {@code Retry-After}, because an identical retry costs identical time. Set the statement bound at
 * or below the lock bound and every lock wait becomes the second thing rather than the first: the
 * lock bound becomes dead configuration, and clients that were told "try again in a moment" start
 * being told the opposite. Nothing would log, nothing would fail, and the only symptom would be a
 * status code changing under a class of failure nobody was looking at.
 *
 * <p><strong>The scope of that claim grew, and this javadoc says exactly how far it reaches.</strong>
 * When this check was first written, both bounds coexisted in only the seven transactions that call
 * {@code LockTimeout} by hand, so it guarded the ordering <em>there</em>. Since
 * {@code BoundedJpaTransactionManager} issues both on every transaction the application opens, the
 * ordering is a property of <strong>every</strong> transaction in the product — every contended
 * write in the tree, not a list of them. That is the whole claim: this check does not say the
 * values are good, only that a lock wait resolves as a lock wait.
 *
 * <p><strong>Why a 2× margin rather than a bare {@code >}.</strong> At exactly one millisecond
 * more, a transaction that waited nearly the whole lock budget has nearly no time left to do its
 * work, so it would trade a clean 409 for a 422 raised on the very next statement — technically
 * ordered correctly and useless. Two times leaves the work as much time as the wait. At the
 * shipped defaults (3000 / 10000) it holds with room.
 *
 * <p>A {@code @Component} with {@code @PostConstruct} rather than an {@code @AssertTrue} because
 * the two values live in different records ({@code AgileProperties.isPlanningViewBounded} is the
 * precedent for the joint check, and is the shape to copy when both operands are in one record).
 * The outcome is identical — the context fails to start — and the message is the documentation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseTimeoutConsistency {

    /** The statement bound must be at least this many times the lock bound. */
    private static final int MARGIN = 2;

    /**
     * Above this share of Hikari's {@code connectionTimeout}, a saturated pool stops turning over
     * inside the window a waiting request is prepared to wait — which is the property the statement
     * bound was sized against and the one it exists to deliver.
     */
    private static final double POOL_TURNOVER_SHARE = 0.5;

    /** Hikari's own default, applied when nothing overrides it — nothing does in this tree. */
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;

    private final StatementTimeoutProperties statementTimeout;
    private final LockingProperties locking;
    private final Environment environment;

    @PostConstruct
    void check() {
        int statementMs = statementTimeout.statementTimeoutMs();
        int lockMs = locking.lockTimeoutMs();
        if (statementMs < MARGIN * lockMs) {
            throw new IllegalStateException(
                    "app.persistence.statement-timeout-ms (" + statementMs + ") must be at least "
                    + MARGIN + "x app.locking.lock-timeout-ms (" + lockMs + "), i.e. at least "
                    + (MARGIN * lockMs) + ". PostgreSQL applies both bounds to the same statement "
                    + "and statement_timeout counts the lock wait too, so the smaller one always "
                    + "fires first: a statement bound at or below the lock bound makes the lock "
                    + "bound dead configuration and silently turns every lock-wait timeout — today "
                    + "a retryable 409 with Retry-After — into a 422 that is not retryable. Raise "
                    + "DB_STATEMENT_TIMEOUT_MS or lower DB_LOCK_TIMEOUT_MS.");
        }
        // Only after the hard rule passes: a boot that is about to abort should not also print
        // sizing advice about a configuration it is refusing.
        warnIfTheBoundOutlastsTheWait(statementMs);
    }

    /**
     * <strong>Warns, deliberately — this one is a heuristic and the rule above is an ordering.</strong>
     *
     * <p>The difference decides the severity and is easy to get backwards. The {@code 2x} rule is
     * a <em>hard</em> relation: below it, a documented status contract silently inverts, and no
     * value of anything else makes that acceptable, so it stops the boot. This one is a sizing
     * judgement — {@code StatementTimeoutProperties} reasons its default as "roughly a third of
     * Hikari's 30 s {@code connectionTimeout}", so that a fully saturated pool turns over inside
     * the window a waiting request will wait, and saturation degrades to latency rather than to
     * connection-acquisition failures. A deployment with a large pool, a slow tenant and an
     * operator who knows both may legitimately want a longer bound; refusing to start would be
     * this file overruling them on a number it cannot see the context for.
     *
     * <p>But nothing checked it at all, which meant the property's own javadoc claimed a bound the
     * code did not hold: {@code DB_STATEMENT_TIMEOUT_MS=600000} passes every hard check and
     * restores exactly the pool starvation HD-151 exists to delete. One log line at startup is the
     * proportionate answer.
     */
    private void warnIfTheBoundOutlastsTheWait(int statementMs) {
        long connectionTimeoutMs = environment.getProperty(
                "spring.datasource.hikari.connection-timeout", Long.class,
                DEFAULT_CONNECTION_TIMEOUT_MS);
        long comfortable = Math.round(connectionTimeoutMs * POOL_TURNOVER_SHARE);
        if (statementMs > comfortable) {
            log.warn("app.persistence.statement-timeout-ms ({} ms) is more than half of Hikari's "
                     + "connectionTimeout ({} ms), so a saturated pool may not turn over before a "
                     + "waiting request gives up: under load, requests will fail to ACQUIRE a "
                     + "connection rather than merely waiting for one. This is a sizing warning, "
                     + "not an error — a deliberately long bound is legitimate. If it is "
                     + "deliberate, raise DB_POOL_MAX_SIZE with it (and see "
                     + "EXPENSIVE_READ_MAX_IN_FLIGHT, which is what bounds how much of that pool "
                     + "the expensive-read surface may hold at once); if it is not, {} ms or less "
                     + "keeps the property the default was chosen for.",
                     statementMs, connectionTimeoutMs, comfortable);
        }
    }
}
