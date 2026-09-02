package com.hamstrack.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded mail executor + critical-mail durability knobs (HD-78, HD-207). Bound under
 * {@code app.mail.*} with a distinct prefix so it never clashes with Boot's own
 * {@code spring.mail.*} {@code MailProperties} auto-config.
 *
 * <p>{@code async.*} drives the dedicated {@code mailExecutor} pool (so a stalled SMTP host can't
 * spawn a thread-per-task or starve other asynchronous work). {@code critical.*} drives the
 * retry-then-dead-letter path for account-critical mail (verification + password reset); invite
 * mail stays best-effort. {@code dead-letter.*} bounds the table those two write into.
 *
 * <p>{@code @Validated} so the bounds and the cross-check below are evaluated at binding time, at
 * startup, where nothing is being dispatched to. (That annotation is correct on a
 * {@code @ConfigurationProperties} class and forbidden on anything Spring MVC dispatches to —
 * ADR-0018.)
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record MailAsyncProperties(
        /*
         * @DefaultValue on the nested components, not only on their fields: without it a
         * deployment that sets none of app.mail.async.* binds this component to null and the
         * failure is a NullPointerException out of AsyncConfig rather than a set of defaults. Every
         * one of these groups is fully defaulted, so "absent" and "absent and defaulted" have to
         * mean the same thing at both levels.
         */
        @DefaultValue @Valid Async async,
        @DefaultValue @Valid Critical critical,
        @DefaultValue @Valid DeadLetter deadLetter
) {
    /**
     * The pool's shape and how long its shutdown is allowed to take.
     *
     * <h2>Three numbers that have to be read together</h2>
     * {@code queueCapacity} and {@code shutdownDrainSeconds} were, until HD-207, inconsistent by an
     * order of magnitude with a silent loss in the gap: a hundred slots against a window that
     * flushes five to seven messages when SMTP is degraded, and the other ninety-odd abandoned with
     * one generic line from Spring naming none of them.
     *
     * <p><strong>The fix was not to make the queue small, and that is worth stating because it is
     * the obvious move and it is wrong.</strong> A queue exists to absorb a burst; the drain window
     * exists to bound how long a container takes to stop. Tying the first to the second would mean
     * dead-lettering a burst of signups that a healthy host clears in a second, in order to make a
     * shutdown promise nobody asked for. Instead {@code MailTaskExecutor} dead-letters whatever the
     * drain does not reach, so <strong>raising {@code queueCapacity} alone is safe</strong> — it
     * buys more absorption and costs at most more dead-letter rows on a deploy. That is a deliberate
     * departure from the ticket's "raising either without the other turns the build red": once the
     * residue is durable, only one direction is dangerous, and an assertion that pretended otherwise
     * would be theatre.
     *
     * <h2>What IS dangerous, and is therefore asserted</h2>
     * See {@link #isShutdownWithinTheStopGrace()}. In one sentence: the whole shutdown — the drain,
     * then the batch write of its residue — has to finish inside the stop grace the platform gives
     * the process, or SIGKILL lands mid-drain, the residue path never runs, and HD-207 is back in
     * full with the fix in place and unreachable.
     *
     * <p>That grace is the third number, and it is a <em>property</em> rather than a constant so
     * that the refusal can prescribe something its reader can do. One variable —
     * {@code APP_STOP_GRACE_SECONDS} — is read by {@code stop_grace_period} in the compose file and
     * by {@code stopGraceSeconds} here, so an operator who needs a longer drain raises the grace in
     * their {@code .env} and both halves move at once. It also removes a drift the code could not
     * police: while it was a hand-copy, lowering the compose value left this record comparing
     * against the old one, passing, and certifying a budget the process no longer got.
     */
    public record Async(
            @DefaultValue("2") @Min(1) @Max(50) int corePoolSize,
            @DefaultValue("5") @Min(1) @Max(50) int maxPoolSize,
            /*
             * Bounded above because the residue write is one batch INSERT of up to this many rows
             * inside the shutdown budget below, and because a queue deeper than this is not
             * absorbing a burst any more, it is hiding an outage.
             */
            @DefaultValue("100") @Min(1) @Max(10_000) int queueCapacity,
            /*
             * Was a hard-coded 15 inside AsyncConfig. It is a property now for one reason: HD-207's
             * complaint is that it and queueCapacity are related and only one of them was
             * operator-visible, and a relationship cannot be stated where an operator changes
             * either one while one of them is a literal in a Java file.
             *
             * The @Max is the ANNOTATION's bound and never the operative one: what an operator may
             * actually set is decided by isShutdownWithinTheStopGrace() against the grace below, so
             * at the default grace this tops out near 28 and at a grace of 300 the whole range is
             * reachable. An earlier revision paired this @Max(120) with a hard-coded 30 s grace,
             * which made 29..120 a range no value could ever pass — a false affordance in the one
             * place an operator looks for the permitted range.
             */
            @DefaultValue("15") @Min(1) @Max(120) int shutdownDrainSeconds,
            /*
             * The stop grace this deployment gives the process, in the ONE place both halves of
             * the deployment read it from: docker-compose.prod.yml spells stop_grace_period as
             * ${APP_STOP_GRACE_SECONDS:-30}s and application.properties binds this from
             * ${APP_STOP_GRACE_SECONDS:30}, so one .env line moves the container's grace and the
             * application's belief about it together.
             *
             * It was a hand-copied constant here until the HD-207 review, and the reason it could
             * not stay one is not tidiness. An operator legitimately needs a longer drain — but
             * raising it was refused at boot whatever they wrote in their compose file, because the
             * number it was compared against was a literal in this file. The refusal therefore
             * prescribed an action its reader could not perform: nobody running a published image
             * can edit a Java constant. Bound, the same edit is one variable, and the cross-check
             * below compares two BOUND values instead of one bound value and a hand-copy.
             */
            @DefaultValue("30") @Min(1) @Max(600) int stopGraceSeconds
    ) {

        /**
         * One connection acquisition plus a transaction begin/commit for the residue batch. It is a
         * budget, not a measurement — a healthy local INSERT is single-digit milliseconds, and this
         * leaves room for a database that is itself shutting down.
         *
         * <p><strong>It does not cover a starved pool, and that gap is accepted rather than
         * overlooked.</strong> {@code spring.datasource.hikari.connection-timeout} is unset, so an
         * acquisition that finds no free connection waits Hikari's 30 s default and blows this
         * budget on its own. Binding it lower is not available as a local fix: {@code
         * StatementTimeoutProperties} derives its 10 s default as roughly a third of that same 30 s
         * so that a saturated pool turns over inside the window a waiting request will wait, and
         * {@code DatabaseTimeoutConsistency} warns at startup when the statement bound exceeds half
         * the acquisition bound — so a 3 s acquisition would need a ≤1.5 s statement bound, which
         * is below that property's own hard floor of twice {@code DB_LOCK_TIMEOUT_MS}. In other
         * words the pool's wait is already sized against the statement bound by a rule this file
         * does not get to overrule, and shortening it here would trade "saturation degrades to
         * latency" for "saturation degrades to 500s" across the whole application.
         *
         * <p>What covers the gap instead is ordering: the WARN naming the abandoned count is
         * emitted <em>before</em> the write is attempted, so when the write does outrun the grace
         * the count is still in the log. A budget that cannot be guaranteed is paired with a record
         * that does not depend on it.
         */
        private static final long RESIDUE_WRITE_FIXED_MS = 1_000;

        /** One row of a batched INSERT. */
        private static final long RESIDUE_WRITE_PER_MESSAGE_MS = 1;

        /**
         * <strong>The whole shutdown must fit inside the stop grace.</strong>
         *
         * <p>Shutdown is two steps, in order: wait {@code shutdownDrainSeconds} for in-flight sends
         * and whatever the workers can pull off the queue, then write the residue — up to
         * {@code queueCapacity} rows — as one batch. If the sum exceeds the grace the platform
         * gives the process, the JVM is killed part-way through and everything still queued is lost
         * exactly as it was before HD-207, except now with a mechanism in the code that makes a
         * reader believe otherwise.
         *
         * <p>All three knobs appear, and that is why this is a relationship rather than three
         * separate {@code @Max}es: at the default grace and drain a full 10 000-slot queue fits,
         * and at a 20-second drain it does not — while at a grace of 60 both do. No one of the
         * three is wrong on its own; the triple can be.
         *
         * <p>A unit test wanting the {@code false} branch constructs the record directly rather
         * than binding properties.
         */
        @AssertTrue(message = "app.mail.async.shutdown-drain-seconds and "
                + "app.mail.async.queue-capacity must together fit inside "
                + "app.mail.async.stop-grace-seconds. Shutdown waits the drain, THEN writes "
                + "whatever is still queued to failed_email as one batch; if the process is killed "
                + "before that write, every queued account-critical email is lost silently, which "
                + "is the loss this path exists to prevent. Fix it with ONE of: a shorter "
                + "MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS, a smaller MAIL_ASYNC_QUEUE_CAPACITY, or a "
                + "larger APP_STOP_GRACE_SECONDS — which raises the container's stop_grace_period "
                + "and this bound together, since docker-compose.prod.yml reads the same variable")
        public boolean isShutdownWithinTheStopGrace() {
            return shutdownDrainSeconds * 1_000L
                   + RESIDUE_WRITE_FIXED_MS
                   + (long) queueCapacity * RESIDUE_WRITE_PER_MESSAGE_MS
                   <= stopGraceSeconds * 1_000L;
        }
    }

    public record Critical(
            @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
            @DefaultValue("2000") @Min(0) @Max(60_000) long retryBackoffMs
    ) {}

    /**
     * <strong>What keeps {@code failed_email} a bounded table</strong> (HD-208 review).
     *
     * <h2>Why it needed bounding at all, when it never did before</h2>
     * A row used to cost <em>one exhausted retry cycle</em>, so the table's growth rate was
     * throttled by the mail pool — five workers times three attempts against SMTP timeouts is on
     * the order of ten rows a minute at the very worst. A never-attempted row costs <em>one
     * enqueue</em>, so the same table is now written at the rate of the requests that dispatch
     * mail: {@code app.rate-limit.auth-ip-requests-per-minute} is 15 <em>per IP</em>, and a handful
     * of source IPs against a full queue is hundreds of rows a minute — hundreds of megabytes a
     * day on a box whose database may share a volume with everything else, with nothing sweeping
     * it and nothing alarming on the way there ({@code EmailFailures} watches the metric, which
     * says nothing about table size).
     *
     * <h2>Two bounds, because either alone leaves a hole</h2>
     * <ul>
     *   <li>{@code retentionDays} bounds the table in <em>time</em> — the same shape as
     *       {@code MailSendEventRetention}, and defaulted far past any plausible re-drive window,
     *       because these rows exist to be acted on and deleting one is deleting the only record
     *       that a user's password reset never went.</li>
     *   <li>{@code maxNeverAttemptedPerHour} bounds the <em>rate</em>, per instance, and only for
     *       the never-attempted kind. Without it, retention alone still permits the whole flood —
     *       it merely deletes it ninety days later, which is ninety days too late for the disk. The
     *       tried-and-failed rows are deliberately not capped: their rate is the pool's, which is
     *       already small, and they are the rows an operator most needs.</li>
     * </ul>
     *
     * <p>The cap does not make a loss silent: {@code UndeliverableMail} logs the ERROR naming the
     * type and the recipient's domain <em>before</em> it decides whether to write, and returns
     * {@code false} when it does not, which puts the loss into the caller's {@code AfterCommit}
     * ERROR line as well. Above the cap the log is the record, which is the same fallback the
     * shutdown path already relies on when the database is gone.
     *
     * <h2>What the cap does and does not bound — it is one writer, not the table</h2>
     * {@code cap × 24 × retentionDays} is the ceiling on <strong>never-attempted rows on one
     * node</strong>: ~1.1M at the defaults, times the number of replicas, because this is an
     * in-memory counter per instance and they all write into one table. It is <em>not</em> a
     * ceiling on {@code failed_email}, and an earlier revision of this paragraph said it was.
     *
     * <p>The tried-and-failed half is uncapped by choice, and its worst case is the larger of the
     * two. Against an SMTP host that fails <em>fast</em> (connection refused, NXDOMAIN — the usual
     * self-hosted misconfiguration) a send costs only its backoffs, ~4 s at {@code maxAttempts} 3
     * and {@code retryBackoffMs} 2000, so {@code maxPoolSize} 5 writes on the order of 4 500 rows
     * an hour — about nine times the cap — for as long as the outage lasts.
     *
     * <p>That matters because the retention sweep is a single un-indexed DELETE
     * ({@code failed_email} has no index on {@code created_at}, and adding one is a migration with
     * a ticket of its own). Past some table size the sequential scan stops fitting inside
     * {@code DB_STATEMENT_TIMEOUT_MS}, the daily sweep is cancelled and deletes nothing, and the
     * table grows unbounded from there. The trigger for that index is {@code cap × replicas} rising
     * by an order of magnitude <em>or</em> a sustained fast-failing outage — not the cap alone.
     * {@link com.hamstrack.common.mail.FailedEmailRetention} carries the same arithmetic beside the
     * DELETE itself.
     */
    public record DeadLetter(
            /*
             * 90 days. This is an operational record of mail that never reached somebody's inbox,
             * so the floor is deliberately not small: a week would quietly delete the evidence for
             * an incident nobody looked at over a holiday.
             */
            @DefaultValue("90") @Min(7) @Max(3650) int retentionDays,
            /*
             * Per instance and per hour, and only for never-attempted rows. 500/hour is ~30x the
             * old worst-case write rate of the whole table and ~1/36 of what a handful of source
             * IPs can drive, so it is loose enough never to be met by a genuine outage on a small
             * instance and tight enough that the disk cannot be filled through it.
             */
            @DefaultValue("500") @Min(1) @Max(1_000_000) int maxNeverAttemptedPerHour
    ) {}
}
