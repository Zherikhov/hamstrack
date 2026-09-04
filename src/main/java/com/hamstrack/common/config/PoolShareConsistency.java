package com.hamstrack.common.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <strong>The expensive-read surface's share of the connection pool must leave a share behind, and
 * this refuses the boot when it does not</strong> (HD-182).
 *
 * <p>The property being defended, phrased so it survives every number changing:
 *
 * <blockquote>
 * Through the expensive-read surface, no principal may occupy more than
 * {@code app.expensive-read.max-in-flight-per-principal} of a replica's connections and no set of
 * principals more than {@code app.expensive-read.max-in-flight}, so the rest of the API always
 * retains {@code DB_POOL_MAX_SIZE − app.expensive-read.max-in-flight} of them. The per-minute
 * budgets bound throughput; they do not bound occupancy and never did.
 * </blockquote>
 *
 * <p><strong>A sibling of {@link DatabaseTimeoutConsistency}, deliberately not a method inside
 * it.</strong> That class is about <em>timeouts</em>, and one that also asserted occupancy would be
 * the naming rot {@code CLAUDE.md} records for {@code LockTimeout} — a class whose name stops
 * predicting what is in it is a class nobody looks in. Same shape, though, and the same split
 * between a hard ordering rule and a soft sizing judgement, because the same reasoning produced
 * both.
 *
 * <p><strong>It judges the pool's ACTUAL maximum</strong>, read from the {@link HikariDataSource}
 * by {@link ExpensiveReadShare} rather than from the property string. {@code DB_POOL_MAX_SIZE} is
 * one of several ways that number can be set — a profile file, a system property, a test
 * annotation — and a check that compared against a string would pass while the running pool was
 * something else.
 *
 * <p><strong>A refusal is for a number somebody TYPED — and rule 1 needs BOTH of them typed.</strong>
 * {@link ExpensiveReadShare} derives a ceiling the operator has not configured, and it also
 * narrows a typed per-principal ceiling down to a DERIVED surface ceiling rather than letting the
 * pair reach rule 1 (with a WARN naming both). So what survives here is the one configuration where
 * refusing is right: two numbers an operator typed, in an order that cannot work. Anything with a
 * derived number in it is a pair only half of which anybody chose, and taking an instance down over
 * that is this bound doing what it exists to prevent — the mistake a literal default of 6 already
 * made once, crash-looping every upgrade of an install running a pool of 6 or less having set
 * nothing.
 *
 * <p><strong>Three dials, one setting</strong> ({@code DB_STATEMENT_TIMEOUT_MS} is the fourth, and
 * {@link DatabaseTimeoutConsistency} owns it): the pool, the surface share and the per-principal
 * share stop being independently-tuned numbers here rather than in prose that nobody re-reads when
 * one of them moves. The fifth dial on this surface, {@code app.expensive-read.acquire-wait-ms},
 * is deliberately NOT here — it is denominated in Tomcat worker threads rather than in
 * connections, and it is bounded by its own {@code @Max} for the reason written on it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PoolShareConsistency {

    /**
     * Above this share of the pool the expensive surface is taking most of the replica, which may
     * be exactly what an operator with a large pool wants — hence a WARN and not a refusal.
     *
     * <p>Also the fraction {@link ExpensiveReadShare} derives a share at, and shared with it
     * rather than copied for that reason: a derived default that warned about itself at every boot
     * would teach operators to ignore this log.
     */
    static final double COMFORTABLE_SHARE = 0.6;

    /**
     * The numbers IN FORCE, which are not always the numbers configured: an operator who has set
     * neither ceiling gets a share derived from their pool. Everything below is asserted against
     * the effective pair, and the hard rules additionally ask whether the number was TYPED —
     * refusing to start is a correct answer to a wrong configuration and a self-inflicted outage
     * for an install that expressed no intent at all.
     */
    private final ExpensiveReadShare share;

    @PostConstruct
    void check() {
        int perPrincipal = share.maxInFlightPerPrincipal();
        int total = share.maxInFlight();

        // HARD RULE 1 — the same shape as the lock-versus-statement rule: the larger bound never
        // fires, so the smaller number is dead configuration that every document claims is live.
        //
        // REACHABLE ONLY WHEN BOTH NUMBERS WERE TYPED. ExpensiveReadShare clamps a typed
        // per-principal ceiling to a derived surface ceiling, so the mixed case (pool 4, a pinned
        // EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL=3, derived surface ceiling 2 — i.e. an
        // operator who uncommented exactly the line .env.prod.example shows them) narrows visibly
        // instead of crash-looping. The branch is kept rather than deleted: two typed numbers are a
        // stated intent about their relation, and a stated intent is the thing worth refusing.
        if (perPrincipal > total) {
            throw new IllegalStateException(
                    "app.expensive-read.max-in-flight-per-principal (" + perPrincipal + ") is "
                    + "greater than app.expensive-read.max-in-flight (" + total + "), so the "
                    + "per-principal ceiling can never be reached and is dead configuration: the "
                    + "surface-wide ceiling would refuse first, with the wrong errorType "
                    + "(EXPENSIVE_SURFACE_BUSY rather than TOO_MANY_IN_FLIGHT) and the wrong "
                    + "remedy. You have set both numbers, which is why this is refused rather "
                    + "than narrowed: leave EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL out to have "
                    + "it derived and clamped to fit, lower it to at most " + total + ", or raise "
                    + "EXPENSIVE_READ_MAX_IN_FLIGHT"
                    + (share.poolSize() == null
                       ? "."
                       : ", which must stay strictly below the connection pool's maximum of "
                         + share.poolSize() + "."));
        }

        Integer poolSize = share.poolSize();
        if (poolSize == null) {
            log.debug("No HikariDataSource to read a maximum pool size from, so the expensive-read "
                      + "share ({}) could not be checked against the pool.", total);
            return;
        }

        // HARD RULE 2 — without it the bulkhead is not a bulkhead. The expensive surface could hold
        // every connection on the replica, which is precisely the state HD-182 exists to delete,
        // and every document would still say the interactive API keeps a share.
        //
        // ONLY AGAINST A NUMBER SOMEBODY TYPED. A derived share is below the pool by construction,
        // and the one configuration it cannot make so — a pool of ONE — is ExpensiveReadShare's
        // WARN rather than this throw. Refusing to boot teaches an operator that two settings
        // disagree; refusing to boot over a pair nobody chose is an outage with no lesson in it,
        // which is precisely what an install running DB_POOL_MAX_SIZE=6 and no EXPENSIVE_READ_*
        // variable would have got from a literal default of 6.
        if (!share.derived() && total >= poolSize) {
            throw new IllegalStateException(
                    "app.expensive-read.max-in-flight (" + total + ") is not below the connection "
                    + "pool's maximum size (" + poolSize + "), so the expensive-read surface can "
                    + "hold every connection on this replica and the interactive API is left "
                    + "nothing. That is the exact state the bound exists to prevent — a bulkhead "
                    + "that reserves nothing is not a bulkhead, and every document would still say "
                    + "otherwise. Lower EXPENSIVE_READ_MAX_IN_FLIGHT below " + poolSize
                    + ", or raise DB_POOL_MAX_SIZE.");
        }

        // SOFT — a sizing judgement, not an ordering. A deployment with a large pool and an
        // operator who knows their traffic may legitimately want more of it on this surface;
        // refusing to start would be this file overruling them on a number it cannot see the
        // context for. Stated in CONNECTIONS rather than in percent, because connections are what
        // the interactive API actually runs out of.
        //
        // Explicit shares only, and for the reason a refusal may name only an action its reader
        // can perform: a derived share is taken at exactly COMFORTABLE_SHARE, so the sole way it
        // can exceed it is a pool of ONE, where the remedy is the pool and not this number.
        // ExpensiveReadShare says that in the one WARN that names the right variable.
        if (!share.derived() && total > poolSize * COMFORTABLE_SHARE) {
            log.warn("app.expensive-read.max-in-flight ({}) is more than {}% of the connection "
                     + "pool ({}), so the rest of the API is left {} connection(s) while the "
                     + "expensive-read surface is saturated. This is a sizing warning, not an "
                     + "error — a large pool and a known traffic mix make it legitimate. If it is "
                     + "not deliberate, raise DB_POOL_MAX_SIZE or lower "
                     + "EXPENSIVE_READ_MAX_IN_FLIGHT.",
                     total, Math.round(COMFORTABLE_SHARE * 100), poolSize, poolSize - total);
        }
    }

}
