package com.hamstrack.common.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * <strong>How big the expensive-read share actually is on THIS replica</strong> (HD-182) — the one
 * place that turns {@link ExpensiveReadProperties}' two ceilings into the numbers the bulkhead
 * enforces, deriving them from the connection pool when the operator has configured none.
 *
 * <h2>Why a default may not be a literal here</h2>
 *
 * <p>{@link PoolShareConsistency} refuses the boot when the surface ceiling is not strictly below
 * the pool, and that rule is right: a bulkhead that reserves nothing is not a bulkhead. But a hard
 * refusal is only right for an operator who <em>set</em> the number. Shipping a literal 6 would
 * have made every existing install with {@code DB_POOL_MAX_SIZE=6} or lower crash-loop on upgrade
 * having set no {@code EXPENSIVE_READ_*} variable at all — and a small pool is a configuration
 * this project actively recommends ({@code .env.prod.example}: "a small pool goes a long way",
 * plus the {@code work_mem × nodes × backends} arithmetic that pushes small boxes down). A bound
 * introduced so that one surface cannot take an instance down must not take the instance down.
 *
 * <p>So: <strong>an explicit number is obeyed and hard-checked; an absent one is derived and
 * cannot fail.</strong> The boot failure survives exactly where refusing to boot is correct.
 *
 * <h2>The derivation, and why it is not the "share of the pool" property that was rejected</h2>
 *
 * <p>A {@code pool-share=0.6} <em>property</em> was rejected on purpose: a number an operator
 * typed must not silently change meaning when somebody tunes the pool for an unrelated reason.
 * This is the opposite case — there is no number and no intent to preserve — and it differs in the
 * three ways that made the other one wrong: it applies only while nothing is configured, it is
 * computed once at startup rather than tracking the pool afterwards, and it is logged, with both
 * numbers and the pool, every time it happens.
 *
 * <ul>
 *   <li>{@code maxInFlight = clamp(floor(pool × 0.6), 1, DEFAULT_MAX_IN_FLIGHT)} — 60 % of the
 *       pool, never more than the 6 this product documents. <strong>Capped, so deriving never
 *       WIDENS the share</strong>: an install with a pool of 50 keeps the documented 6 rather than
 *       silently acquiring 30, and the only installs whose behaviour changes are the ones that
 *       would otherwise not have started. On the shipped pool of 10 it yields exactly 6, so the
 *       documented default is still the running default.</li>
 *   <li>{@code maxInFlightPerPrincipal = min(DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL, maxInFlight)} —
 *       the shipped 3, clamped so the pair can never trip {@link PoolShareConsistency}'s ordering
 *       rule. Deriving one ceiling and not the other would have moved the crash-loop rather than
 *       removed it: at a pool of 4 the derived surface ceiling is 2, and a literal 3 above it is
 *       exactly the dead configuration that rule refuses.</li>
 *   <li><strong>A TYPED per-principal ceiling above a DERIVED surface ceiling is clamped down to
 *       it, with a WARN</strong> — the mixed configuration, and the last place the crash loop was
 *       still reachable. An operator on {@code DB_POOL_MAX_SIZE=4} who uncomments exactly the line
 *       {@code .env.prod.example} shows them ({@code EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL=3})
 *       meets a derived surface ceiling of 2, and hard rule 1 would refuse {@code 3 > 2}. The
 *       principle that produced the derivation applies unchanged: a bound introduced so that one
 *       surface cannot take an instance down must not itself take the instance down, and narrowing
 *       visibly beats refusing to start over a pair only half of which anybody chose. The refusal
 *       survives where it is right — <strong>both numbers typed</strong> is an operator who stated
 *       an intent about their relation, and that intent is the one that can be wrong.</li>
 *   <li>60 % is the same fraction {@link PoolShareConsistency} warns above, and the constant is
 *       shared rather than copied: <strong>a derived share must never warn about itself.</strong>
 *       A boot-time complaint an operator cannot act on — because they configured nothing — is
 *       noise that teaches people to ignore the log.</li>
 * </ul>
 *
 * <p><strong>A pool of 1 is the one case the arithmetic cannot satisfy</strong>, since a share
 * strictly below it would be zero. The derivation yields 1 and this class WARNs: nothing is
 * reserved, because there is nothing to reserve. It does not throw — the operator configured
 * nothing here, and the pool is the thing to fix.
 *
 * <p><strong>No {@code DataSource} to ask, no derivation</strong>: the shipped literals apply and
 * the checks that need a pool are skipped, exactly as they were before. That is the shape of a
 * slice test or any context built without persistence, and a share sized for a pool that is not
 * there protects nothing either way.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpensiveReadShare {

    private final ExpensiveReadProperties properties;

    /**
     * An {@link ObjectProvider} because a context without a {@code DataSource} is a context this
     * cannot derive in, and that must degrade to the shipped literals rather than to a failure.
     */
    private final ObjectProvider<DataSource> dataSource;

    private Integer poolSize;
    private int maxInFlight;
    private int maxInFlightPerPrincipal;

    /**
     * Whether a TYPED per-principal ceiling was narrowed to a DERIVED surface ceiling. Kept as
     * state rather than recomputed, because after the clamp the configured number is gone from
     * every other reading of these fields — and "configured" in the boot log would then name a
     * value that is not in force.
     */
    private boolean perPrincipalClamped;

    /**
     * Resolved once, at startup. Not per read: a value that could change between two acquisitions
     * would make the bulkhead's permit count and this number disagree, and that semaphore is built
     * once from it ({@code PerPrincipalInFlightLimit.initialiseSurfacePermits}).
     */
    @PostConstruct
    void resolve() {
        poolSize = readPoolSize();
        maxInFlight = deriveMaxInFlight();
        maxInFlightPerPrincipal = deriveMaxInFlightPerPrincipal();

        if (perPrincipalClamped) {
            log.warn("EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL is set to {}, which is above the "
                     + "surface ceiling of {} DERIVED from a connection pool of {} — so it has "
                     + "been NARROWED to {} rather than refusing the boot. A ceiling above the "
                     + "surface ceiling can never be reached, and refusing to start over it would "
                     + "be this bound taking the instance down over a pair only half of which you "
                     + "chose. Nothing else changes: your own share is now the whole surface "
                     + "share. To have the number you typed, set EXPENSIVE_READ_MAX_IN_FLIGHT too "
                     + "(it must stay strictly below DB_POOL_MAX_SIZE, so it needs a bigger pool "
                     + "here) or raise DB_POOL_MAX_SIZE and leave both derived.",
                     properties.maxInFlightPerPrincipal(), maxInFlight,
                     poolSize == null ? "unknown" : poolSize, maxInFlightPerPrincipal);
        }

        if (!derived() && !perPrincipalDerived()) {
            return;
        }
        log.info("Expensive-read share in force: max-in-flight={} ({}), "
                 + "max-in-flight-per-principal={} ({}), connection pool={}. A DERIVED number is "
                 + "what an operator who has configured none of this gets: the shipped {}/{} are "
                 + "clamped to this pool rather than refusing the boot on a pool smaller than they "
                 + "were sized for. Set EXPENSIVE_READ_MAX_IN_FLIGHT or "
                 + "EXPENSIVE_READ_MAX_IN_FLIGHT_PER_PRINCIPAL to take a number back, and it is "
                 + "then checked against the pool instead of derived from it.",
                 maxInFlight, derived() ? "derived" : "configured",
                 maxInFlightPerPrincipal,
                 perPrincipalDerived() ? "derived"
                         : perPrincipalClamped ? "configured, narrowed to the derived surface "
                                                 + "ceiling — see the WARN above"
                         : "configured",
                 poolSize == null ? "unknown" : poolSize,
                 ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL,
                 ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT);

        if (derived() && poolSize != null && maxInFlight >= poolSize) {
            log.warn("The connection pool has {} connection(s), so the expensive-read surface "
                     + "cannot be given a share that leaves any behind: it is {} and the "
                     + "interactive API is left none. Nothing is refused at startup because "
                     + "EXPENSIVE_READ_MAX_IN_FLIGHT is not configured — the number to change is "
                     + "DB_POOL_MAX_SIZE, which at this size bounds the whole application and not "
                     + "only this surface.", poolSize, maxInFlight);
        }
    }

    /** The surface ceiling in force — configured, or derived from the pool. */
    public int maxInFlight() {
        return maxInFlight;
    }

    /**
     * The per-principal ceiling in force — configured, derived from the surface ceiling, or a
     * configured one narrowed to a derived surface ceiling ({@link #perPrincipalClamped()}).
     */
    public int maxInFlightPerPrincipal() {
        return maxInFlightPerPrincipal;
    }

    /**
     * Whether a typed per-principal ceiling was narrowed to a derived surface ceiling. Read by
     * tests and by nothing that makes a decision — the decision is the clamp itself, taken once at
     * startup, and every reader downstream is entitled to see one pair of numbers.
     */
    public boolean perPrincipalClamped() {
        return perPrincipalClamped;
    }

    /**
     * Whether the surface ceiling was derived rather than configured — the question
     * {@link PoolShareConsistency} asks before it refuses a boot, because refusing to start is
     * only correct against a number somebody typed.
     */
    public boolean derived() {
        return properties.maxInFlight() == ExpensiveReadProperties.DERIVE_FROM_POOL;
    }

    /** The same question for the per-principal ceiling. */
    public boolean perPrincipalDerived() {
        return properties.maxInFlightPerPrincipal() == ExpensiveReadProperties.DERIVE_FROM_POOL;
    }

    /**
     * The pool's real maximum, or {@code null} when there is nothing to ask. Read from the
     * {@link HikariDataSource} rather than from {@code DB_POOL_MAX_SIZE}, because that property is
     * one of several ways the number can be set — a profile file, a system property, a test
     * annotation — and a share derived from a string would be sized for a pool that is not there.
     */
    public Integer poolSize() {
        return poolSize;
    }

    private int deriveMaxInFlight() {
        int configured = properties.maxInFlight();
        if (configured != ExpensiveReadProperties.DERIVE_FROM_POOL) {
            return configured;
        }
        if (poolSize == null) {
            return ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT;
        }
        int share = (int) Math.floor(poolSize * PoolShareConsistency.COMFORTABLE_SHARE);
        return Math.clamp(share, 1, ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT);
    }

    /**
     * The per-principal ceiling, and the one place the two ways of arriving at a number meet.
     *
     * <p>Unset → the shipped 3, clamped to the surface ceiling. Set, with the surface ceiling
     * DERIVED → clamped to it as well, because the number it would collide with is one this class
     * chose rather than one the operator did. Set, with the surface ceiling also set → returned
     * untouched, so {@link PoolShareConsistency}'s hard rule 1 can refuse the pair: two typed
     * numbers in the wrong order are a stated intent that is wrong, which is exactly the case where
     * refusing to boot teaches something.
     */
    private int deriveMaxInFlightPerPrincipal() {
        int configured = properties.maxInFlightPerPrincipal();
        if (configured == ExpensiveReadProperties.DERIVE_FROM_POOL) {
            return Math.min(ExpensiveReadProperties.DEFAULT_MAX_IN_FLIGHT_PER_PRINCIPAL,
                            maxInFlight);
        }
        if (derived() && configured > maxInFlight) {
            perPrincipalClamped = true;
            return maxInFlight;
        }
        return configured;
    }

    /**
     * Unwraps as well as type-tests: a {@code DataSource} decorated for metrics or lazy
     * connections is still a Hikari pool underneath, and a reader that only handled the bare type
     * would silently skip itself on exactly the deployments that decorate it.
     */
    private Integer readPoolSize() {
        var source = dataSource.getIfAvailable();
        if (source == null) {
            return null;
        }
        if (source instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        try {
            if (source.isWrapperFor(HikariDataSource.class)) {
                return source.unwrap(HikariDataSource.class).getMaximumPoolSize();
            }
        } catch (Exception e) {
            log.debug("Could not unwrap {} to read a maximum pool size", source.getClass(), e);
        }
        return null;
    }
}
