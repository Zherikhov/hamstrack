package com.hamstrack.common.persistence;

import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Every transaction the application opens is bounded — asked of the running application,
 * not of a list</strong> (HD-151).
 *
 * <p>This is the test the whole shape exists for. The alternative design bounded a set of call
 * sites that had been <em>measured</em>, and its coverage would have been a property of our
 * measurement history: a path nobody bounded would be silently unbounded, with no log line, no
 * metric and no failing test, discovered when the pool was gone. Bounding at the transaction
 * manager makes coverage a property of the application's structure instead — and this file is what
 * turns that claim into something that can fail.
 *
 * <p><strong>Both bounds are asserted, and the lock one is the assertion that would have caught a
 * real defect.</strong> {@code statement_timeout} counts lock-wait time, so a transaction carrying
 * only the statement bound answers a contended write with 422 and no {@code Retry-After} — a
 * refusal that forbids the retry that would have worked. The seven call sites of
 * {@code LockTimeout} were fine; every other transaction in the product was not, and nothing in
 * this file would have noticed while it read one GUC.
 *
 * <p>It samples the four <em>ways</em> a transaction gets opened here rather than a list of
 * features, because the ways are few and stable while the features are neither: an HTTP read, an
 * HTTP write, a {@link TransactionTemplate} (the shape {@code AttachmentService} uses), and a bare
 * Spring Data repository call made with no surrounding transaction at all. Nothing in the sample
 * was modified for this feature, which is the point — none of them calls anything to opt in.
 *
 * <p>Observation is a {@link TransactionExecutionListener}: at {@code afterBegin} it asks
 * PostgreSQL, on the transaction's own connection, what {@code statement_timeout} is. That is the
 * only vantage point from which the question can honestly be asked, because {@code SET LOCAL} is
 * gone by the time the caller returns — and it means the assertion is about the transactions the
 * application actually opened while serving a real request, not about a transaction the test
 * opened to look at.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        // Distinct from every other suite's property set on purpose: this test mutates the shared
        // transaction manager by registering a listener, and a context of its own is what keeps
        // that out of everybody else's statement counts.
        "hamstrack.test.statement-bound-coverage=true"
})
@AutoConfigureMockMvc
class StatementBoundCoverageTest extends LabelTestBase {

    /**
     * <strong>The checklist, at the one moment a maintainer cannot fail to read it.</strong>
     *
     * <p>If this test fails, the guard has a hole and the hole is silent — an unbounded statement
     * produces no error until it produces an outage. There are only a few ways to open it.
     */
    private static final String CHECKLIST = """


            A transaction reached the database with one of its two bounds missing. Every \
            application transaction is supposed to get BOTH at doBegin — statement_timeout so \
            that no single statement runs unbounded, and lock_timeout so that a contended write \
            is refused as a retryable 409 rather than cancelled by the statement bound and \
            answered a 422 that forbids the retry. So exactly one of these is true:

              1. BoundedJpaTransactionManager is no longer THE PlatformTransactionManager. Boot's
                 JpaBaseConfiguration#transactionManager is @ConditionalOnMissingBean, so anything
                 that stops our bean from being registered — a @Primary manager, a test slice, a
                 conditional — silently restores Boot's unbounded one. This is the likeliest cause.
              2. A second transaction manager was introduced (a ChainedTransactionManager, a
                 dedicated one for a second DataSource, a JTA manager). It has to extend or wrap
                 BoundedJpaTransactionManager, or everything it manages is unbounded.
              3. doBegin stopped issuing the SET — most plausibly because the EntityManagerFactory
                 stopped exposing its DataSource, in which case there is no ConnectionHolder to
                 write to and the manager throws rather than continuing quietly.
              4. Somebody exempted a path with StatementTimeout.exemptCurrentTransaction. That is
                 supported, but it is meant to be rare and deliberate: grep for it, read the
                 reason it logs, and decide whether the exemption or the test is wrong.

            The one thing that must NOT be done is bounding the paths this test samples. The \
            sample is four WAYS of opening a transaction, not four features; a fix that makes \
            these four green by touching them is a fix that leaves everything else unbounded.
            """;

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TransactionTemplate txTemplate;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;

    private BoundRecorder recorder;

    @BeforeEach
    void registerRecorder() {
        recorder = new BoundRecorder(dataSource);
        ((AbstractPlatformTransactionManager) transactionManager)
                .setTransactionExecutionListeners(List.of(recorder));
    }

    @AfterEach
    void removeRecorder() {
        ((AbstractPlatformTransactionManager) transactionManager)
                .setTransactionExecutionListeners(List.of());
    }

    /**
     * An HTTP read and an HTTP write, through unmodified controllers and services. Between them
     * they cover the ordinary {@code @Transactional} service method in both directions — and the
     * write matters as much as the read, because the statements whose cost is a whole tenant are
     * not all queries (a workspace-wide unassign is one {@code UPDATE}).
     */
    @Test
    void everyTransactionAnHttpRequestOpensIsBounded() throws Exception {
        var ctx = newProject();

        recorder.clear();
        var issues = "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/issues";
        mockMvc.perform(get(issues).header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
        assertBounded("a read request (GET issues)");

        recorder.clear();
        createIssue(ctx, "an ordinary write");
        assertBounded("a write request (POST issues)");
    }

    /**
     * The two ways a transaction is opened <em>without</em> an {@code @Transactional} service
     * method: a {@link TransactionTemplate}, which is how {@code AttachmentService} brackets the
     * work around a blob write, and a bare repository call, which opens its own transaction
     * because {@code SimpleJpaRepository} is itself {@code @Transactional}. The second is the one
     * an opt-in design could not have covered at all — there is no method anywhere to annotate.
     */
    @Test
    void aTransactionTemplateAndABareRepositoryCallAreBoundedToo() {
        recorder.clear();
        txTemplate.executeWithoutResult(status -> userRepository.count());
        assertBounded("a TransactionTemplate transaction");

        recorder.clear();
        userRepository.count();
        assertBounded("a bare Spring Data repository call outside any transaction");
    }

    /**
     * <strong>Flyway must stay unbounded, and the mechanism is what is asserted.</strong>
     *
     * <p>A migration legitimately runs for minutes — an index build on {@code issues}, a FK
     * validation, {@code V11}'s whole-table {@code position} rescale — and it is exempt here by
     * construction: Flyway takes its own connections and runs its own transactions, so it never
     * touches the transaction manager. That exemption is worth exactly as much as the reason it
     * rests on, so what is pinned is the reason: the moment a bound is moved <em>below</em> the
     * transaction manager — a {@code connection-init-sql}, a JDBC-URL option, an
     * {@code ALTER ROLE} default — Flyway inherits it and a migration on a large install starts
     * failing at ten seconds. That is precisely what {@code LockTimeout}'s javadoc rejects for the
     * lock bound, and it is the trap this design was chosen to avoid.
     */
    @Test
    void nothingBoundsTheDatasourceItself() {
        assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
                .as("a session-level bound on the shared datasource would be inherited by every "
                    + "Flyway migration, which is exactly the thing the transaction-manager shape "
                    + "exists to avoid. If a connection-init-sql is genuinely needed, Flyway needs "
                    + "its own DataSource first, and the Flyway exemption stops being free.")
                .isNull();
    }

    private void assertBounded(String what) {
        assertThat(recorder.observed())
                .as("%s opened no transaction at all, so this assertion is guarding nothing", what)
                .isNotEmpty();
        assertThat(recorder.observed())
                .as("%s ran with an unbounded statement_timeout.%s", what, CHECKLIST)
                .allSatisfy(bounds -> assertThat(bounds.statementTimeout()).isNotEqualTo(UNBOUNDED));
        assertThat(recorder.observed())
                .as("%s ran with an unbounded lock_timeout, so a contended write on this path "
                    + "waits until statement_timeout cancels it and is then answered 422 with no "
                    + "Retry-After — telling a caller that a retry is pointless when it succeeds "
                    + "the moment the holder commits. Both bounds are issued together by "
                    + "BoundedJpaTransactionManager for exactly this reason, and LockTimeout's "
                    + "seven call sites are a re-assert, not the source.%s", what, CHECKLIST)
                .allSatisfy(bounds -> assertThat(bounds.lockTimeout()).isNotEqualTo(UNBOUNDED));
    }

    /** What PostgreSQL reports when there is no bound. */
    private static final String UNBOUNDED = "0";

    /** Both per-transaction bounds, as the database reports them from inside the transaction. */
    private record Bounds(String statementTimeout, String lockTimeout) {}

    /**
     * Asks, from inside each transaction as it begins, what the database thinks its statement
     * bound is. Registered on the transaction manager rather than woven into the application,
     * because the property under test is about transactions the application opened for its own
     * reasons — a test that opened its own would be asserting about itself.
     */
    private record BoundRecorder(DataSource dataSource, List<Bounds> settings)
            implements TransactionExecutionListener {

        BoundRecorder(DataSource dataSource) {
            this(dataSource, new CopyOnWriteArrayList<>());
        }

        @Override
        public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
            if (beginFailure != null) {
                return;
            }
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            if (holder == null) {
                // No JDBC connection bound means the manager could not have issued the SET either;
                // record it as unbounded so the assertion fails rather than the sample shrinking.
                settings.add(new Bounds(UNBOUNDED, UNBOUNDED));
                return;
            }
            try (var statement = holder.getConnection().createStatement();
                 var rows = statement.executeQuery(
                         "SELECT current_setting('statement_timeout'), "
                         + "current_setting('lock_timeout')")) {
                settings.add(rows.next()
                        ? new Bounds(rows.getString(1), rows.getString(2))
                        : new Bounds(UNBOUNDED, UNBOUNDED));
            } catch (SQLException ex) {
                throw new IllegalStateException("could not read the transaction's bounds", ex);
            }
        }

        void clear() {
            settings.clear();
        }

        List<Bounds> observed() {
            return List.copyOf(settings);
        }
    }
}
