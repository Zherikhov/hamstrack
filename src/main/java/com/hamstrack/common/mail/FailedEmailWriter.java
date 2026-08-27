package com.hamstrack.common.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the dead-letter row for account-critical mail in a <strong>transaction of its own</strong>
 * (HD-181 fix round 1).
 *
 * <h2>Why it cannot join the caller's transaction</h2>
 * A dead-letter row exists to outlive the failure it records, and the caller's transaction is the
 * one thing it must not depend on. Two callers make that concrete, and the second is a regression
 * HD-181 introduced:
 *
 * <ul>
 *   <li><strong>A committing thread.</strong> When {@code mailExecutor}'s queue is full,
 *       {@code CallerRunsPolicy} turns the {@code @Async} dispatch into an inline send on the
 *       calling thread — and every send is now dispatched from an {@code AfterCommit} effect, which
 *       Spring runs <em>after</em> {@code doCommit} but <em>before</em>
 *       {@code cleanupAfterCompletion}. In that window the {@code EntityManagerHolder} is still
 *       bound and still marked transaction-active, so {@code isExistingTransaction()} answers true
 *       while the {@code EntityTransaction} underneath is already committed. A
 *       {@code PROPAGATION_REQUIRED} save therefore joins a <strong>dead</strong> transaction:
 *       {@code persist} succeeds, nothing flushes it, the persistence context is discarded at
 *       cleanup, and <em>no exception is thrown</em> — the row simply never exists and even the
 *       {@code catch} around the write never fires. Measured, not reasoned: before this class the
 *       probe produced zero {@code failed_email} rows for a send failed inside an effect and one
 *       for the same failure outside a transaction.</li>
 *   <li><strong>Any future transactional caller.</strong> Even with a live transaction, joining it
 *       means a later rollback unwrites the record of a mail that really was attempted and really
 *       did fail. The send is not transactional; its dead letter must not be either.</li>
 * </ul>
 *
 * <h2>Why it is its own bean</h2>
 * {@code @Transactional} is proxy-applied, so a same-class call from {@code MailService.deadLetter}
 * would bypass it entirely and silently reinstate the bug. Same reason
 * {@code issue.service.LabelConflictLookup} and {@code common.security.RolePermissionCache} exist.
 *
 * <p>{@code REQUIRES_NEW} suspends whatever is bound (dead or alive), borrows a clean
 * {@code EntityManager} and connection for one INSERT and commits it, then resumes. Inside an
 * {@code AfterCommit} effect that is safe by construction: the suspend/resume walks the
 * synchronization list, and Spring iterates a snapshot of it while triggering {@code afterCommit},
 * so re-registering does not disturb the callbacks still to run.
 *
 * <h2>What the extra transaction costs at the pool — one thread really does hold two</h2>
 * The objection to {@code REQUIRES_NEW} on a committing thread is that it asks Hikari for a
 * <em>second</em> connection while the request's own is still held through a send that HD-76 bounds
 * at ten seconds an attempt. <strong>That objection is correct, and an earlier revision of this
 * paragraph denied it.</strong> The denial reasoned from the release mode — nothing sets
 * {@code hibernate.connection.handling_mode}, so the resource-local default
 * {@code DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION} applies — and concluded the physical
 * connection returns inside {@code tx.commit()}, before any callback. It does not. It is released at
 * {@code cleanupAfterCompletion}, the same step that unbinds the {@code EntityManagerHolder}, which
 * runs <em>after</em> every {@code afterCommit} callback.
 *
 * <p>Measured rather than argued, single-threaded against Hikari's {@code getActiveConnections()}:
 * <pre>base=0  inTx=1  inEffect=1  inRequiresNew=2  afterRequiresNew=1  afterAll=0</pre>
 * So an effect holds one for its whole duration and this write briefly makes it two. Read that
 * number the right way round: the expensive half is not the INSERT, it is that a caller-runs
 * critical send <strong>pins a pool connection for the entire send</strong> — up to the full retry
 * budget under a stalled mail host, against a production pool of ten. That is HD-208's territory,
 * not this class's, and this class adds one short bounded statement on top of it.
 *
 * <p>Recorded this way deliberately. The claim was written from a source reading, believed, and was
 * wrong; it took a measurement to find. Reason about pool occupancy from a probe, not from a
 * release-mode constant.
 *
 * <p>Two things follow that are worth having in the same place. The INSERT is <strong>bounded like
 * any other statement</strong> — suspension unbinds the outer {@code ConnectionHolder} first, so
 * {@code BoundedJpaTransactionManager} finds the new transaction's own holder and its
 * {@code SET LOCAL statement_timeout} / {@code lock_timeout} land on the connection this write uses;
 * it cannot sit on a connection indefinitely. And the transaction is a <strong>gain in
 * observability, not only a cost</strong>: an exhausted pool now fails the {@code begin} with
 * {@code CannotCreateTransactionException}, which lands in {@code MailService.deadLetter}'s catch
 * and is logged with the mail type and recipient. Before, the same starvation produced a write that
 * was dropped in silence, which is nothing to see at all.
 *
 * <p>The pool's own acquisition bound is a separate decision and is deliberately not taken here:
 * {@code spring.datasource.hikari.connection-timeout} is unset, so a starved acquisition anywhere in
 * the application waits the 30 s default. Lowering it is an application-wide change to how every
 * request fails under saturation — with an env var, a compose file and two deployment docs behind it
 * — and it does not belong to a ticket about the ordering of one effect. Filed as HD-208.
 *
 * <p>This is the "durability of its own inside the effect" that {@code AfterCommit} and
 * {@code BoundedJpaTransactionManager} both point at for account-critical mail. It is the only
 * thing standing between an SMTP outage and a user who can never finish signing up, so the write
 * must not be moved back onto a caller's transaction.
 */
@Component
@RequiredArgsConstructor
public class FailedEmailWriter {

    private final FailedEmailRepository failedEmailRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(FailedEmail row) {
        failedEmailRepository.save(row);
    }
}
