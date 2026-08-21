package com.hamstrack.common.persistence;

import com.hamstrack.common.config.LockingProperties;
import com.hamstrack.common.config.StatementTimeoutProperties;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <strong>Every transaction this application opens is bounded</strong> (HD-151): the manager
 * issues {@code SET LOCAL statement_timeout} as part of {@code doBegin}, before any application
 * statement can run.
 *
 * <h2>Why here, and not at a list of expensive call sites</h2>
 * {@code application.properties} bounded {@code lock_timeout} and nothing bounded how long a
 * statement may <em>run</em>, so a query that took one of the ten pooled connections and simply
 * kept it was unbounded from every direction at once: a client that disconnects does not abort
 * the server-side statement, Hikari's {@code connectionTimeout} bounds only <em>checking a
 * connection out</em>, and the per-principal throttles bound how often expensive work may
 * <em>start</em>, never how long it runs.
 *
 * <p>The opt-in shape {@link LockTimeout} uses is right for locks and wrong here, and the
 * difference is not a matter of taste. A lock wait is <strong>structural</strong>: a transaction
 * that takes no lock and writes no contended row cannot queue, and that is provable by reading
 * the code. A slow statement is <strong>volumetric</strong>: its cost is a function of how much
 * data a tenant has, and no static property of a query proves it fast. There is no code-reading
 * procedure that yields the set of statements that will never be slow — so an opt-in list would
 * describe what we have measured, not what is at risk, and it would <em>look</em> complete,
 * which is worse than looking absent. The evidence is in this repository: the lock bound's own
 * enumeration was deleted from {@code LockTimeout} for rotting and promptly grew back in
 * {@code docs/self-hosting.md}, which listed five bounded paths while there were seven.
 *
 * <p>So the participants are not enumerated; the <strong>exemptions</strong> are, and that set is
 * small, deliberate, and defined by a property rather than by a census. See
 * {@link StatementTimeout#exemptCurrentTransaction(String)} — and note that it is
 * <strong>empty in application code today</strong>, which is a feature of this shape rather than
 * an oversight.
 *
 * <h2>Why the transaction manager is the right boundary</h2>
 * It is <em>already</em> the line separating work the application does from work Flyway does.
 * Flyway takes its own connections from the {@code DataSource} and runs its own transactions,
 * never touching this bean, so "everything the application does is bounded, and migrations are
 * not" becomes expressible <strong>without a list</strong> — and a migration genuinely needs the
 * unbounded budget (a {@code UNIQUE} index build on {@code issues}, a FK validation, {@code V11}'s
 * whole-table {@code position} rescale). Every boundary below this one — the datasource, the JDBC
 * URL, the login role, {@code postgresql.conf} — catches Flyway too, which is exactly what
 * {@code LockTimeout}'s javadoc rejects for {@code connection-init-sql}. That rejection is the
 * reason this class exists rather than a one-line property.
 *
 * <p>Three consequences worth stating, because each is a hole this shape does <em>not</em> have:
 * a {@code TransactionTemplate} resolves through the same bean and is covered; a bare Spring Data
 * repository call opens its own transaction through this bean (({@code SimpleJpaRepository} is
 * {@code @Transactional})) and is covered, so there is no "outside" to forget; and the
 * {@code SET} is raw JDBC, so it is invisible to Hibernate's {@code Statistics} and the
 * {@code *QueryCountTest} family keeps asserting the same absolute numbers. Issuing it the way
 * {@code LockTimeout} does — {@code entityManager.createNativeQuery} — would move every one of
 * those counts by one and read like a regression.
 *
 * <h2>It issues BOTH bounds, and that is a deliberate behaviour change</h2>
 * The same {@code SET LOCAL} carries {@code lock_timeout} (from {@code app.locking}). Without it
 * this class would have <em>created</em> the defect it exists to prevent: {@code statement_timeout}
 * counts time spent waiting for a lock, so an ordinary contended write — an issue edit queued
 * behind a member removal, say — would have been cancelled at the statement bound and answered
 * <strong>422 with no {@code Retry-After}</strong>, telling a caller that a retry is pointless when
 * in truth it succeeds the moment the holder commits. A refusal wider than its condition, shipped
 * by the ticket about refusals wider than their condition.
 *
 * <p>So: a contended write that used to wait indefinitely now fails after
 * {@code app.locking.lock-timeout-ms} with the <strong>retryable 409 + {@code Retry-After}</strong>
 * this codebase already documents for lock contention — 55P03 reaches
 * {@code GlobalExceptionHandler.handlePessimisticLock}, which binds the DAO superclass globally
 * and needs no change. The ordering is not luck: {@code DatabaseTimeoutConsistency} refuses to
 * start unless the statement bound is at least twice the lock bound, so for a pure lock wait the
 * lock bound always fires first, and it does so now in <em>every</em> transaction rather than in
 * the seven that call {@code LockTimeout} by hand.
 *
 * <p>{@link LockTimeout} therefore survives as an <strong>explicit re-assert</strong> on those
 * seven paths rather than as the only source of the bound. That is not redundancy worth deleting:
 * it is {@code MANDATORY}-propagated and asserted, so it fails loudly if a locking path is ever
 * moved outside a transaction, and it is where the reasoning about lock ordering lives.
 *
 * <h2>What it does NOT bound — do not read this class as "a connection can no longer be pinned"</h2>
 * {@code statement_timeout} bounds <strong>one statement</strong>. A transaction of 100 statements
 * at half the budget holds a connection for fifty times it, and a transaction that spends its time
 * in <em>Java</em> — {@code ReportCsvService} assembles a whole CSV inside its
 * {@code @Transactional} — is {@code idle in transaction} and is not touched by this setting at
 * all. The complementary control is {@code idle_in_transaction_session_timeout} and it is
 * deliberately not shipped: it would also kill legitimate app-side assembly and needs its own
 * measurement. What this deletes is the <em>unbounded</em> statement, not the connection hold.
 * (The same shape as {@code LockTimeout}'s "it bounds waiting for a lock, not holding one".)
 *
 * <p><strong>A cancellation adds a cause, never a class.</strong> A bounded transaction that has
 * already published an effect <em>outside</em> the database can be left half-done by the
 * cancellation exactly as it can by any other rollback. The category is <strong>any effect
 * published before commit</strong>, and it has two members' worth of shape today: a blob written
 * to storage ahead of the row that names it ({@code AttachmentService}, which already logs the
 * orphan it can leave), and <strong>every {@code @Async} hand-off made from inside a live
 * transaction</strong> — the mail sends are the ones that exist, queued to the executor before the
 * token row they describe is flushed, so a rollback of any kind delivers a link whose row never
 * existed. Both fail closed, both predate this class, and neither is created by it; what this adds
 * is one more <em>cause</em> of a rollback. The mail hand-offs are open work — <strong>HD-181</strong>,
 * which defers them to after commit using the shape
 * {@code AttachmentService.deleteFromStorageAfterCommit} already uses — so the category above
 * points at a ticket rather than implying there is nothing to do. Stated as the category on
 * purpose: the previous wording named the blob write as "the one such path today" and was already
 * wrong when written, which is the failure mode this file argues against everywhere else.
 *
 * <p><strong>And nothing below the exception advice may catch the cancellation and carry on.</strong>
 * After SQLSTATE {@code 57014} the transaction is aborted, so the next statement on it fails with
 * {@code 25P02 in_failed_sql_transaction} — a second, confusing error naming neither the cause nor
 * the budget. It belongs to {@code GlobalExceptionHandler.handleQueryTimeout} and to nobody else.
 * <strong>The tree has one place that has to obey this deliberately</strong> —
 * {@code DemoDataService.bestEffort}, which absorbs a failure in each of five optional showcase
 * steps inside one transaction and now re-throws a dead transaction first. Read that as the worked
 * example, not as the census: the rule applies to any {@code catch} broad enough to include a
 * {@code RuntimeException} that arrived from the database, and
 * {@link StatementTimeout#isStatementCancellation} is the shared answer to "is this that?".
 *
 * <h2>Replacing Boot's manager</h2>
 * Boot's {@code JpaBaseConfiguration#transactionManager} is {@code @ConditionalOnMissingBean(
 * TransactionManager.class)}, so declaring this one backs it off — and the replacement therefore
 * has to re-apply {@link TransactionManagerCustomizers} by hand, or every Boot-level transaction
 * setting ({@code spring.transaction.*}) silently stops being applied. That is the one
 * easy-to-miss step of this shape, which is why it is done in the constructor next to a sentence
 * saying so.
 */
@Component(BoundedJpaTransactionManager.BEAN_NAME)
public class BoundedJpaTransactionManager extends JpaTransactionManager {

    /**
     * <strong>The bean name is load-bearing — do not let it default to the class name.</strong>
     * {@code @Transactional} resolves a manager by type and would not care, but Spring Data JPA's
     * repository factory resolves one by <em>name</em> ({@code transactionManagerRef}, default
     * {@code "transactionManager"}), which is the name Boot's own {@code @Bean} method gave the
     * manager this class replaces. Named anything else, the context starts perfectly and then
     * <em>every repository call in the application</em> fails with "No bean named
     * 'transactionManager' available" — at request time, not at boot. Caught by any test that
     * seeds through a repository outside a transaction, which is most of them (renaming this
     * constant in an isolated copy turned five sampled classes red, including
     * {@code OnboardingFlowTest}, {@code WorkspaceCreationTest} and
     * {@code PermissionResolutionQueryCountTest}).
     */
    static final String BEAN_NAME = "transactionManager";

    /**
     * <strong>Both bounds, one string, one round trip.</strong> pgjdbc sends a multi-statement
     * string in simple query mode, so this costs the same as setting either alone — which is what
     * makes "issue both, always" affordable enough to be unconditional.
     *
     * <p>The values are inlined rather than bound, because this runs on a plain {@link Statement}
     * at transaction begin: there is no user input anywhere near it — both are {@code int}s this
     * process validated at startup — and {@code SET} takes no parameters in PostgreSQL anyway, so
     * a {@link java.sql.PreparedStatement} would buy a parse-and-plan round trip and nothing else.
     * Milliseconds are unitless on purpose: PostgreSQL reads a bare number for either GUC as
     * milliseconds, so nothing has to quote a unit.
     */
    private static final String SET_BOUNDS =
            "SET LOCAL statement_timeout = %d; SET LOCAL lock_timeout = %d";

    private final String setBounds;

    public BoundedJpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                        ObjectProvider<TransactionManagerCustomizers> customizers,
                                        StatementTimeoutProperties statementTimeout,
                                        LockingProperties locking) {
        super(entityManagerFactory);
        // Formatted once at construction: both values are ints this process validated at startup,
        // and neither can change without a restart.
        this.setBounds = SET_BOUNDS.formatted(
                statementTimeout.statementTimeoutMs(), locking.lockTimeoutMs());
        // Boot applies these to the manager it would have created; this one is that manager.
        customizers.ifAvailable(c -> c.customize(this));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The bound is applied after {@code super.doBegin}, which is where the JDBC connection for
     * this transaction is acquired and exposed — {@code JpaTransactionManager} binds a
     * {@link ConnectionHolder} for the {@code DataSource} it takes from the
     * {@code EntityManagerFactory}, so the connection this {@code SET LOCAL} lands on is
     * provably the one every statement of this transaction will use.
     *
     * <p><strong>The failure path is the interesting one.</strong> {@code super.doBegin} wraps its
     * own body in a catch that closes the half-opened {@code EntityManager}; code after it is
     * outside that net, so a failure here — a connection that died between acquisition and this
     * statement — would leave an {@code EntityManagerHolder} bound to a pooled request thread and
     * poison every later request that thread serves. {@link #doCleanupAfterCompletion} is exactly
     * the unbind-and-close the manager performs at the end of a transaction, and calling it here
     * is what makes a failed bound a failed <em>begin</em> rather than a leak.
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        try {
            applyBounds();
        } catch (RuntimeException | Error ex) {
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException(
                    "Could not bound the statement time of the new transaction", ex);
        }
    }

    private void applyBounds() {
        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            // Only reachable if the EntityManagerFactory stops exposing its DataSource, which
            // would also mean nothing else in the app can see the JDBC connection. Loud, because
            // the alternative is an application that silently runs unbounded again.
            throw new IllegalStateException(
                    "The JPA transaction manager exposes no DataSource, so no transaction can be "
                    + "bounded. Every statement would run unbounded — refusing to begin.");
        }
        var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        if (holder == null) {
            throw new IllegalStateException(
                    "No JDBC connection was bound for the transaction just begun, so "
                    + "SET LOCAL statement_timeout has nowhere to go and every statement would "
                    + "run unbounded — refusing to begin.");
        }
        try (Statement statement = holder.getConnection().createStatement()) {
            // Both GUCs in one string, therefore one round trip: pgjdbc sends a multi-statement
            // string in simple query mode. Two calls would double the per-transaction cost of
            // this class for no benefit.
            statement.execute(setBounds);
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Could not bound this transaction's statement and lock time", ex);
        }
    }
}
