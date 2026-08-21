package com.hamstrack.common.persistence;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * <strong>The way out of the statement bound, for a transaction that legitimately needs one</strong>
 * (HD-151).
 *
 * <p>{@link BoundedJpaTransactionManager} bounds every transaction the application opens, so this
 * is the complement: the small, deliberate, reviewable set of transactions that must run without
 * a bound. Enumerating the exemptions is the whole design — the participants are "all of them",
 * which is not a set anyone should maintain, while the exemptions are defined by a property
 * ("this transaction does not serve an interactive request") rather than by a census.
 *
 * <p><strong>The exemption set in application code is empty at ship time, and that is a property
 * of the shape rather than an oversight.</strong> The three candidates the ticket named all
 * resolve to "nothing to do", and each for a different reason worth keeping:
 * <ul>
 *   <li><strong>Flyway migrations</strong> — the one that genuinely needs unbounded time, and
 *       exempt <em>by construction</em>: Flyway runs its own transactions on its own connections
 *       and never touches the transaction manager. This is precisely what a
 *       {@code connection-init-sql} or a role-level default would get wrong.</li>
 *   <li><strong>First-login demo seeding</strong> — needs nothing, because
 *       {@code statement_timeout} bounds a <em>statement</em> and {@code DemoDataService} is a
 *       long transaction made of short ones. Exempting it would create a permanently unbounded
 *       transaction on the first-login path to defend against a failure that cannot occur.</li>
 *   <li><strong>"Admin exports"</strong> — no such feature exists. The only export surface is the
 *       report CSV, which is not a candidate for exemption but the <em>heaviest</em> bounded path.
 *       Anyone arriving here to implement "exports must not be bounded" would exempt the exact
 *       opposite of the intent.</li>
 * </ul>
 * The API ships anyway, because the first legitimate long-running job — a backfill, an async
 * export, a scheduled recompute — must have a supported way to say so instead of raising the
 * global number for every request in the instance.
 *
 * <p><strong>It lifts the statement bound only, never the lock bound.</strong>
 * {@link BoundedJpaTransactionManager} issues both, and a job that may run for an hour has no
 * business waiting an hour for a row lock somebody else is holding — that is a queue, not work.
 * An exempted transaction therefore still gives up on contention after
 * {@code app.locking.lock-timeout-ms}, with the same retryable 409 every other transaction gets.
 * If a future job genuinely needs both lifted, that is a second method and a second decision.
 *
 * <p><strong>{@code SET LOCAL}, so an exemption cannot escape its transaction.</strong> The value
 * is reverted at COMMIT or ROLLBACK and can never leak back into the pooled connection — the same
 * property {@link LockTimeout} relies on, and the reason an exemption is safe to hand out at all.
 *
 * <p><strong>It refuses to be a no-op.</strong> Outside a transaction PostgreSQL downgrades
 * {@code SET LOCAL} to a WARNING and carries on, so an exemption applied in the wrong place would
 * return normally while exempting nothing — and the caller would discover that as a 422 on a job
 * it believed was unbounded. {@link Propagation#MANDATORY}, plus the assertion for a future
 * self-invocation that would bypass the proxy, makes that an error instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatementTimeout {

    /**
     * Zero is how PostgreSQL spells "no bound" — the same value {@code StatementTimeoutProperties}
     * refuses as a configured value, which is not a contradiction: as configuration it would
     * silently unbind the whole instance, and here it unbinds one transaction that said why.
     */
    private static final String CLEAR_STATEMENT_TIMEOUT =
            "SELECT set_config('statement_timeout', '0', true)";

    private final EntityManager entityManager;

    /**
     * <strong>Whether this failure is a statement the database cancelled at the bound.</strong>
     *
     * <p>Exists so that "which exceptions mean a cancelled statement" is answered in one place.
     * {@code GlobalExceptionHandler.handleQueryTimeout} answers the same question by declaring
     * the three types on its {@code @ExceptionHandler} — it has to, that is how dispatch works —
     * so the two must move together, and its javadoc explains why there are three. Anything else
     * that needs the answer calls this rather than growing a fourth copy.
     *
     * <p><strong>The cause chain is walked, and that is the whole point.</strong> On the path
     * that runs queries through the {@code EntityManager} directly, the JPA spec requires
     * Hibernate to throw a bare {@link jakarta.persistence.PersistenceException} once the
     * transaction is marked for rollback — which a cancellation always does — so the cancellation
     * is only ever visible as a <em>cause</em>. An {@code instanceof} on the thrown object misses
     * it, silently, on exactly the paths that matter.
     */
    public static boolean isStatementCancellation(Throwable failure) {
        // Depth-bounded rather than "walk until null": this runs on a failure path, and a
        // cause CYCLE — which a hand-written wrapper can produce and the JDK does not prevent
        // beyond self-reference — would hang the thread that was trying to report an error.
        // Ten is far deeper than any real wrapping (three, on the deepest path here).
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof org.springframework.dao.QueryTimeoutException
                || t instanceof jakarta.persistence.QueryTimeoutException
                || t instanceof org.hibernate.QueryTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * Removes the statement bound from the current transaction for the stated reason.
     *
     * @param reason why this transaction may run unbounded — <strong>required, and logged</strong>.
     *               An exemption that does not explain itself is how the set stops being
     *               reviewable, which is the only thing keeping this design honest.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exemptCurrentTransaction(String reason) {
        Assert.hasText(reason, "an exemption from the statement bound must say why — it is the "
                               + "only thing that makes the exemption set reviewable");
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "statement_timeout must be cleared INSIDE the transaction it exempts — outside "
                + "one, SET LOCAL is a warning and a no-op, and the caller would believe it was "
                + "exempt while still being bounded");
        entityManager.createNativeQuery(CLEAR_STATEMENT_TIMEOUT).getSingleResult();
        log.info("Statement bound lifted for this transaction: {}", reason);
    }
}
