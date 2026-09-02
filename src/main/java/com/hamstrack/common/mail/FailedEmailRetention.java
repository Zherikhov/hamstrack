package com.hamstrack.common.mail;

import com.hamstrack.common.config.MailAsyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps {@code failed_email} bounded (HD-208 review).
 *
 * <h2>Why this table did not need a sweep before and does now</h2>
 * Every row used to cost an exhausted retry cycle, so its write rate was the mail pool's — a
 * handful a minute at the very worst, in a table an operator reads by hand. A never-attempted row
 * (HD-207/HD-208) costs one <em>enqueue</em>, so the rate became that of the requests that dispatch
 * mail, which on the unauthenticated paths is bounded per IP and not globally. Nothing swept it and
 * nothing alarmed on it: {@code EmailFailures} fires on a metric, and a metric says nothing about
 * how much disk a table is using. On a self-hosted box where the database shares a volume with the
 * attachments, that is a disk-fill with no warning on the way there.
 *
 * <p>The other half of the answer is the per-hour cap in {@link UndeliverableMail} — retention
 * alone still permits the whole flood and merely deletes it three months later, which is three
 * months too late for the disk. See {@code MailAsyncProperties.DeadLetter} for why it takes both.
 *
 * <h2>What is being deleted, and why the default is long</h2>
 * These rows are the only record that somebody's password reset or verification never went, and
 * they are the input to a re-drive. So retention is defaulted well past any window in which anyone
 * would act on one (90 days), and its floor is a week rather than a day: a default that quietly
 * deleted the evidence for an incident nobody looked at over a holiday would be worse than no sweep
 * at all.
 *
 * <h2>One un-indexed DELETE, and what actually bounds the table it scans</h2>
 * {@code failed_email} carries no index on {@code created_at} (V7), so this is a sequential scan.
 * <strong>The cap does not bound the table; it bounds one of the table's two writers</strong>, and
 * an earlier revision of this paragraph said otherwise ("roughly {@code cap × 24 × retentionDays}
 * rows, ~1.1M at the defaults") which was wrong twice over. Written out properly:
 *
 * <ul>
 *   <li><strong>Never-attempted rows</strong> — capped, at
 *       {@code maxNeverAttemptedPerHour × 24 × retentionDays × replicas}. That is ~1.1M on one node
 *       at the defaults, and the {@code replicas} factor is not decoration: the cap is an in-memory
 *       counter <em>per instance</em>, so N Cloud replicas write into one shared table under N
 *       separate ceilings.</li>
 *   <li><strong>Tried-and-failed rows</strong> — <em>not</em> capped, deliberately (their rate is
 *       the mail pool's, and they are the rows an operator most needs). But "the pool's rate" is
 *       not small when the pool fails <em>fast</em>. Against an SMTP host that refuses the
 *       connection or does not resolve — the ordinary self-hosted misconfiguration — a send costs
 *       only its two backoffs, ~4 s at the default {@code max-attempts=3} /
 *       {@code retry-backoff-ms=2000}, so {@code max-pool-size=5} workers produce on the order of
 *       4 500 rows an hour: roughly nine times the cap, from the half of the table nothing bounds.
 *       Over 90 days that is millions of rows and single-digit gigabytes.</li>
 * </ul>
 *
 * <p>So the honest statement is that <strong>the sweep is affordable at ordinary volumes and its
 * failure mode is silent-ish and cumulative</strong>: past some size the sequential scan stops
 * fitting inside {@code DB_STATEMENT_TIMEOUT_MS} (10 s by default), this transaction is cancelled,
 * the day's sweep deletes nothing, and the table then grows without any bound at all. It is logged —
 * the statement bound names itself — but nothing pages on it.
 *
 * <p>The follow-up is an index on {@code created_at}, which is a migration and has a ticket. What
 * should trigger it is <em>either</em> {@code maxNeverAttemptedPerHour × replicas} rising by an
 * order of magnitude <em>or</em> a sustained SMTP outage of the fast-failing kind — not the cap on
 * its own, which is the smaller of the two writers.
 *
 * <h2>Every node runs it</h2>
 * Same argument as {@link MailSendEventRetention}, and it is not inherited silently: the work is
 * idempotent and bounded by an absolute cutoff, so the second replica's DELETE matches the rows the
 * first already removed, which is none of them. No leader election, no advisory lock. A future
 * DB-writing scheduled job that computes from what it reads, sends mail, or is not idempotent
 * needs single-execution and may not copy this pattern without making its own argument.
 *
 * <p><strong>"They do not interfere" is about the result, not about the timing.</strong> The
 * {@code initialDelay} is measured from each process's own start, so two replicas restarted
 * together — a rolling deploy, or one compose file — run this within milliseconds of each other,
 * and the second can block on row locks the first is holding and be cancelled by
 * {@code DB_LOCK_TIMEOUT_MS} (3 s). That is harmless and loud: the day's sweep is idempotent and
 * the next one is 24 hours away, so the only cost is a logged failure and rows that age out a day
 * late. Said here because the difference between "cannot collide" and "collides visibly and does
 * not matter" is exactly what the next reader would otherwise have to rediscover from a stack
 * trace.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedEmailRetention {

    private final FailedEmailRepository repository;
    private final MailAsyncProperties mailAsyncProperties;

    /**
     * Daily rather than hourly. Nothing reads this table on a request path, so a sweep that is a
     * few hours late costs nothing; hourly would only mean 24 sequential scans a day for the same
     * result.
     */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    @Transactional
    public void sweep() {
        var days = mailAsyncProperties.deadLetter().retentionDays();
        var cutoff = Instant.now().minus(Duration.ofDays(days));
        int deleted = repository.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            // INFO, not DEBUG: these rows were undelivered account-critical mail, so how many of
            // them aged out without anybody acting on them is worth having in a shipped log.
            log.info("failed_email retention: deleted {} dead-letter row(s) older than {} ({} days)",
                    deleted, cutoff, days);
        }
    }
}
