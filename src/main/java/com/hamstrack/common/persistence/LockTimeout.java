package com.hamstrack.common.persistence;

import com.hamstrack.common.config.LockingProperties;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * <strong>Bound how long this transaction will wait for a row lock</strong> (HD-136 review
 * round 4), for the handful of transactions that take {@code FOR UPDATE} on membership
 * rows.
 *
 * <p><strong>Why the locks need a bound at all, when they were argued to be
 * deadlock-safe.</strong> Every locking read in the membership paths is deliberately
 * ordered ({@code ORDER BY m.id}) so that overlapping transactions <em>queue</em> instead
 * of cycling — which is the right design, and which means a deadlock is precisely the
 * outcome that does <em>not</em> normally happen. The ordinary outcome is a plain lock
 * wait, and PostgreSQL waits for one <strong>for ever</strong> by default. Nothing else in
 * the stack shortens it: an HTTP client that gives up and disconnects does not abort the
 * server-side transaction, and Hikari's {@code connectionTimeout} bounds only <em>checking
 * out</em> a connection, never a query already in flight on one. So the safety argument
 * ("a deadlock is safe, Postgres rolls one back") covered the rare branch and left the
 * common one unbounded. {@code WorkspaceMemberService.remove} then holds two lock sets
 * across a knowingly unbounded history write, so overlapping removals of a member with a
 * lot of assigned work can pin connections until the pool is gone — for every tenant, in
 * Cloud.
 *
 * <p><strong>{@code SET LOCAL}, never a global setting.</strong> The obvious spelling —
 * {@code spring.datasource.hikari.connection-init-sql=SET lock_timeout = …} — is rejected:
 * Flyway shares this datasource, so every migration would inherit the bound and a
 * {@code ALTER TABLE} queued behind a long read on a large table would begin failing on it.
 * A schema change legitimately waits; a membership edit does not. {@code SET LOCAL} keeps
 * the bound scoped to the transaction that asked for it and is reverted at COMMIT or
 * ROLLBACK, so it can never leak back into the pooled connection — the same property
 * {@code IssueRepository.suppressUpdatedAtForThisTransaction} relies on, and
 * {@code set_config(..., is_local = true)} IS {@code SET LOCAL}.
 *
 * <p><strong>What the caller gets when it fires.</strong> PostgreSQL raises SQLSTATE
 * {@code 55P03 lock_not_available}, which Hibernate maps to {@code LockTimeoutException}
 * and Spring's exception translation to {@code CannotAcquireLockException} — a subclass of
 * {@code PessimisticLockingFailureException}, so it lands on the handler that already
 * exists ({@code GlobalExceptionHandler.handlePessimisticLock}) and answers <strong>409
 * with {@code Retry-After}</strong>, logging a WARN an operator can alert on. No new
 * handler, and the client contract is unchanged: "someone else is changing this right now —
 * try again in a moment", which is exactly what a lost lock race is.
 *
 * <p><strong>It bounds <em>waiting</em> for a lock, not <em>holding</em> one</strong> — do
 * not later read this class as "a membership removal can no longer pin a connection".
 * PostgreSQL applies {@code lock_timeout} per lock <em>acquisition</em>, so
 * {@code WorkspaceMemberService.remove}, which takes two lock sets, can legitimately wait
 * up to roughly twice the configured bound before it fails; and once it holds them it still
 * holds both across the unchunked {@code writeUnassignHistory}, whose size is the departing
 * member's assigned work. What this deletes is the <em>unbounded queue</em>, not the
 * critical section. The complementary control, if the held time ever needs bounding too, is
 * a {@code SET LOCAL statement_timeout} issued from this same helper — never on the
 * datasource, for exactly the Flyway reason above.
 *
 * <p><strong>Call it as the first statement of the transaction.</strong> Two reasons, both
 * hard: a bound applied after a locking read bounds nothing, and this runs a <em>native</em>
 * query, which Hibernate flushes the whole persistence context ahead of — harmless when
 * nothing is pending, which is only guaranteed at the top. {@link Propagation#MANDATORY}
 * (plus the assertion, for a future self-invocation that would bypass the proxy) refuses
 * the misuse that would make it a silent no-op: outside a transaction PostgreSQL downgrades
 * {@code SET LOCAL} to a WARNING and the guard quietly stops guarding.
 *
 * <p>Not applied everywhere. Ordinary requests take no row locks at all, and adding a
 * round-trip to every transaction to bound a wait that cannot happen would be a cost with
 * no matching risk. It belongs on the transactions that take {@code FOR UPDATE}: today the
 * two membership mutations and the project-member removal.
 */
@Component
@RequiredArgsConstructor
public class LockTimeout {

    /**
     * Milliseconds, unitless on purpose: PostgreSQL reads a bare number for
     * {@code lock_timeout} as milliseconds, so nothing has to parse or quote a unit and the
     * value that reaches the GUC is an {@code int} this process validated at startup — not
     * a string an operator wrote.
     */
    private static final String SET_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?1, true)";

    private final EntityManager entityManager;
    private final LockingProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyToCurrentTransaction() {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "lock_timeout must be set INSIDE the transaction it bounds — outside one, "
                + "SET LOCAL is a warning and a no-op");
        entityManager.createNativeQuery(SET_LOCK_TIMEOUT)
                .setParameter(1, String.valueOf(properties.lockTimeoutMs()))
                .getSingleResult();
    }
}
