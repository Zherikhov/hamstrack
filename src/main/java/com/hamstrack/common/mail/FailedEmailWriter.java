package com.hamstrack.common.mail;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

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
 *   <li><strong>A committing thread.</strong> Every mailer is dispatched from an
 *       {@code AfterCommit} effect, and a dispatch the pool refuses is recorded <em>on that
 *       thread</em>, right there inside the effect — which was originally reached by
 *       {@code CallerRunsPolicy} running the whole send inline (HD-208 has since deleted that
 *       policy) and is reached today by {@code MailDispatcher} writing a never-attempted row for a
 *       message the full queue would not take. The window is the same either way: Spring runs an
 *       effect <em>after</em> {@code doCommit} but <em>before</em>
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
 * number the right way round: the expensive half was never the INSERT, it was that a caller-runs
 * critical send <strong>pinned a pool connection for the entire send</strong> — up to the full
 * retry budget under a stalled mail host, against a production pool of ten. HD-208 removed that
 * half by deleting {@code CallerRunsPolicy}: no request thread performs SMTP any more, so the
 * longest a committing thread now holds its second connection is one bounded INSERT. This class was
 * the cheap statement on top of an expensive one; it is now the only one.
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
 * <p><strong>The pool's own acquisition bound is a separate decision, and this is the third review
 * to reach it and the first to find out why it cannot simply be taken here.</strong>
 * {@code spring.datasource.hikari.connection-timeout} is unset, so a starved acquisition anywhere
 * in the application waits the 30 s default — and that unset default is now the sole bound on
 * <em>four</em> harms:
 *
 * <ol>
 *   <li>a 30 s park on unauthenticated {@code register} / {@code forgot-password} under database
 *       pressure;</li>
 *   <li>an address-correlated one on the known branch alone — a timing signal on the endpoint whose
 *       whole design is that its branches are indistinguishable;</li>
 *   <li>the shutdown residue write, whose budget is a budget only while the acquisition inside it
 *       is short;</li>
 *   <li><strong>and the one that actually hurts: an application-wide stall, not a mail-path
 *       latency.</strong> The measurement above is what makes it — {@code inEffect=1} to
 *       {@code inRequiresNew=2} means the committing thread <em>holds its first connection while
 *       parking for the second</em>. Under a saturated mail queue, N Tomcat threads inside their
 *       {@code AfterCommit} window each hold one connection and wait up to 30 s for another; with
 *       {@code DB_POOL_MAX_SIZE=10} against Tomcat's 200 threads, ten of them empty the pool and
 *       <em>every acquisition anywhere in the application</em> parks — reports, board, login,
 *       nothing to do with mail. It is self-amplifying, because each parked thread keeps its first
 *       connection for the full wait and so shrinks the free pool further, and self-resolving in
 *       30 s waves. The first three harms are latency on the mail path; this one is the whole
 *       instance.</li>
 * </ol>
 *
 * <p>The fourth is <strong>closed by {@link #poolIsStarved()}</strong> rather than accepted — see
 * that method, which removes the amplification without touching a global timeout. The other three
 * remain, and the reason a shorter acquisition bound is not the fix for them is a relationship this
 * codebase already reasons about and already checks. {@code StatementTimeoutProperties} derives its
 * 10 s default as roughly a third of Hikari's 30 s precisely so that a saturated pool turns over
 * inside the window a waiting request will wait, and
 * {@code DatabaseTimeoutConsistency.warnIfTheBoundOutlastsTheWait} logs a WARN whenever the
 * statement bound exceeds half the acquisition bound. A 3 s acquisition would therefore warn at
 * every boot <em>on the shipped defaults</em>, and silencing it honestly would need a statement
 * bound of 1.5 s — below that property's own hard floor of twice {@code DB_LOCK_TIMEOUT_MS} at
 * <em>its</em> default of 3000 ms.
 *
 * <p><strong>That is a bound on what one ticket may change, not a proof of impossibility, and an
 * earlier revision of this paragraph read as the latter.</strong> "The shortest consistent
 * acquisition bound is around 20 s" is true only while the statement bound stays where it is:
 * {@code lockTimeoutMs}'s own {@code @Min} is 100, so a fully re-sized family — lock 500, statement
 * 1000, acquisition 2000 — is expressible today and violates none of the three rules. What makes it
 * out of scope here is that it is the whole family: re-sizing it trades "saturation degrades to
 * latency" for "saturation degrades to 500s" across every endpoint, and needs an env var, a compose
 * file and two deployment docs behind it. A mail ticket may not take that on the way past; the
 * ticket that does take it should know these paths are among its beneficiaries.
 *
 * <p>Recorded here rather than left implicit so the next reviewer meets the counter-argument
 * instead of the omission.
 *
 * <p>This is the "durability of its own inside the effect" that {@code AfterCommit} and
 * {@code BoundedJpaTransactionManager} both point at for account-critical mail. It is the only
 * thing standing between an SMTP outage and a user who can never finish signing up, so the write
 * must not be moved back onto a caller's transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailedEmailWriter {

    private final FailedEmailRepository failedEmailRepository;

    /**
     * Injected for {@link #poolIsStarved()} only — nothing here opens a connection by hand.
     *
     * <p>It also tightens the destruction ordering {@code MailTaskExecutor}'s javadoc depends on:
     * the executor already declares this bean as a dependency, and this bean now declares the
     * {@code DataSource}, so the container has one more recorded reason to close the pool after the
     * residue write rather than during it.
     */
    private final DataSource dataSource;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(FailedEmail row) {
        failedEmailRepository.save(row);
    }

    /**
     * <strong>Is anybody inside the pool's connection-acquisition path?</strong> — the cheap
     * guard that closes the fourth harm in the class javadoc, without configuration and without
     * touching a global timeout.
     *
     * <p>Callers on a <em>committing thread</em> ask this before {@link #write}. When it answers
     * {@code true} they skip the write and log instead, which is structurally the same trade as
     * {@code UndeliverableMail}'s hourly cap: a cheap in-memory check on a path that is already
     * failing, degrading durability to visibility rather than to silence. The message is lost
     * either way; the row is a convenience for whoever re-drives it, and the pool belongs to
     * everybody.
     *
     * <h2>What the counter counts, which is more than its name says</h2>
     * Read out of HikariCP 7.0.2's sources rather than off the method name:
     * {@code ConcurrentBag.borrow} does {@code waiters.incrementAndGet()} <strong>before</strong>
     * it scans the shared list, and decrements in the {@code finally} — so the count includes
     * threads that are about to find a free connection immediately and never park at all.
     * {@code getThreadsAwaitingConnection() > 0} therefore means <em>"at least one thread is
     * inside the pool's non-thread-local acquisition path"</em>, not <em>"at least one thread is
     * queued"</em>. It over-reads, and an over-read costs a row that would otherwise have been
     * written.
     *
     * <p><strong>Deliberate, and cheap where it is used.</strong> This is reached only after a
     * dispatch has already been rejected, so on a healthy instance it is never called at all; at
     * roughly 200 borrows a second the chance of a call catching a transient non-zero is on the
     * order of 4×10<sup>-4</sup>, and the whole consequence of catching one is a single row
     * degraded to a log line on a path that has already lost the message.
     *
     * <p><strong>Which is exactly why the real reading is written down here.</strong> The obvious
     * next reuse is gating {@code MailService.deadLetter}'s <em>tried-and-failed</em> write — the
     * most valuable rows in the table, the ones a re-drive actually reads — where a false positive
     * is expensive rather than free. Under the "somebody is already queued" reading that reuse
     * looks costless and would be made without an argument. Under the true one it is a trade to be
     * argued on its own merits, and this method's cheapness does not carry over to it.
     *
     * <p><strong>It is still the right predicate</strong>, precisely because it fires only on
     * <em>proven</em> contention. {@code getIdleConnections() == 0} would close the blind spot the
     * paragraph below describes — the transition <em>into</em> starvation, where nobody is in the
     * path yet — but it is the wrong trade and was rejected: on a right-sized pool
     * {@code idle == 0} is the ordinary state at peak, so that disjunct would degrade
     * account-critical durability on mere busyness. Do not add it as a second condition here.
     *
     * <p><strong>What it removes is the amplification, not the park</strong> — and the difference
     * is worth being exact about, because the looser claim ("no thread parks any more") is false.
     * This reads the pool at one instant and the acquisition happens a moment later, and that
     * window is not ε: it spans the {@code @Transactional} proxy, {@code getTransaction} and the
     * suspend, {@code createEntityManager}, and {@code JpaTransactionManager.doBegin}'s eager
     * {@code getJdbcConnection} — tens to hundreds of microseconds. So what still parks is
     * <strong>a small constant number of threads, not N</strong>: every committing thread that
     * enters that window before the first one becomes visible in the count passes the guard too.
     * The conclusion is unchanged, and put this way it survives a future where refusals arrive in
     * a correlated burst — a constant instead of a self-feeding wave. Each of those parkers still
     * holds its own first connection for up to the 30 s default, so the per-thread cost is exactly
     * what it always was; only the multiplication is gone. And acquisitions with nothing to do
     * with mail still join the queue behind them: what is closed is mail's <em>contribution</em>
     * to the cascade, which is how the fourth harm is defined.
     *
     * <p><strong>Fails open, in both directions of "unknown".</strong> A {@code DataSource} that is
     * not Hikari underneath (a proxy, a test double, some future pool) answers {@code false}, so
     * the write is attempted exactly as it was before this method existed — a probe that cannot
     * read the pool must not be the reason an account-critical loss goes unrecorded. The pool being
     * momentarily starved is likewise not remembered anywhere: the next call asks again.
     *
     * <p>That fail-open is also the perfect disguise — a guard permanently unable to unwrap answers
     * {@code false} for ever and looks exactly like one that works — so the premise is asserted
     * twice, once in each place it can be lost. {@code FailedEmailBoundsTest} pins it in CI: this
     * application's {@code DataSource} is <em>the</em> Hikari one behind the
     * {@code EntityManagerFactory}, with a live MXBean. {@link #warnIfTheProbeCannotSeeThePool()}
     * pins it in a deployment nobody here runs, which is where it is actually lost.
     *
     * <p>Not applied to {@link #writeAll}. That runs at shutdown, on no request thread, holding no
     * other connection, once per process, and it is the last chance those rows will ever get.
     */
    public boolean poolIsStarved() {
        HikariPoolMXBean pool = poolMXBean();
        return pool != null && pool.getThreadsAwaitingConnection() > 0;
    }

    /**
     * <strong>Say so at startup when the probe is blind</strong> — because the CI assertion does
     * not travel with the JAR, and a self-hosted instance is exactly where it goes missing.
     *
     * <p>{@code FailedEmailBoundsTest} closes the disguise on our build. It says nothing about a DC
     * operator who sets {@code spring.datasource.type} to another pool, or a platform that injects
     * a tracing agent wrapping the {@code DataSource}: there {@link #poolIsStarved()} is a
     * permanent no-op whose only trace is a {@code DEBUG} line nobody has enabled. That is a
     * self-hosting gap rather than a test gap, so the check ships in the product.
     *
     * <p><strong>WARN is deliberate, against the instinct not to warn at startup.</strong> Same
     * reason {@code StartupMemoryLogger}'s outer catch is a WARN while its inner ones are DEBUG:
     * reaching this means a guard is silently <em>absent</em>, not that a value is unusual. It
     * fires once per process, and only on a deployment that has really lost the guard.
     *
     * <p>On {@link ApplicationReadyEvent} rather than {@code @PostConstruct}, and that is not a
     * style choice. Boot builds the {@code HikariDataSource} without a fast-path pool, so
     * {@code getHikariPoolMXBean()} answers null until the first connection is taken — a
     * construction-time check would warn on any boot where this bean happened to be built before
     * anything opened one, which is a WARN about bean ordering wearing the words of a WARN about
     * the pool.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnIfTheProbeCannotSeeThePool() {
        if (poolMXBean() == null) {
            log.warn("The starvation probe cannot read the connection pool; dead-letter writes "
                     + "will be attempted unguarded. Account-critical mail is still recorded, but "
                     + "a dead-letter write on a committing thread may now queue for a second "
                     + "connection instead of degrading to a log line, which is what bounds "
                     + "mail's contribution to a pool-starvation stall "
                     + "(see FailedEmailWriter#poolIsStarved)");
        }
    }

    /**
     * The single place the {@code DataSource} is unwrapped, shared by the probe and the startup
     * check so the two can never disagree about what "cannot read the pool" means.
     *
     * @return the live pool, or {@code null} when this {@code DataSource} does not expose one
     */
    private HikariPoolMXBean poolMXBean() {
        try {
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (Exception e) {
            // DEBUG on purpose: on a non-Hikari DataSource this is the normal answer and would
            // otherwise be a line per refused email. The consequence of being wrong here is that
            // the write is attempted, which is the old behaviour — and the startup WARN above is
            // what makes that state visible without this line being enabled.
            log.debug("Cannot read the connection pool; the dead-letter write will be attempted "
                      + "as usual", e);
            return null;
        }
    }

    /**
     * The same write for a batch, in <strong>one</strong> transaction — the shutdown residue
     * (HD-207), where the caller is {@code MailTaskExecutor.shutdown()} and the whole path is
     * racing the platform's stop grace.
     *
     * <p>One transaction rather than N is the point. Looping {@link #write} would cost N connection
     * acquisitions at the moment the pool is least likely to hand one over, and would make the
     * shutdown budget asserted in {@code MailAsyncProperties.Async} a function of N rather than a
     * constant plus a row cost. It also collapses N failure modes into one: either the residue is
     * recorded or it is not, and {@code UndeliverableMail} has already logged the count either way.
     *
     * <p>All-or-nothing is the right atomicity here for the same reason. A partial batch would be
     * worse than none — an operator reading {@code failed_email} would have no way to tell which
     * half of a deploy's abandoned mail is in front of them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAll(List<FailedEmail> rows) {
        failedEmailRepository.saveAll(rows);
    }
}
