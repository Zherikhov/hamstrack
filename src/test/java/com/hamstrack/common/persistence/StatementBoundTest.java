package com.hamstrack.common.persistence;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The bound is real, it is applied to a transaction nobody asked to bound, it cannot
 * leak, and the way out of it refuses to be a no-op</strong> (HD-151).
 *
 * <p>Before this, {@code application.properties} bounded {@code lock_timeout} and nothing bounded
 * how long a statement could <em>run</em>, so a query that took one of the ten pooled connections
 * and kept it was unbounded from every direction: a disconnecting client does not abort the
 * server-side statement, Hikari's {@code connectionTimeout} bounds only checking a connection
 * <em>out</em>, and the per-minute throttles bound how often expensive work may <em>start</em>.
 *
 * <p><strong>Proved with a statement that really outruns the bound</strong>, not by reading the
 * setting back. {@code SHOW statement_timeout} would pass against a helper that set the GUC on the
 * wrong connection or in the wrong scope; a {@code pg_sleep} that comes back as an error in a
 * fraction of its own duration cannot. The setting is <em>also</em> read back, but only where the
 * question is "did it revert", which no behavioural assertion can ask cheaply.
 *
 * <p><strong>Nothing here calls anything to opt in</strong>, and that is the design under test:
 * the transaction below is an ordinary {@code TransactionTemplate} transaction of the kind
 * {@code AttachmentService} opens, and it is bounded because
 * {@link BoundedJpaTransactionManager} bounds every transaction the application opens.
 *
 * <p>Deterministic in both directions, in the shape {@code MembershipLockTimeoutTest} uses: the
 * bound is lowered to {@link #BOUND_MS} and the slow statement sleeps far longer, so a regression
 * that removes the bound does not hang the suite — it lets the sleep finish and fails an
 * assertion. The lock bound is lowered with it because {@code DatabaseTimeoutConsistency} refuses
 * a statement bound inside 2× the lock bound, which is itself part of the design.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.locking.lock-timeout-ms=100",
        "app.persistence.statement-timeout-ms=" + StatementBoundTest.BOUND_MS
})
class StatementBoundTest {

    /** Far below the sleeps below, so "was cancelled" and "finished on its own" never blur. */
    static final int BOUND_MS = 1000;

    private static final String SLOW_STATEMENT = "SELECT 1 FROM pg_sleep(4)";
    private static final String SHORTER_BUT_STILL_TOO_SLOW = "SELECT 1 FROM pg_sleep(2)";

    /**
     * PostgreSQL's own words for a statement it killed on {@code statement_timeout}. Asserted on
     * because "it threw" is also true of a syntax error, a dead connection and a session somebody
     * cancelled by hand — and only one of those is this feature doing its job.
     */
    private static final String CANCELLED = "canceling statement due to statement timeout";

    @Autowired StatementTimeout statementTimeout;
    @Autowired TransactionTemplate txTemplate;
    @Autowired EntityManager entityManager;

    /**
     * <strong>The headline.</strong> An ordinary transaction — no annotation, no helper, nothing
     * that knows this feature exists — runs a statement that would take four seconds, and
     * PostgreSQL cancels it after one.
     *
     * <p>Three assertions, each failing differently: the message proves the cancellation came from
     * {@code statement_timeout} rather than from anything else that can end a query; the elapsed
     * time proves the bound is what ended it rather than the sleep finishing; and the lower bound
     * on elapsed time proves the statement really started, so a manager that refused the query
     * outright could not pass this.
     */
    @Test
    @Timeout(60)
    void anOrdinaryTransactionIsBoundedWithoutAskingToBe() {
        var startedAt = System.nanoTime();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                entityManager.createNativeQuery(SLOW_STATEMENT).getSingleResult()))
                .as("a statement that outruns the bound must be cancelled by the database, and "
                    + "the reason must be the timeout — not a connection that died under it")
                .hasStackTraceContaining(CANCELLED);

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs)
                .as("it gave up after %d ms; the statement does not finish for 4000 ms, so "
                    + "anything near that means it ran to completion and the bound never applied",
                        elapsedMs)
                .isLessThan(3500);
        assertThat(elapsedMs)
                .as("it failed in %d ms, faster than the bound itself — then the statement never "
                    + "ran and this test is proving nothing", elapsedMs)
                .isGreaterThanOrEqualTo(BOUND_MS - 50L);
    }

    /**
     * <strong>The control, and the exemption, in one.</strong> Without it the test above would
     * also pass if {@code pg_sleep} were unavailable or if some unrelated bound were killing every
     * long statement in the suite — and the exemption API needs a behavioural proof that it does
     * something rather than a proof that it ran.
     *
     * <p>This is the only supported way for a transaction to run unbounded. The set of application
     * transactions that use it is empty today, on purpose: Flyway and demo seeding both need
     * nothing (see {@link StatementTimeout}), so what ships is the mechanism for the first
     * legitimate long-running job rather than a licence anybody has already taken.
     */
    @Test
    @Timeout(60)
    void anExemptedTransactionRunsTheSameStatementToCompletion() {
        var result = txTemplate.execute(s -> {
            statementTimeout.exemptCurrentTransaction("test: proving the exemption lifts the bound");
            return entityManager.createNativeQuery(SHORTER_BUT_STILL_TOO_SLOW).getSingleResult();
        });

        assertThat(result)
                .as("an exempted transaction must be able to outrun the bound — otherwise the "
                    + "only supported escape hatch does not work and the next long-running job "
                    + "has to raise the global number for every request in the instance")
                .isNotNull();
    }

    /**
     * <strong>Neither the bound nor the exemption may outlive its transaction.</strong>
     * {@code SET LOCAL} is reverted by PostgreSQL at COMMIT, so the pooled connection has to come
     * back carrying the configured bound and not the exemption. The dangerous direction is the
     * second one: an exemption that leaked into the pool would leave <em>later, unrelated</em>
     * transactions unbounded, with nothing to see and no test that would notice — a guard that
     * silently stopped guarding.
     *
     * <p>The sweep is more transactions than {@code spring.datasource.hikari.maximum-pool-size},
     * so a carrier connection cannot hide behind the pool handing out a different one.
     */
    @Test
    @Timeout(60)
    void anExemptionCannotEscapeIntoThePooledConnection() {
        // Read rather than asserted as a literal: PostgreSQL normalises the value it echoes back
        // ("1s" for 1000), and pinning a spelling here would make this test about the formatting
        // of a GUC rather than about whether the bound survives the pool.
        String bounded = txTemplate.execute(s -> currentStatementTimeout());
        assertThat(bounded)
                .as("an ordinary transaction must carry a bound; PostgreSQL reports 0 for none")
                .isNotEqualTo("0");

        txTemplate.executeWithoutResult(s -> {
            statementTimeout.exemptCurrentTransaction("test: the exemption must be visible here");
            assertThat(currentStatementTimeout())
                    .as("inside the exempted transaction the GUC must actually be off — if it is "
                        + "not, the exemption is a no-op that logged a reason")
                    .isEqualTo("0");
        });

        for (int i = 0; i < 20; i++) {
            int attempt = i;
            String afterwards = txTemplate.execute(s -> currentStatementTimeout());
            assertThat(afterwards)
                    .as("transaction %d after an exempted one came back unbounded — SET LOCAL "
                        + "leaked into the pooled connection and every later transaction that "
                        + "lands on it runs with no bound at all", attempt)
                    .isEqualTo(bounded);
        }
    }

    /**
     * <strong>Outside a transaction the exemption must refuse, not shrug.</strong> PostgreSQL
     * downgrades a {@code SET LOCAL} issued outside a transaction block to a WARNING and carries
     * on, so a misplaced call would return normally while exempting nothing — and the caller would
     * discover that as a 422 on a job it believed was unbounded.
     */
    @Test
    void theExemptionRefusesWhereSetLocalWouldBeANoOp() {
        assertThatThrownBy(() -> statementTimeout.exemptCurrentTransaction("no transaction here"))
                .as("an exemption applied outside a transaction is a warning in the server log "
                    + "and a call that did nothing — it has to be an error here instead")
                .isInstanceOf(TransactionException.class);
    }

    /**
     * And it must say why. An exemption that does not explain itself is how the exemption set
     * stops being reviewable, which is the only thing keeping "bound by default" honest.
     */
    @Test
    void theExemptionRefusesWithoutAReason() {
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                statementTimeout.exemptCurrentTransaction("  ")))
                .as("a blank reason must be refused — the log line IS the review")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** What PostgreSQL says the current setting is, e.g. {@code "1s"} or {@code "0"}. */
    private String currentStatementTimeout() {
        return (String) entityManager
                .createNativeQuery("SELECT current_setting('statement_timeout')")
                .getSingleResult();
    }
}
