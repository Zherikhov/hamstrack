package com.hamstrack.common.persistence;

import com.hamstrack.issue.LabelTestBase;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <strong>What a caller is told when a statement is too slow to finish</strong> (HD-151): 422, an
 * {@code errorType} it can branch on, the seconds it was allowed, and no {@code Retry-After}.
 *
 * <h2>Why the slow statement is synthetic, and why that is not a weaker test</h2>
 * The first version of this file produced a real cancellation by blocking a report on a table lock.
 * That stopped working the moment {@link BoundedJpaTransactionManager} began issuing
 * {@code lock_timeout} alongside {@code statement_timeout}: a lock wait now always resolves as a
 * lock wait ({@code LockContentionRefusalTest} pins that, and it is the better outcome — a
 * retryable 409). So there is no longer any way to make a <em>shipped</em> endpoint exceed the
 * statement budget on demand: doing so requires a statement that is slow for its own sake, on data
 * nobody has. The endpoint below is exactly that statement and nothing else.
 *
 * <p>What the synthetic endpoint costs is fidelity of the <em>route</em>, and nothing else, because
 * the three things this file asserts are all route-independent: the bound is applied by the
 * transaction manager to every transaction ({@code StatementBoundCoverageTest} proves that against
 * real endpoints), the cancellation is real PostgreSQL behaviour ({@code StatementBoundTest} proves
 * that), and the refusal is produced by a {@code @RestControllerAdvice} that is bound to exception
 * types rather than to paths. The one thing a shipped endpoint would add is a URL in the log line.
 *
 * <h2>The exception path this drives is the one that actually broke</h2>
 * The service below runs its statement through the {@code EntityManager} directly — the way
 * {@code InsightsService} runs all three of its Criteria aggregates. On that path the JPA spec
 * requires Hibernate to throw a bare {@code jakarta.persistence.PersistenceException} once the
 * transaction is marked for rollback, which a cancellation always does, so the cancellation is
 * visible only as a <em>cause</em>. During development that produced a <strong>500</strong>, and it
 * is why {@code GlobalExceptionHandler.handleQueryTimeout} declares Hibernate's own type as a third
 * binding. Narrow that handler back to Spring's {@code QueryTimeoutException} and this test fails
 * with a 500 rather than passing quietly.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.persistence.statement-timeout-ms=" + StatementBudgetRefusalTest.BUDGET_MS,
        // Lowered only so DatabaseTimeoutConsistency accepts the shortened budget (it requires a
        // 2x margin). Nothing here waits for a lock, so it is not otherwise in play.
        "app.locking.lock-timeout-ms=" + (StatementBudgetRefusalTest.BUDGET_MS / 2)
})
@AutoConfigureMockMvc
class StatementBudgetRefusalTest extends LabelTestBase {

    /** Two seconds, so the humanised sentence in the body has a plural to get right. */
    static final int BUDGET_MS = 2000;

    /** Comfortably longer than the budget, so "cancelled" and "finished" cannot blur. */
    private static final int SLEEP_SECONDS = 8;

    private static final String SLOW_PATH = "/api/test-only/slow-statement";

    @Test
    @Timeout(120)
    void aStatementThatOutrunsTheBudgetIsRefusedWith422AndNoRetryAfter() throws Exception {
        var ctx = newProject();

        var startedAt = System.nanoTime();
        var response = mockMvc.perform(get(SLOW_PATH)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.getStatus())
                .as("a statement the database cancelled is a deliberate refusal, not a crash — and "
                    + "not a 5xx, which is the class intermediaries and SDKs retry automatically, "
                    + "turning one expensive request into an unbounded series of them. A 500 here "
                    + "most likely means the handler no longer declares the exception type this "
                    + "path produces (a PersistenceException wrapping Hibernate's own).")
                .isEqualTo(422);
        assertThat(response.getHeader("Retry-After"))
                .as("an identical retry costs identical time, so a Retry-After would be an "
                    + "instruction that cannot work — the lock 409 carries one, this must not")
                .isNull();
        assertThat(elapsedMs)
                .as("it answered in %d ms; the statement does not finish for %d ms, so anything "
                    + "near that means it ran to completion and the bound never applied",
                        elapsedMs, SLEEP_SECONDS * 1000)
                .isLessThan(SLEEP_SECONDS * 1000L - 1000);

        var body = response.getContentAsString();
        assertThat(body)
                .as("a client must be able to branch on which refusal this is without parsing "
                    + "prose — 422 is a status it already gets for HQL errors")
                .contains("\"errorType\":\"STATEMENT_BUDGET_EXCEEDED\"");
        assertThat(body)
                .as("the body names the budget in human units, the way the report window cap is "
                    + "quoted back inside its own 400")
                .contains("2 seconds");
        assertThat(body)
                .as("and it must stay true for a caller with nothing to narrow — several bounded "
                    + "paths take no parameters at all")
                .contains("larger than this instance is configured to answer");
        assertThat(body)
                .as("it may not dispatch the reader to somebody who cannot help: this sentence "
                    + "read \"ask your administrator\" until review, which is sound on DC — where "
                    + "the administrator owns the .env — and a dead end on Cloud, where the "
                    + "reader's administrator is a workspace owner with no access to this setting. "
                    + "A refusal may only prescribe an action its reader can perform.")
                .doesNotContain("administrator");
        assertThat(body)
                .as("the property and env var are the OPERATOR's copy of this refusal and belong "
                    + "in the WARN log: on a Cloud instance they are tuning surface in front of "
                    + "every tenant. Nor may any SQL, table name or SQLSTATE reach the wire.")
                .doesNotContain("app.persistence")
                .doesNotContain("DB_STATEMENT_TIMEOUT_MS")
                .doesNotContain("statement_timeout")
                .doesNotContain("57014")
                .doesNotContain("pg_sleep");
    }

    /**
     * A statement that is slow for its own sake, reachable over HTTP. Same shape as
     * {@code OptimisticLockConflictContractTest}'s probe controller, which is the house pattern for
     * "drive a real advice with a condition no shipped endpoint can be made to produce on demand".
     *
     * <p>Nested inside the test class on purpose: a nested {@code @TestConfiguration} is part of
     * this test's context key, so the endpoint exists in this context and in no other — it appears
     * in neither {@code ThrottleCoverageTest}'s handler sweep nor the application. (It is visible
     * to {@code VelocityRefusalTest}'s classpath scan of every {@code @RestController}, as that
     * test's own probe already is, and is inert to both of its nets: it returns a {@code String}
     * and its path names no report.)
     *
     * <p>Under {@code /api/**} for the reason that test gives: the request then travels the
     * production filter chain and the production advice, so what is asserted below is what a real
     * caller would receive.
     */
    @TestConfiguration
    static class SlowStatementEndpoint {

        @Bean
        SlowStatementService slowStatementService(EntityManager entityManager) {
            return new SlowStatementService(entityManager);
        }

        @Bean
        SlowStatementController slowStatementController(SlowStatementService service) {
            return new SlowStatementController(service);
        }
    }

    @RestController
    @RequiredArgsConstructor
    static class SlowStatementController {

        private final SlowStatementService service;

        @GetMapping(SLOW_PATH)
        String slow() {
            service.runSlowStatement();
            return "the bound did not fire";
        }
    }

    /**
     * Separate bean, not a method on the controller: {@code @Transactional} is proxy-applied, so a
     * self-invocation would open no transaction, the manager would never bound anything, and the
     * test would pass or fail for reasons unrelated to the feature. Registered by {@code @Bean}
     * and deliberately carrying no stereotype annotation, so it adds nothing to the classpath that
     * another test's component scan could pick up.
     */
    @RequiredArgsConstructor
    static class SlowStatementService {

        private final EntityManager entityManager;

        @Transactional(readOnly = true)
        public void runSlowStatement() {
            entityManager.createNativeQuery("SELECT 1 FROM pg_sleep(" + SLEEP_SECONDS + ")")
                    .getSingleResult();
        }
    }
}
