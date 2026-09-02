package com.hamstrack.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <strong>Publish an effect the database cannot take back only once the database has kept its
 * half</strong> (HD-181).
 *
 * <h2>The category, which is what this class is named after</h2>
 * The rule is <strong>any effect published before commit</strong> — not any particular list of
 * call sites. An effect that leaves this process (a message handed to a mail executor, a blob
 * written to object storage, a cache evicted on a peer, a stream closed on a live connection) has
 * no participation in the transaction that produced it: nothing unsends it, and a rollback taken
 * afterwards leaves the outside world describing a row that never existed. Every rollback is a
 * cause — a constraint violation, a late refusal, a statement cancelled at the bound
 * {@code BoundedJpaTransactionManager} applies — and none of them is required to be rare for the
 * outcome to be wrong.
 *
 * <p>So the ordering is the fix, and it is one line at the call site. The shape predates this
 * class: the blob deletes in {@code AttachmentService} were registered on {@code afterCommit} by
 * hand precisely so a rolled-back delete does not remove a file whose row survived, and the SSE and
 * role-cache paths reach the same ordering through
 * {@code @TransactionalEventListener(AFTER_COMMIT)}. What this adds is a name for the rule, so the
 * next such effect has something to reach for rather than a precedent to notice — and a precedent
 * that stays hand-rolled is not a precedent to reach for but a second answer to choose between, so
 * {@code AttachmentService} now calls this class rather than repeating it.
 *
 * <h2>The mirror-image failure, which this class makes visible instead of fixing</h2>
 * Moving an effect after the commit does not make it reliable — it swaps which half can be missing.
 * Before: mail sent, row absent. After: <strong>row present, effect missing</strong>, and the
 * caller has already been told the write succeeded. That half is unrecoverable here by
 * construction (there is nothing left to fail the request with, and failing it would be a lie about
 * durable work that really did commit), so it is <em>logged at ERROR with the description the
 * caller supplied</em> and nothing else.
 *
 * <p><strong>How much that line is worth depends on the effect, and it is worth least where it
 * matters most.</strong> An effect that carries no durability of its own leaves this line as the
 * whole record that it is missing — nobody can retry from it and nothing else knows. An effect that
 * does carry durability writes its own account of the same failure, and this line is then a
 * duplicate rather than the record: {@code MailService} retries account-critical mail, logs its own
 * ERROR and writes a {@code failed_email} row, and the whole of that happens <em>inside</em> the
 * effect, so it is reached without this catch ever seeing anything. Which is the rule to design to:
 * an effect that needs more than one log line needs durability of its own inside the effect. Do not
 * read this class as promising a record of anything.
 *
 * <p><strong>Swallowing is not a preference, it is required.</strong> Spring invokes
 * {@code afterCommit} callbacks from {@code AbstractPlatformTransactionManager.processCommit}
 * <em>after</em> {@code doCommit} and outside any {@code catch}, so an exception thrown here
 * propagates out of {@code commit()} and out of the {@code @Transactional} proxy: the caller gets a
 * 500 for a transaction that committed, and the other synchronizations queued behind this one never
 * run. A throw would therefore convert a missing side effect into a false failure report plus
 * collateral.
 *
 * <h2>The effect must not touch the EntityManager at all — not a lazy association, not a write</h2>
 * "After the commit" and "outside the transaction" are not the same instant, and the gap between
 * them is wide enough to lose a row in. {@code processCommit} runs
 * {@code doCommit → triggerAfterCommit → triggerAfterCompletion} and only then, in a
 * {@code finally}, {@code cleanupAfterCompletion} — and it is that last step which unbinds the
 * {@code EntityManagerHolder} and clears its transaction-active flag. So while an effect runs, the
 * persistence context is <strong>still bound and still claims to be in a transaction</strong>
 * ({@code isExistingTransaction()} answers true) over an {@code EntityTransaction} that has already
 * committed.
 *
 * <p>The consequence is the quietest failure in this file. A nested {@code @Transactional} call —
 * including any Spring Data {@code save}, which is {@code PROPAGATION_REQUIRED} — <em>joins</em>
 * that dead transaction instead of starting one: the {@code persist} succeeds, no flush ever
 * follows, the context is discarded at cleanup, and <strong>no exception is thrown</strong>. Not a
 * rollback, not a {@code TransactionRequiredException} for a {@code catch} to report — the write
 * simply never happened and nothing anywhere says so. HD-181 shipped exactly that and it was found
 * by measurement, not by reading: {@code MailService}'s dead-letter row stopped being written for
 * critical mail that failed on the caller-runs path, in precisely the SMTP outage dead-lettering
 * exists for. The fix is {@code FailedEmailWriter} — {@code REQUIRES_NEW} in a bean of its own, so
 * the write suspends whatever is bound and commits on a transaction it owns.
 *
 * <p>That caller-runs path is gone (HD-208) and <strong>the window is not</strong>, which is the
 * more useful way to hold this: a mail dispatch the full pool <em>refuses</em> is recorded by
 * {@code UndeliverableMail} on the committing thread, inside an effect, and reaches the same
 * writer. The hazard belongs to the window, not to whichever caller happens to be standing in it.
 *
 * <p><strong>That silence is a guard being disarmed, not a case nobody thought about.</strong>
 * Spring ships one written for this exact outcome: {@code SharedEntityManagerCreator} lists
 * {@code persist} among its {@code transactionRequiringMethods}, commented <em>"Otherwise, the
 * operation would get accepted but remain unflushed"</em>, and throws
 * {@code TransactionRequiredException} when neither {@code isActualTransactionActive()} nor the
 * target {@code EntityTransaction} is active. Inside an effect the second is already false and the
 * first is still <strong>true</strong> — that flag is cleared in {@code cleanupAfterCompletion},
 * i.e. after every callback — so the guard passes and the write is accepted and lost. The one check
 * designed to catch this is defeated by the one window that produces it, which is why it has to be
 * a rule here instead of an exception there.
 *
 * <p>A read is no safer than a write, and for a second reason that outlives the first. The obvious
 * one is lifetime: a lazy association resolved here happens to work while the context is still
 * bound, and throws {@code LazyInitializationException} the moment the same call site is reached
 * with nothing bound at all. The one that survives refactoring is that <strong>a post-commit query
 * escapes the statement bound</strong>. {@code BoundedJpaTransactionManager} issues
 * {@code SET LOCAL statement_timeout} / {@code lock_timeout} at begin, and {@code SET LOCAL} dies
 * with the transaction that carried it — so a SELECT issued after the commit takes a fresh
 * connection in autocommit and runs <strong>unbounded</strong>, with a request waiting behind it.
 * Every transaction this application opens is bounded; an effect is not a transaction.
 *
 * <p>So the rule for an effect is the strong one, not the lazy-loading one it looks like: <strong>do
 * not read and do not write through JPA here.</strong> Read what the effect needs into locals before
 * calling {@code run}, and give any persistence the effect genuinely needs a transaction of its own.
 *
 * <h2>No transaction: run inline</h2>
 * With no synchronization active there is nothing to order against and nothing that can roll back,
 * so the effect runs immediately. This is the same decision {@code SseEventListener} makes with
 * {@code fallbackExecution = true} and for the same reason: the alternative is an effect that
 * silently never happens when its call site is reached from a non-transactional path, which is a
 * worse failure than the one being prevented and an invisible one.
 *
 * <h2>An effect published from inside another effect: deferred one callback further</h2>
 * There is a third state, and unhandled it is this class's own failure class wearing this class's
 * own name. Spring triggers {@code afterCommit} over a <em>snapshot</em> of the synchronization
 * list — {@code TransactionSynchronizationManager.getSynchronizations()} copies and sorts precisely
 * so a callback may register more without a {@code ConcurrentModificationException} — so a
 * synchronization registered <em>while that snapshot is being iterated</em> is not in it and never
 * receives {@code afterCommit}. Synchronization is still active in that window, so the inline
 * branch above does not fire either. A registration that implemented only {@code afterCommit} would
 * therefore neither defer the effect nor run it: it would drop it, in silence, in the one file
 * whose whole subject is effects dropped in silence. The shape is ordinary, not exotic — anything
 * running inside an {@code AFTER_COMMIT} listener or an effect of this class is inside that window.
 *
 * <p><strong>Detection is not available, so the fix is not detection.</strong> Nothing distinguishes
 * "inside a live transaction" from "inside an {@code afterCommit} callback": the synchronization
 * list, the bound holder and {@code isActualTransactionActive()} all read identically either side
 * of {@code doCommit}, which is the same indistinguishability that makes the dead-join above
 * silent. So the registration implements {@code afterCompletion} as well. That snapshot is taken
 * later — in the {@code finally} of {@code processCommit}, once {@code triggerAfterCommit} has
 * returned — and therefore <em>does</em> contain the late registration. Whichever callback reaches
 * the effect first runs it, exactly once, and only for {@code STATUS_COMMITTED}, so "never on
 * rollback" is unchanged: a rollback delivers a completion status that is not it.
 *
 * <p>Two further losses close with the same line, both out of one asymmetry in Spring —
 * {@code invokeAfterCommit} does not catch, {@code invokeAfterCompletion} catches {@code Throwable}
 * per synchronization. A callback that throws out of {@code afterCommit} abandons the rest of that
 * iteration, so any effect ordered behind it was skipped with nothing said; it is now still
 * delivered at completion. And an effect that reaches the net runs after the persistence context
 * has been closed rather than merely committed, which makes the "do not touch the EntityManager"
 * rule above fail loudly there rather than quietly. The cost of all of it is one boolean per
 * registered effect.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Run {@code effect} exactly once after the current transaction commits, or immediately if
     * there is no transaction. Never runs on rollback.
     *
     * @param description what the effect is, in the words a log reader needs when it fails and
     *                    cannot be retried — it is the entire content of the failure line, and it
     *                    is written verbatim into a log that is shipped and kept. <strong>No
     *                    personal data, and for mail that means the recipient's domain, never the
     *                    address</strong> ({@code MailAddresses.domainOf}) — the same rule
     *                    {@code RecipientMailThrottle} applies to its own send line, and the
     *                    address is not lost by keeping it out of here: it is in the row the effect
     *                    accompanies, and {@code MailService}'s own failure ERROR still carries it
     *                    on the paths that reach a send at all
     * @param effect      the out-of-database effect. It runs on the committing thread, so it must
     *                    be quick: hand slow work (SMTP) to an executor rather than doing it here.
     *                    <strong>It must not touch the {@code EntityManager} at all</strong>, read
     *                    or write — see the class javadoc. Neither half of that fails where you
     *                    would test it: a lazy read happens to work while the context is still
     *                    bound, blows up on the no-transaction path where nothing is bound at all,
     *                    and runs unbounded by the statement timeout wherever it does work; a write
     *                    is lost in silence on both. Read what it needs into locals before calling
     *                    this.
     */
    public static void run(String description, Runnable effect) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly(description, effect);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new RunOnceAfterCommit(description, effect));
    }

    /**
     * Run by whichever committed-path callback reaches the effect first — see "An effect published
     * from inside another effect" in the class javadoc for why one of them is not enough.
     * {@code afterCompletion} is the net, not the route: on an ordinary transaction
     * {@code afterCommit} has already run the effect and the net finds it done.
     */
    private static final class RunOnceAfterCommit implements TransactionSynchronization {

        private final String description;
        private final Runnable effect;

        /**
         * Not volatile, and it does not need to be: both callbacks are invoked by
         * {@code processCommit} on the committing thread, one strictly after the other.
         */
        private boolean ran;

        private RunOnceAfterCommit(String description, Runnable effect) {
            this.description = description;
            this.effect = effect;
        }

        @Override
        public void afterCommit() {
            runOnce();
        }

        @Override
        public void afterCompletion(int status) {
            // STATUS_ROLLED_BACK is the case the whole class exists for. STATUS_UNKNOWN means the
            // outcome of the commit is not known, and an effect the database may not have kept is
            // precisely what must not be published.
            if (status == STATUS_COMMITTED) {
                runOnce();
            }
        }

        private void runOnce() {
            if (ran) {
                return;
            }
            ran = true;
            runQuietly(description, effect);
        }
    }

    private static void runQuietly(String description, Runnable effect) {
        try {
            effect.run();
        } catch (Throwable e) {
            // Throwable, not RuntimeException, and the width is the point: what must not escape is
            // a THROW, whatever its class. An Error out of here — a lazily linked
            // NoClassDefFoundError on the mail path is the realistic one — produces the exact
            // outcome this class exists to forbid: it leaves commit() by a route that has no catch,
            // so a committed transaction is reported to the caller as a 500 and every
            // synchronization queued behind this one is skipped. Spring draws the line the same way
            // in TransactionSynchronizationUtils, which catches Throwable around afterCompletion.
            //
            // The database half is already committed and the caller has been told so. See the class
            // javadoc for what this line is, and is not, a record of.
            log.error("After-commit effect failed and will not be retried: {}. The database change "
                      + "it accompanies is COMMITTED.", description, e);
        }
    }
}
