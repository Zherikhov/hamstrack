package com.hamstrack.common.mail;

import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.mail.UndeliverableMail.Reason;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>{@code failed_email} is bounded in rate and in age, and it did not have to be until this
 * release</strong> (HD-207/HD-208 review).
 *
 * <h2>What changed about the table, which is not what it stores</h2>
 * A row used to cost <em>one exhausted retry cycle</em>: the mail pool was the throttle, and
 * against SMTP <em>timeouts</em> five workers manage on the order of ten rows a minute, in a table
 * an operator reads by hand. A never-attempted row costs <em>one enqueue</em>, so the write rate
 * became the rate of the requests that dispatch mail — and on the unauthenticated paths that is
 * bounded per IP, which is to say not bounded at all across a handful of source addresses. Nothing
 * swept the table and nothing alarmed on it: {@code EmailFailures} watches a counter, and a counter
 * says nothing about disk.
 *
 * <p>Worth keeping straight, because the capped half is the smaller one: the retry path is only
 * slow while the host is. Against one that fails <em>fast</em> — connection refused, NXDOMAIN — a
 * send costs its two backoffs and nothing else, so the uncapped tried-and-failed half writes far
 * more rows than the cap admits. The cap bounds one writer; retention bounds the table.
 *
 * <h2>Why one bound is not enough</h2>
 * Retention alone permits the entire flood and merely deletes it three months later, which is three
 * months too late for a disk. A cap alone lets a slow trickle accumulate for ever. And a row can be
 * refused for a reason that is not a bound on this table at all — see the pool guard below. Each
 * assertion here is about a different question: how fast the table can grow, how large it can get,
 * and what a write is allowed to cost the rest of the instance.
 *
 * <h2>The cap degrades durability, never visibility</h2>
 * Above it, {@link UndeliverableMail#record} still logs the ERROR naming the type and the
 * recipient's <em>domain</em>, and returns {@code false} — which is exactly what makes
 * {@link MailDispatcher} rethrow, so the loss also reaches the caller's {@code AfterCommit} ERROR
 * line. That return value is the contract asserted here; silence is the one outcome that would be
 * unacceptable.
 */
@SpringBootTest(properties = {
        "app.mail.dead-letter.retention-days=7",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class FailedEmailBoundsTest {

    @Autowired FailedEmailRetention retention;
    @Autowired FailedEmailRepository failedEmailRepository;
    @Autowired FailedEmailWriter failedEmailWriter;
    @Autowired ProductMetrics metrics;
    @Autowired MailAsyncProperties mailAsyncProperties;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext applicationContext;

    /**
     * A recorder of its own, with a cap of two, rather than the container's singleton with the cap
     * set by a test property.
     *
     * <p>The counter is per-instance and its window is an hour, so two tests sharing the context's
     * bean would share a spent budget and pass or fail by execution order — the kind of coupling
     * that shows up later as one flaky method and gets blamed on the wrong thing. Everything below
     * it is the real collaborator: the same writer, the same transaction semantics, the same rows.
     */
    private UndeliverableMail undeliverable;

    @BeforeEach
    void clean() {
        failedEmailRepository.deleteAll();
        undeliverable = new UndeliverableMail(failedEmailWriter, metrics,
                new MailAsyncProperties(mailAsyncProperties.async(), mailAsyncProperties.critical(),
                        new MailAsyncProperties.DeadLetter(7, 2)));
    }

    // ================================================================ the rate bound

    /**
     * <strong>The cap is on never-attempted rows, and it is what stops an HTTP-rate-driven write
     * from filling a disk.</strong> Configured to 2 here so the third dispatch is the one that
     * crosses it; the shipped default is 500 an hour, which no genuine outage on a small instance
     * reaches.
     */
    @Test
    void neverAttemptedRowsStopBeingWrittenAboveTheHourlyCap() {
        assertThat(undeliverable.record(task("first"), Reason.QUEUE_FULL))
                .as("inside the cap: a durable row is the whole point of the mechanism")
                .isTrue();
        assertThat(undeliverable.record(task("second"), Reason.QUEUE_FULL)).isTrue();

        assertThat(undeliverable.record(task("third"), Reason.QUEUE_FULL))
                .as("""
                        above the cap the answer must be FALSE, not a swallowed write. false is \
                        what makes MailDispatcher rethrow, which is what puts the loss into the \
                        AfterCommit ERROR line that names the row an operator would have to open. \
                        A cap that returned true would convert a bounded-durability trade into a \
                        silent drop, which is the one outcome this whole path exists to remove.""")
                .isFalse();

        assertThat(failedEmailRepository.count())
                .as("and the table really did stop growing — the cap is a bound on writes, not a "
                    + "flag on a row")
                .isEqualTo(2);
    }

    /**
     * The cap covers the <em>never-attempted</em> kind only, and this is the assertion that says so.
     * Rows written after a real send failed are produced at the mail pool's rate, which is small and
     * was never the problem, and they are the rows an operator most needs.
     */
    @Test
    void aTriedAndFailedRowIsNotSubjectToTheCap() {
        undeliverable.record(task("burn-1"), Reason.QUEUE_FULL);
        undeliverable.record(task("burn-2"), Reason.QUEUE_FULL);
        assertThat(undeliverable.record(task("burnt"), Reason.QUEUE_FULL))
                .as("premise: the cap is now spent")
                .isFalse();

        // The exact call MailService.deadLetter makes when a send exhausts its retries — the cap
        // must not sit anywhere on that path.
        var row = new FailedEmail();
        row.setEmailType(EmailType.PASSWORD_RESET.name());
        row.setRecipient(address("attempted"));
        row.setSubject("Reset your Hamstrack password");
        row.setLastError("jakarta.mail.MessagingException: connect timed out");
        row.setAttempts(3);
        failedEmailWriter.write(row);

        assertThat(failedEmailRepository.findAll())
                .as("the tried-and-failed row lands whatever the never-attempted cap has done")
                .anySatisfy(written -> assertThat(written.getAttempts()).isEqualTo(3));
    }

    // ================================================================ the pool bound

    /**
     * <strong>A starved connection pool degrades the row to the log line, and does not spend
     * budget doing it</strong> (HD-208 review round 4).
     *
     * <p>{@link UndeliverableMail#record} runs on a <em>committing</em> thread that is still
     * holding a connection, and the write asks for a second one. When threads are already queued
     * for a connection, joining that queue is how ten mail refusals turn into an
     * application-wide stall — every acquisition anywhere parks, not just mail's. So the write is
     * skipped, exactly as it is above the hourly cap, and for the same reason: this path has
     * already lost the message, and the row is a convenience the whole instance should not pay
     * for.
     *
     * <p>Driven through a mocked writer because the real condition is a saturated pool, which a
     * test cannot produce without holding ten connections and would then be racing. What is worth
     * sealing is not Hikari's counter but that the guard is <em>consulted at all</em> — a guard
     * that never fires and one that works are both silent in the happy path, which is this
     * project's documented failure shape.
     */
    @Test
    void aStarvedConnectionPoolDegradesTheRowToTheLogLineWithoutSpendingBudget() {
        var writer = Mockito.mock(FailedEmailWriter.class);
        Mockito.when(writer.poolIsStarved()).thenReturn(true);
        var recorder = new UndeliverableMail(writer, metrics,
                new MailAsyncProperties(mailAsyncProperties.async(), mailAsyncProperties.critical(),
                        new MailAsyncProperties.DeadLetter(7, 2)));

        assertThat(recorder.record(task("starved"), Reason.QUEUE_FULL))
                .as("""
                        false, never a swallowed write: false is what makes MailDispatcher rethrow, \
                        so the loss still reaches the AfterCommit ERROR line. Durability degrades \
                        to visibility here, never to silence.""")
                .isFalse();
        Mockito.verify(writer, Mockito.never()).write(Mockito.any());

        Mockito.when(writer.poolIsStarved()).thenReturn(false);
        assertThat(recorder.record(task("after-1"), Reason.QUEUE_FULL)).isTrue();
        assertThat(recorder.record(task("after-2"), Reason.QUEUE_FULL))
                .as("""
                        the cap here is two, and both are still available: a refusal the pool made \
                        must not consume the hourly budget, or a moment of database pressure would \
                        silently narrow the rate bound for the rest of the hour. The two guards \
                        answer different questions and only one of them is a rate.""")
                .isTrue();
    }

    /**
     * <strong>The probe can read <em>the</em> pool the write borrows from</strong> — not merely
     * <em>a</em> pool.
     *
     * <p>{@link FailedEmailWriter#poolIsStarved()} fails <em>open</em> on purpose — a
     * {@code DataSource} it cannot unwrap answers {@code false}, so a probe that cannot see the
     * pool never becomes the reason an account-critical loss goes unrecorded. That is the right
     * trade and it is also the perfect disguise: a guard wired to a {@code DataSource} it can never
     * unwrap returns {@code false} for ever and is indistinguishable, from every other test in this
     * file, from one that works.
     *
     * <p><strong>The noun matters, and an earlier version of this test proved the wrong one.</strong>
     * Asserting that the injected {@code DataSource} unwraps to some {@code HikariDataSource} with
     * a live MXBean is passed by every realistic wrong-pool shape: an
     * {@code AbstractRoutingDataSource} unwraps to whichever target is currently routed, and a
     * read-replica or a second {@code @ConfigurationProperties} {@code DataSource} would hand this
     * bean, by type, a pool that is not the one the write's transaction borrows from. The guard
     * would then read a healthy idle pool and cheerfully let the write queue on the starved one.
     * So the assertion is <em>identity</em> against the pool behind the {@code EntityManagerFactory}
     * — the only one {@link FailedEmailWriter#write} can possibly use — plus a tripwire on the
     * number of {@code DataSource} beans, so growing a second one is a decision rather than an
     * accident.
     */
    @Test
    void theStarvationProbeIsNotSilentlyBlindToTheRealPool() throws Exception {
        assertThat(applicationContext.getBeansOfType(DataSource.class))
                .as("""
                        tripwire, not a style rule: the probe takes its DataSource by type, so a \
                        second one in the context (a read replica, a routing wrapper, another \
                        @ConfigurationProperties pool) can silently give it a different pool from \
                        the one the dead-letter transaction borrows from. Adding one is allowed — \
                        but then poolIsStarved() must be pointed at the persistence unit's pool \
                        explicitly, and this assertion updated to say so.""")
                .hasSize(1);

        var persistenceUnitDataSource =
                ((EntityManagerFactoryInfo) em.getEntityManagerFactory()).getDataSource();
        var persistenceUnitPool = persistenceUnitDataSource.unwrap(HikariDataSource.class);
        var probedPool = dataSource.unwrap(HikariDataSource.class);

        assertThat(probedPool)
                .as("""
                        the probe must read the pool the write actually borrows from. Same object, \
                        not merely the same class: a routed or replica DataSource unwraps to a \
                        perfectly live Hikari that is the wrong one, and the guard would then \
                        answer "nobody is waiting" about a pool nobody was waiting on.""")
                .isSameAs(persistenceUnitPool);

        var pool = probedPool.getHikariPoolMXBean();
        assertThat(pool)
                .as("poolIsStarved() answers false when it cannot unwrap the DataSource, so "
                    + "without this assertion a guard that never sees the pool passes every other "
                    + "test in this file")
                .isNotNull();
        assertThat(pool.getThreadsAwaitingConnection())
                .as("premise for the rest of the file: an idle test pool has nobody in its "
                    + "acquisition path, which is why every record() above is free to write its row")
                .isZero();
        assertThat(failedEmailWriter.poolIsStarved())
                .as("and the probe agrees with the pool it just read")
                .isFalse();
    }

    // ================================================================ the age bound

    /**
     * <strong>The sweep, which is the only thing that ever deletes one of these rows.</strong>
     * Backdated rather than waited for, and asserted in both directions: a sweep that deleted rows
     * inside the window would be destroying the record that somebody's password reset never went,
     * which is worse than an unbounded table.
     */
    @Test
    void theSweepDeletesRowsPastRetentionAndKeepsTheRest() {
        var old = write("aged", Duration.ofDays(30));
        var recent = write("recent", Duration.ofDays(1));

        retention.sweep();

        assertThat(failedEmailRepository.findById(old))
                .as("a dead-letter row 30 days past a 7-day retention is what the sweep is for")
                .isEmpty();
        assertThat(failedEmailRepository.findById(recent))
                .as("""
                        and a row inside the window must survive. These rows are the only record \
                        that a user's password reset never arrived, and they are what a re-drive \
                        reads — a sweep that took them early would be worse than no sweep at all, \
                        which is why the configured minimum is a week and the default is 90 days.""")
                .isPresent();
    }

    // ================================================================ fixture

    private MailTask task(String label) {
        return new MailTask(EmailType.PASSWORD_RESET, address(label), "Reset your Hamstrack password",
                () -> { throw new AssertionError("a never-attempted task is never run"); });
    }

    /** A row aged past {@code age}, the only way to reach the far side of the sweep's predicate. */
    private UUID write(String label, Duration age) {
        var id = transactions.execute(status -> {
            var row = new FailedEmail();
            row.setEmailType(EmailType.VERIFICATION.name());
            row.setRecipient(address(label));
            row.setSubject("Confirm your Hamstrack email");
            row.setLastError("NEVER ATTEMPTED — the mail queue was full");
            row.setAttempts(0);
            return failedEmailRepository.save(row).getId();
        });
        // Native, because created_at is written by @CreatedDate auditing on the way in and would
        // overwrite anything set here. The sweep's predicate is on this column.
        transactions.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE failed_email SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, Instant.now().minus(age))
                .setParameter(2, id)
                .executeUpdate());
        return id;
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }
}
