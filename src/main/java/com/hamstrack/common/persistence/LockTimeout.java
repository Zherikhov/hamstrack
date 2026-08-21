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
 * round 4), for the handful of transactions that take one.
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
 * critical section. The complementary control now exists: {@link BoundedJpaTransactionManager}
 * applies {@code SET LOCAL statement_timeout} to <strong>every</strong> transaction the
 * application opens, because a slow statement — unlike a lock wait — is volumetric and cannot be
 * found by reading the code (HD-151). It carries the lock bound in the same round trip, for the
 * reason below. Still never on the datasource, for exactly the Flyway reason above.
 *
 * <p>Note that the two bounds do not share a status code: losing a lock race is a retryable 409
 * with {@code Retry-After}, exceeding the statement bound is a 422 with none, because an identical
 * retry of a slow query is slow again. Which of the two a contended statement gets is therefore a
 * contract, not an accident, and it is decided entirely by their relative size —
 * {@code DatabaseTimeoutConsistency} refuses to start unless the statement bound is at least twice
 * this one, so for a pure lock wait this bound always fires first.
 *
 * <p><strong>Call it as the first statement the service issues.</strong> (It is no longer
 * literally the transaction's first statement — {@link BoundedJpaTransactionManager} issues the
 * statement bound at {@code doBegin}, before any application code runs. The reasons below are
 * unaffected; only the wording had to move, because "first statement of the transaction" would
 * now read as a broken guard.) Two reasons, both hard: a bound applied after a locking read
 * bounds nothing, and this runs a <em>native</em> query, which Hibernate flushes the whole
 * persistence context ahead of — harmless when nothing is pending, which is only guaranteed at
 * the top. {@link Propagation#MANDATORY}
 * (plus the assertion, for a future self-invocation that would bypass the proxy) refuses
 * the misuse that would make it a silent no-op: outside a transaction PostgreSQL downgrades
 * {@code SET LOCAL} to a WARNING and the guard quietly stops guarding.
 *
 * <p><strong>This class is no longer the only source of the bound, and the finding that changed
 * that is worth keeping</strong> (HD-151). {@link BoundedJpaTransactionManager} now issues
 * {@code lock_timeout} alongside {@code statement_timeout} on <em>every</em> transaction the
 * application opens, so an ordinary contended write — a plain {@code UPDATE} in a transaction that
 * never called this class, which is most issue and comment writes — gives up after
 * {@code app.locking.lock-timeout-ms} with the retryable <strong>409 + {@code Retry-After}</strong>
 * rather than waiting for ever.
 *
 * <p>The reason both had to move together: {@code statement_timeout} counts lock-wait time, so a
 * statement bound shipped <em>without</em> a global lock bound would have cancelled that same
 * contended write at the statement bound and answered <strong>422 with no {@code Retry-After}</strong>
 * — telling the caller a retry is pointless when it succeeds the moment the holder commits. The
 * intuitive alternative, raising the statement bound so the write has more time to win the race, is
 * wrong twice over: it lengthens exactly the unbounded hold the bound exists to delete, and it does
 * not change the status the loser is handed.
 *
 * <p>What survives here is the <strong>explicit re-assert</strong> on the paths that take a lock on
 * purpose. Belt-and-braces rather than duplication: {@link Propagation#MANDATORY} plus the
 * assertion below fails loudly if one of those paths is ever moved outside a transaction, and this
 * is where the reasoning about lock ordering lives for whoever writes the next locking path.
 *
 * <p><strong>Where it belongs, as a rule rather than a list.</strong> Every transaction that
 * takes a row lock — in <em>any</em> mode, {@code FOR UPDATE} and {@code FOR NO KEY UPDATE}
 * alike — or that writes a row another lock-taking transaction holds, calls this as its
 * first statement. Not applied everywhere: ordinary requests take no row locks at all, and a
 * round-trip added to every transaction to bound a wait that cannot happen is a cost with no
 * matching risk.
 *
 * <p>This paragraph used to enumerate the call sites ("the two membership mutations and the
 * project-member removal"). It was wrong within one ticket and wronger within two, because a
 * list is maintained by whoever remembers it exists and this file is the first thing a lock
 * author reads. The enumeration is deliberately gone — grep for
 * {@code applyToCurrentTransaction} for the current set, and state the rule here instead of
 * a census of it.
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
