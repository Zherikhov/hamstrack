package com.hamstrack.common.async;

import com.hamstrack.common.mail.MailTask;
import com.hamstrack.common.mail.UndeliverableMail;
import com.hamstrack.common.mail.UndeliverableMail.Reason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * The mail pool, plus <strong>an account of what its shutdown drain did not manage to send</strong>
 * (HD-207).
 *
 * <h2>The loss this closes</h2>
 * {@code setWaitForTasksToCompleteOnShutdown(true)} keeps the queue and waits
 * {@code awaitTerminationSeconds} for it. When that expires, the plain
 * {@code ThreadPoolTaskExecutor} abandons everything still queued as a block and Spring logs one
 * line — <em>"Timed out while waiting for executor to terminate"</em> — that names no message, no
 * recipient and no count. At 10–34 s per send against a degraded SMTP host, five workers flush a
 * handful of messages in fifteen seconds, so a rolling deploy during any backlog could strand up to
 * a full queue of already-committed, already-announced verification and reset mail with nothing
 * written down anywhere.
 *
 * <p>The knob was never the fix. Sizing the queue down to what the drain can flush would mean
 * dead-lettering a burst of signups that a healthy host would have cleared in a second — a queue is
 * there to absorb bursts, and the drain window has nothing to do with how big a burst is worth
 * absorbing. So the queue keeps its size and the residue becomes durable instead.
 *
 * <h2>How the residue is recovered at all</h2>
 * A {@code RejectedExecutionHandler} runs on submission and therefore cannot see this: nothing is
 * being submitted. {@code shutdownNow()} can — it returns the tasks that never commenced — and it
 * returns them <em>as the runnables that were queued</em>, which is only useful because those are
 * {@link MailTask}s carrying their own type and recipient. Under {@code @Async} they would have
 * been {@code FutureTask}s wrapping an unreadable lambda and this class could do no better than
 * count them.
 *
 * <h2>The ordering that makes the write possible, and how it is held</h2>
 * Dead-lettering at shutdown means a database write during shutdown, so the {@code DataSource} and
 * the {@code EntityManagerFactory} must still be open when this runs. That is not a hope: the
 * {@code mailExecutor} bean takes {@link UndeliverableMail} as a constructor argument, so the
 * container records a dependency and destroys <em>this</em> bean before it, before the writer,
 * before the repository's entity manager factory and before the pool. Injecting it through an
 * {@code ObjectProvider} would break exactly that and look tidier while doing it.
 *
 * <p>What no ordering inside the JVM can hold is the process's own stop grace. If the platform's
 * grace period is shorter than {@code app.mail.async.shutdown-drain-seconds}, SIGKILL lands
 * <em>during</em> the drain, this method never runs, and HD-207 is back in full with the fix in
 * place and unreachable. That pair is asserted at startup in {@code MailAsyncProperties.Async}
 * against {@code app.mail.async.stop-grace-seconds} — the same {@code APP_STOP_GRACE_SECONDS} the
 * compose file puts in {@code stop_grace_period}, so the two cannot drift.
 *
 * <h2>The queue is not the whole surface — in-flight sends are outside this mechanism</h2>
 * {@code shutdownNow()} returns what was <em>queued</em> and nothing else. The up-to
 * {@code maxPoolSize} messages a worker has already commenced are interrupted where they stand:
 * they are not in the returned list, so they are neither counted by {@link UndeliverableMail} nor
 * given a {@code failed_email} row, and if such a send's retry loop outlives the {@code DataSource}
 * its own dead-letter write throws into a {@code catch} that logs and swallows. Every claim this
 * class makes is therefore scoped to the word <em>queued</em>, and that scoping is deliberate
 * rather than careless — but with a default {@code max-pool-size} of five, up to five
 * account-critical sends per deploy sit outside the mechanism, so the WARN below names them as an
 * upper bound rather than pretending they do not exist.
 *
 * <p>Waiting for them is not the answer and was considered: a second bounded
 * {@code awaitTermination} is the drain by another name, it would consume grace that
 * {@code isShutdownWithinTheStopGrace()} has budgeted for the residue write, and it would be a
 * window no startup assertion covers. The operator dial for "give the drain longer" is
 * {@code MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS}, whose ceiling now moves with
 * {@code APP_STOP_GRACE_SECONDS}; a second hidden wait would be that dial, spelled where nobody
 * can see it. ({@code super.shutdown()} already does {@code shutdown()} plus
 * {@code awaitTermination(drain)}, so an in-flight send has had the whole window before this
 * method's first line runs — a second wait would only sit outside the assertion.)
 *
 * <p><strong>That refusal answers "waiting", and the gap is not therefore unclosable — nobody has
 * tried "identifying".</strong> A {@code MailTask} could register itself in a concurrent set on
 * entry to {@link MailTask#run()} and remove itself on exit, and this method could then
 * dead-letter what is still in that set alongside the queue's residue, with no extra waiting at
 * all. It costs a set operation per send and one more failure mode at shutdown, and it is a
 * separate ticket rather than a thing this class quietly cannot do. Until it exists, the WARN
 * below is genuinely their only record.
 */
@Slf4j
public class MailTaskExecutor extends ThreadPoolTaskExecutor {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient UndeliverableMail undeliverable;

    /** Kept alongside {@code setAwaitTerminationSeconds} only so the warning below can name it. */
    private final int drainSeconds;

    public MailTaskExecutor(UndeliverableMail undeliverable, int drainSeconds) {
        this.undeliverable = Objects.requireNonNull(undeliverable);
        this.drainSeconds = drainSeconds;
        // Flush in-flight mail on graceful shutdown, bounded so shutdown never hangs. NOT a promise
        // that the queue drains — what it does not reach is dead-lettered below.
        setWaitForTasksToCompleteOnShutdown(true);
        setAwaitTerminationSeconds(drainSeconds);
    }

    /**
     * Graceful drain first, then take back what it did not reach and write it down.
     *
     * <p>{@code super.shutdown()} does the whole of the existing behaviour: {@code shutdown()} on
     * the underlying pool, then a bounded wait. Only after that wait has expired is anything still
     * in the queue known to be abandoned — calling {@code shutdownNow()} any earlier would take
     * messages the drain was about to send.
     *
     * <p>Idempotent by construction: the second call (a test shutting the pool down by hand, then
     * the container destroying the bean) finds an empty queue and returns after the two calls that
     * are themselves idempotent.
     */
    @Override
    public void shutdown() {
        super.shutdown();

        List<Runnable> residue;
        int inFlight;
        try {
            // Read BEFORE shutdownNow(), which interrupts the workers: afterwards this is a count
            // of threads on their way out. ThreadPoolExecutor documents getActiveCount() as an
            // approximation, which is why the warning says "up to" — an honest upper bound is
            // worth more here than a precise number that is not available.
            inFlight = getThreadPoolExecutor().getActiveCount();
            residue = getThreadPoolExecutor().shutdownNow();
        } catch (IllegalStateException notInitialized) {
            return;
        }
        if (residue.isEmpty()) {
            if (inFlight > 0) {
                // No row is possible for these — shutdownNow() does not hand back what a worker is
                // already inside — so this line is their entire record. See the class javadoc.
                log.warn("mailExecutor's {}s shutdown drain expired with an empty queue but up to "
                         + "{} send(s) still in progress. Those are interrupted, cannot be "
                         + "identified from here and get no failed_email row; a critical one among "
                         + "them may write its own on the way out, or may not, depending on whether "
                         + "the datasource is still open.", drainSeconds, inFlight);
            }
            return;
        }

        // The count comes FIRST and unconditionally. Everything below it can fail — the database is
        // one of the things a shutdown may already have lost — and when it does, this line is the
        // entire record. "Up to 100" was the old warning's real content; this one is a number an
        // operator can act on.
        log.warn("mailExecutor's {}s shutdown drain expired with {} email(s) still queued and now "
                 + "abandoned, plus up to {} more already in progress and now interrupted. Their "
                 + "database rows are COMMITTED and their users have already been told the mail "
                 + "was sent; account-critical QUEUED ones are being dead-lettered to failed_email "
                 + "below. The in-progress ones cannot be — shutdownNow() does not return what a "
                 + "worker is already inside — so for those this line is the record.",
                drainSeconds, residue.size(), inFlight);

        var mail = residue.stream()
                .filter(MailTask.class::isInstance)
                .map(MailTask.class::cast)
                .toList();
        int foreign = residue.size() - mail.size();
        if (foreign > 0) {
            // Something reached this pool without going through MailDispatcher — an unqualified
            // @Async is the way that happens, and AsyncConfig's javadoc says why it lands here.
            // Nothing can be recorded about it beyond that it existed.
            log.error("{} abandoned task(s) on mailExecutor were not MailTasks and cannot be "
                      + "identified or dead-lettered. Something is dispatching to this pool "
                      + "without going through MailDispatcher — see AsyncConfig.", foreign);
        }
        undeliverable.recordAll(mail, Reason.SHUTDOWN_RESIDUE);
    }
}
