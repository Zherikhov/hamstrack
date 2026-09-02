package com.hamstrack.common.mail;

import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <strong>Mail this instance accepted responsibility for and will never attempt</strong> — one
 * mechanism, reached three ways (HD-207 + HD-208).
 *
 * <h2>Why the two tickets share a class</h2>
 * A dispatch refused because the queue is full (HD-208) and a queued message abandoned when the
 * shutdown drain expires (HD-207) are the same event seen at two moments: the row is committed, the
 * user has already been told to check their inbox, and no SMTP attempt will ever be made. The
 * durable artefact is the same {@code failed_email} row {@link MailService} writes when a send
 * exhausts its retries, so designing two mechanisms would mean two answers to "what happened to my
 * verification email".
 *
 * <h2>The row now means two things, and it says which</h2>
 * Before this, a {@code failed_email} row meant <em>we tried and failed</em>. It can now also mean
 * <em>we never tried</em>, and those are not interchangeable to a human re-driving the table or to
 * any future retry job: a message that was never attempted may well succeed on the first try, while
 * one that burned three attempts against a dead host probably will not. Two columns say so without
 * a migration:
 *
 * <ul>
 *   <li>{@code attempts = 0} — the count is literally true, and it was never zero before, because
 *       the old writer only ran after at least one attempt.</li>
 *   <li>{@code last_error} begins with {@value #NEVER_ATTEMPTED}, then the {@link Reason}
 *       <em>constant</em> in brackets, then the sentence written for a human. So the distinction
 *       survives being read by eye in {@code psql} rather than depending on the reader knowing what
 *       a zero means — and the three never-attempted causes are separable from each other by a
 *       token an operator can grep and {@code GROUP BY}, which the prose alone did not give them.
 *       The {@code EmailFailures} alert tells a paged operator to sort a deploy burst
 *       ({@code SHUTDOWN_RESIDUE}) from a saturated pool ({@code QUEUE_FULL}) by exactly that
 *       token; before it was written down the alert named a string this class never wrote.</li>
 * </ul>
 *
 * <h2>Where the address may appear, which is not everywhere</h2>
 * The recipient goes in the row and never in a log line. {@code AfterCommit} already draws that
 * line for the descriptions it prints — a shipped, retained log must not carry the local part —
 * and {@code MailService}'s own failure ERROR is a deliberate exception on the paths that reach a
 * send. Nothing here reaches a send, so nothing here has that excuse: the logs carry the mail type
 * and {@link MailAddresses#domainOf(String) the domain}, and the row carries the address.
 *
 * <p><strong>A rule about what a log line may say has to be checked against what it may
 * carry.</strong> Every {@code log.*} below passes the domain, and the address still reached a
 * shipped log for a while — as an exception <em>argument</em>, because a rejected {@link MailTask}
 * is rendered into {@code TaskRejectedException}'s message and the record's generated
 * {@code toString} printed every component. The redaction now lives on
 * {@link MailTask#toString()}. So a grep over format strings certifies half of this; the other half
 * is every object one of them interpolates.
 *
 * <h2>Best-effort mail gets no row, and the caller is told instead</h2>
 * {@link MailService#isCritical} is the same fork the retry path uses, deliberately: a new
 * {@code EmailType} lands on one side or the other exactly once — which a boolean fork cannot
 * enforce on its own, since its else-branch absorbs any constant added later, so
 * {@code MailCriticalityCoverageTest} fails on a type neither side names. Invite mail is
 * best-effort, so
 * there is nothing durable to write — {@link #record} therefore returns {@code false} and
 * {@link MailDispatcher} rethrows, which is what puts the loss into the {@code AfterCommit} ERROR
 * line that names the workspace an operator would have to open. Silence is the one outcome that is
 * never acceptable; a row is better than a log line, and a log line is better than nothing.
 *
 * <p><strong>For invites specifically that is a regression in kind, and it was accepted with its
 * eyes open.</strong> Under saturation an invite used to be slow-but-delivered (the caller-runs
 * policy sent it inline); it is now dropped, and the only trace is a server-side ERROR the
 * inviter never sees — the endpoint has already answered 201 and the {@code workspace_invites} row
 * is committed. Dead-lettering invites too was considered and rejected: {@code failed_email}'s
 * growth is the largest new risk this change carries (see
 * {@code MailAsyncProperties.DeadLetter}), and INVITE is by far the highest-volume best-effort
 * type, so writing rows for it would make the more serious problem worse to fix the less serious
 * one. The trade is recorded in ADR-0021 and has a ticket of its own; do not widen the dead-letter
 * table to best-effort mail without reading both.
 *
 * <h2>The table is bounded in rate and in time, and neither bound is optional</h2>
 * A row used to cost an exhausted retry cycle and now costs one enqueue, which moved this table's
 * write rate from the mail pool's to the request path's. {@link #record} therefore claims budget
 * from a per-instance hourly cap before it writes, and {@link FailedEmailRetention} sweeps by age.
 * {@code MailAsyncProperties.DeadLetter} carries the arithmetic and the reason it takes both.
 *
 * <p>A third thing can stop a row being written, and it is not a bound on the table at all:
 * {@link FailedEmailWriter#poolIsStarved()}. {@link #record} runs on a committing thread that is
 * still holding a connection, so a write attempted while other threads are already queued for one
 * would join — and lengthen — a queue that stalls the whole instance, not just mail. All three
 * degrade the loss from durable to loud, never to silent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UndeliverableMail {

    /** Prefix on {@code failed_email.last_error} for a message that never reached SMTP. */
    static final String NEVER_ATTEMPTED = "NEVER ATTEMPTED";

    /** The window {@code app.mail.dead-letter.max-never-attempted-per-hour} is counted over. */
    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * Why the message will never be sent, in the words that go into the dead-letter row.
     *
     * <p>Each {@code detail} is written for the person reading {@code failed_email} months later
     * with no memory of this code, so it says what happened rather than naming a policy.
     */
    public enum Reason {

        /**
         * The pool is running and its queue is full. The dispatch is dropped rather than run on the
         * calling thread — see {@code AsyncConfig} for why that trade was reversed.
         */
        QUEUE_FULL("the mail queue was full, so this email was dropped without an SMTP attempt "
                   + "rather than sent on the request thread. Any database row it was announcing "
                   + "is committed"),

        /**
         * A dispatch arriving after {@code shutdown()} — a request still in flight while the
         * container drains. The wording is load-bearing: a test asserts a caller can recognise this
         * refusal by it.
         */
        POOL_SHUT_DOWN("mailExecutor is shut down — this email cannot be queued and will never be "
                       + "sent. Any database row it was announcing is committed"),

        /**
         * Still in the queue when the shutdown drain expired. This is the loss HD-207 was filed
         * for; before it, these messages left one generic line from Spring naming none of them.
         */
        SHUTDOWN_RESIDUE("the shutdown drain expired with this email still queued, so it will "
                         + "never be sent. Any database row it was announcing is committed");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    private final FailedEmailWriter failedEmailWriter;
    private final ProductMetrics metrics;
    private final MailAsyncProperties mailAsyncProperties;

    /**
     * The per-hour, per-instance cap on never-attempted rows — a plain counter and a window start,
     * guarded by the object monitor because both have to move together.
     *
     * <p>Deliberately not a {@code Bucket4j} bucket like the request-path limiters: those are keyed
     * per caller and live in {@code common.ratelimit}; this is one global count of writes this
     * process has made, it is consulted only on a path that is already failing, and contention on
     * it is bounded by the mail pool.
     */
    private final Object neverAttemptedLock = new Object();
    private Instant neverAttemptedWindowStart = Instant.EPOCH;
    private int neverAttemptedInWindow;
    private int suppressedInWindow;

    /**
     * Record one message that will never be attempted.
     *
     * @return {@code true} when a durable {@code failed_email} row now exists. {@code false} means
     *         the loss is only in the log — the mail was best-effort and earns no row, this
     *         instance is over its hourly cap on never-attempted rows, another thread is
     *         already inside the connection pool's acquisition path
     *         ({@link FailedEmailWriter#poolIsStarved()}), or the write itself failed — and the
     *         caller must make sure somebody hears about it.
     */
    public boolean record(MailTask task, Reason reason) {
        // A message that never reaches SMTP is still a failure to send, so it counts on the
        // EXISTING outcome tag rather than a new one: hamstrack_email_sent_total{outcome="failure"}
        // is what the EmailFailures alert reads, and a drop that invented its own tag would be a
        // silent loss with the meter that watches for it pointed elsewhere.
        metrics.emailSent(task.type(), EmailOutcome.FAILURE);

        if (!MailService.isCritical(task.type())) {
            log.warn("Best-effort {} email to a {} address was not sent: {}",
                    task.type(), MailAddresses.domainOf(task.recipient()), reason.detail());
            return false;
        }

        // Both reasons to skip the write are decided BEFORE the line is written, so the line can
        // say which of the three things is about to happen. It is still logged before any database
        // work, which is the ordering that matters: when the write fails, or is refused, this is
        // the whole record.
        //
        // The pool is asked FIRST so that a write it refuses does not spend hourly budget. The two
        // guards answer different questions — "is the pool already contended" and "is this
        // instance writing too many rows" — and only the cap is a RATE, so only the cap keeps a
        // count. Claiming budget and then skipping for the other reason would let a minute of
        // database pressure silently narrow the rate bound for the rest of the hour.
        String refusal = null;
        if (failedEmailWriter.poolIsStarved()) {
            // "contended", not "queued": the pool counter this reads includes threads that will
            // be handed a free connection immediately, so the honest statement is that somebody is
            // in the acquisition path — see FailedEmailWriter#poolIsStarved for why over-reading
            // it is the cheap side of the trade here and would not be somewhere else.
            refusal = "NOT dead-lettered: the database connection pool is contended (another thread "
                      + "is already acquiring a connection), and this write would take a SECOND one "
                      + "while holding the committing thread's first (see FailedEmailWriter). So "
                      + "this line is the only record of it";
        } else if (!claimNeverAttemptedRow()) {
            refusal = "NOT dead-lettered: this instance is over its cap of "
                      + mailAsyncProperties.deadLetter().maxNeverAttemptedPerHour()
                      + " never-attempted rows an hour "
                      + "(app.mail.dead-letter.max-never-attempted-per-hour), so this line is "
                      + "the only record of it";
        }
        log.error("Critical {} email to a {} address will NEVER be sent: {} — {}",
                task.type(), MailAddresses.domainOf(task.recipient()), reason.detail(),
                refusal == null ? "dead-lettering" : refusal);
        if (refusal != null) {
            return false;
        }
        try {
            failedEmailWriter.write(row(task, reason));
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to persist never-attempted dead-letter row for {} email to a {} "
                      + "address", task.type(), MailAddresses.domainOf(task.recipient()), e);
            return false;
        }
    }

    /**
     * Record a whole batch — the queue's residue at shutdown — in <strong>one</strong> transaction.
     *
     * <p>Batched rather than looped on purpose, and the reason is a bound rather than throughput.
     * This runs after the drain has already expired, inside whatever stop grace the platform gives
     * the process, so its cost has to be a constant plus a per-row cost and not N connection
     * acquisitions — {@code MailAsyncProperties.Async} asserts that arithmetic at startup and could
     * not do so against an unbounded loop. It also means one failure mode instead of N: if the
     * database is unreachable at shutdown nothing is written, and the count in the WARN that
     * precedes this call is then the entire record, which is why that WARN carries the count.
     *
     * @return how many rows were durably written
     */
    public int recordAll(List<MailTask> tasks, Reason reason) {
        var critical = new ArrayList<FailedEmail>();
        for (var task : tasks) {
            metrics.emailSent(task.type(), EmailOutcome.FAILURE);
            if (MailService.isCritical(task.type())) {
                critical.add(row(task, reason));
            } else {
                log.warn("Best-effort {} email to a {} address was not sent: {}",
                        task.type(), MailAddresses.domainOf(task.recipient()), reason.detail());
            }
        }
        if (critical.isEmpty()) {
            return 0;
        }
        try {
            failedEmailWriter.writeAll(critical);
            log.error("Dead-lettered {} account-critical email(s) that will never be sent: {}",
                    critical.size(), reason.detail());
            return critical.size();
        } catch (RuntimeException e) {
            log.error("Failed to persist {} never-attempted dead-letter row(s) — those emails are "
                      + "lost with only this line to say so: {}",
                    critical.size(), reason.detail(), e);
            return 0;
        }
    }

    /**
     * <strong>One never-attempted row's worth of budget, or nothing</strong> (HD-208 review).
     *
     * <p>The rate this table is written at stopped being the mail pool's when a row started costing
     * one enqueue instead of one exhausted retry cycle — see {@code MailAsyncProperties.DeadLetter}
     * for the arithmetic. Above the cap the ERROR line already logged by the caller is the record,
     * and {@link #record} returns {@code false}, so the loss also reaches the dispatcher's
     * {@code AfterCommit} ERROR: it is degraded from durable to loud, never to silent.
     *
     * <p>Applied to {@link #record} only. {@link #recordAll} is the shutdown residue, whose size is
     * bounded by {@code queueCapacity} once per process, and whose budget is asserted at startup;
     * capping it would drop rows to defend against a flood it cannot produce.
     *
     * <p>The window is a fixed hour that starts at the first write rather than a sliding one: an
     * exact rate here is worth nothing (the number is an order-of-magnitude disk bound, not a
     * fairness control) and a fixed window is a comparison and two ints.
     */
    private boolean claimNeverAttemptedRow() {
        int cap = mailAsyncProperties.deadLetter().maxNeverAttemptedPerHour();
        synchronized (neverAttemptedLock) {
            var now = Instant.now();
            if (Duration.between(neverAttemptedWindowStart, now).compareTo(WINDOW) >= 0) {
                if (suppressedInWindow > 0) {
                    log.error("{} never-attempted dead-letter row(s) were NOT written in the hour "
                              + "ending now: this instance's cap of {} "
                              + "(app.mail.dead-letter.max-never-attempted-per-hour) was reached. "
                              + "Those emails are recorded only by the ERROR line each one logged. "
                              + "A sustained hour at this cap means the mail queue is saturated — "
                              + "look at the pool and at SMTP before raising the cap.",
                            suppressedInWindow, cap);
                }
                neverAttemptedWindowStart = now;
                neverAttemptedInWindow = 0;
                suppressedInWindow = 0;
            }
            if (neverAttemptedInWindow >= cap) {
                suppressedInWindow++;
                return false;
            }
            neverAttemptedInWindow++;
            return true;
        }
    }

    private FailedEmail row(MailTask task, Reason reason) {
        var row = new FailedEmail();
        row.setEmailType(task.type().name());
        row.setRecipient(MailService.truncate(task.recipient(), 320));
        row.setSubject(MailService.truncate(task.subject(), 255));
        // attempts = 0 and this prefix are the two halves of "we never tried" — see the class
        // javadoc for why the row has to say which of its two meanings it carries.
        //
        // The enum CONSTANT as well as its sentence, and that is not redundancy. The detail() is
        // written for a human reading one row; the name is what makes a hundred rows sortable, and
        // it is what the EmailFailures alert tells a paged operator to look for when it asks them
        // to tell a deploy burst from a saturated pool. Bracketed and up front so a `last_error
        // LIKE 'NEVER ATTEMPTED [SHUTDOWN_RESIDUE]%'` and a `left(last_error, 40)` GROUP BY both
        // work, and so the token survives the 1000-char truncation below whatever the detail says.
        row.setLastError(MailService.truncate(
                NEVER_ATTEMPTED + " [" + reason.name() + "] — " + reason.detail(), 1000));
        row.setAttempts(0);
        return row;
    }
}
