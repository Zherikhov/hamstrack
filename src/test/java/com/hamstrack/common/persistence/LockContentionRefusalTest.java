package com.hamstrack.common.persistence;

import com.hamstrack.common.exception.GlobalExceptionHandler;
import com.hamstrack.issue.SprintTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>An endpoint that merely waits for a lock is answered 409 with {@code Retry-After}, never
 * the statement budget's 422</strong> (HD-151, security review).
 *
 * <h2>The defect this pins, which the feature nearly shipped</h2>
 * {@code statement_timeout} counts time spent <em>waiting for a lock</em> as part of the statement.
 * So bounding statements without also bounding lock waits would have taken every contended write in
 * the product — an issue edit queued behind a member removal, which {@code docs/self-hosting.md}
 * itself describes as a 30-second operation on a large tenant — and answered it <strong>422
 * {@code STATEMENT_BUDGET_EXCEEDED}, no {@code Retry-After}, "Retrying it unchanged will take just
 * as long"</strong>. Every clause of that is false for this cause: the retry succeeds the moment
 * the holder commits, and a client told not to retry will not.
 *
 * <p>{@code LockTimeout} had seven call sites; the statement bound has all of them. So
 * {@link BoundedJpaTransactionManager} issues <em>both</em> bounds in one round trip, and because
 * {@code DatabaseTimeoutConsistency} refuses to start unless the statement bound is at least twice
 * the lock bound, a pure lock wait always resolves as a lock wait. This test drives that through
 * two real endpoints that call {@code LockTimeout} nowhere.
 *
 * <h2>Why this file cannot also test the 422</h2>
 * It could, until the fix above: the first version of this test produced a statement-budget refusal
 * by blocking a report on a table lock. That is now impossible — deliberately, and by an invariant
 * enforced at startup. The 422 contract therefore needs a statement that is slow for its own sake,
 * and lives in {@code StatementBudgetRefusalTest}.
 *
 * <p>Deterministic in both directions: the holder lets go on a fixed schedule far longer than
 * either bound, so a regression that drops the lock bound does not hang the suite — it produces
 * exactly the 422 this file exists to refuse, and fails an assertion that says so.
 *
 * <h2>What this file is really about, now that it holds three cases</h2>
 * Not "lock waits" but <strong>which refusal a request that lost a race is given</strong>. There
 * are two mechanisms and they arrive at the same 409 from opposite ends: a <em>pessimistic</em>
 * loss, where the database made this request wait and it gave up (carries {@code Retry-After});
 * and an <em>optimistic</em> loss, where somebody else committed first and this request is asked
 * to refresh (carries none). Both are correct today only because the advice binds the untranslated
 * spellings as well as Spring's DAO ones — the single premise behind all three cases, and the one
 * that was false for each of them in turn. The {@code Retry-After} header is what tells the two
 * apart, so every assertion here checks it rather than the status alone.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.locking.lock-timeout-ms=" + LockContentionRefusalTest.LOCK_MS,
        // Exactly the 2x floor DatabaseTimeoutConsistency enforces: the tightest legal pair, which
        // is also where a regression in the ordering of the two shows up soonest.
        "app.persistence.statement-timeout-ms=" + (2 * LockContentionRefusalTest.LOCK_MS)
})
@AutoConfigureMockMvc
class LockContentionRefusalTest extends SprintTestBase {

    /**
     * Long enough that the optimistic case below can hold a row for a second and still be nowhere
     * near it — if a version race were to hit this bound first it would answer the OTHER 409, and
     * the two are told apart by {@code Retry-After}.
     */
    static final int LOCK_MS = 3000;

    /** How long the blocking transaction sits on the table — far longer than either bound. */
    private static final long HOLD_MS = 12_000;

    /**
     * How long the competing edit sits on the child row — bounded on both sides, and neither bound
     * is arbitrary.
     *
     * <p><strong>Lower bound:</strong> the whole delete request (authentication,
     * {@code resolveProject}, {@code findByProjectAndNumber}, {@code findByParent}) has to reach
     * the child {@code UPDATE} inside this window. Overrun it on a loaded box and the editor
     * commits first, the delete loads the new version and succeeds — a {@code 204} the status
     * assertion names. So the flakiness is <strong>one-directional</strong>: this test can fail
     * spuriously, never pass spuriously, which is the only direction worth having.
     *
     * <p><strong>Upper bound:</strong> it must stay well inside {@link #LOCK_MS}, or the delete is
     * refused for waiting and answers the <em>pessimistic</em> 409 instead. That inversion is
     * caught separately by the {@code Retry-After} assertion rather than being left to timing.
     *
     * <p>2 s against a 3 s lock bound: double the window for the request to arrive, a full second
     * of headroom before the wrong mechanism fires.
     */
    private static final long EDIT_HOLD_MS = 2_000;

    // txTemplate comes from ComponentTestBase, up the SprintTestBase chain; re-declaring it here
    // would shadow the inherited field rather than replace it.
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * A read that has to queue behind an exclusive lock on {@code issues}. It is a report only
     * because a report is convenient to block; nothing on this path takes a lock of its own, and
     * that is the point — it is one of the many transactions {@code LockTimeout} never touched.
     */
    @Test
    @Timeout(120)
    void anEndpointThatOnlyWaitsForALockGets409WithRetryAfter() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "something to read");

        var response = whileIssuesAreLocked(
                get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                    + "/reports/aging")
                        .header("Authorization", "Bearer " + ctx.token()));

        assertThat(response.getStatus())
                .as("losing a lock race is the one failure whose entire contract is 'try again'. "
                    + "A 422 here means the lock bound is not being issued on this transaction and "
                    + "statement_timeout — which counts lock-wait time — cancelled the wait "
                    + "instead, handing a retryable failure a status that forbids the retry.")
                .isEqualTo(409);
        assertThat(response.getHeader("Retry-After"))
                .as("and the caller has to be told that in a header, not only in prose")
                .isEqualTo("1");
        assertThat(response.getContentAsString())
                .as("the message stays mechanism-free — a human is reading it")
                .contains(GlobalExceptionHandler.LOCK_CONTENTION_DETAIL)
                .doesNotContain("STATEMENT_BUDGET_EXCEEDED");
    }

    /**
     * The same, on the endpoint that runs its queries against the {@code EntityManager} directly.
     * Covered separately because those two paths produce different exception types for the same
     * database condition — the trap that made the 422 handler need three bindings — so there is no
     * reason to assume the lock spelling behaves alike without asking it.
     */
    @Test
    @Timeout(120)
    void insightsGetsTheSameAnswerThoughItRunsItsQueriesItself() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "something to count");

        var response = whileIssuesAreLocked(
                post("/api/workspaces/" + ctx.wsId() + "/search/insights")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"slice\":\"STATUS\"}"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    /**
     * <strong>The optimistic twin, and why it belongs beside its pessimistic siblings: the same
     * untranslated flush, the same 409, a different exception hierarchy.</strong>
     *
     * <p>{@code handleOptimisticLock} bound only Spring's DAO type, which exists on translated
     * paths. On an explicit {@code entityManager.flush()} Hibernate produces
     * {@code jakarta.persistence.OptimisticLockException} instead — unrelated by type — and the
     * request 500ed on a condition whose entire documented contract is "refresh and retry".
     *
     * <p>Every clause of this fixture is load-bearing, because the reachable path is narrow.
     * {@code SprintScopeLedger.recordDepartureBeforeDelete} holds the only explicit flush in the
     * application; it runs <em>only</em> while deleting an issue that sits in a
     * <strong>started</strong> sprint; and the versioned statement it flushes is the
     * {@code UPDATE} re-pointing that issue's <strong>children</strong> to {@code parent = null}.
     * Delete an issue with no children, or one outside a started sprint, and the conflict moves to
     * commit time — where translation applies and the bug is invisible. The race is a delete
     * against a concurrent <em>edit</em>, not two edits: {@code IssueService.update} pre-checks the
     * client's {@code version} and answers its own 409 before anything flushes.
     *
     * <p>The interleave is made deterministic with a row lock rather than with timing luck. The
     * competing transaction bumps the child's version and sits on the row, so the delete reaches
     * its flush, blocks there, and only then sees the new version — the ordering the race needs,
     * with no hook in production code. It holds for {@link #EDIT_HOLD_MS}, comfortably inside
     * {@link #LOCK_MS}, so the delete waits rather than being refused; if that margin ever inverts
     * this test fails as the <em>pessimistic</em> 409, which the assertions name explicitly.
     */
    @Test
    @Timeout(120)
    void anIssueDeleteThatLosesAVersionRaceGets409RatherThanACrash() throws Exception {
        var ctx = newProject();
        enableEveryCapability(ctx);

        var parent = createIssue(ctx, "the parent");
        var parentId = UUID.fromString(parent.get("id").asText());
        var parentNumber = parent.get("number").asLong();
        var childId = createSubtaskOf(ctx, parentId);
        addIssuesToSprint(ctx, ctx.token(), startedSprint(ctx, "Sprint 1"), parentId)
                .andExpect(status().is2xxSuccessful());

        var editing = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var editor = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    // A colleague editing the child: the version bump is what the delete collides
                    // with, and the still-open transaction is what makes it collide THERE.
                    jdbcTemplate.update(
                            "UPDATE issues SET version = version + 1 WHERE id = CAST(? AS uuid)",
                            childId.toString());
                    editing.countDown();
                    sleep(EDIT_HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "child-editor");
        editor.start();
        assertThat(editing.await(30, TimeUnit.SECONDS))
                .as("the competing edit never reached its UPDATE, so the delete had nothing to "
                    + "race and this test would prove nothing")
                .isTrue();

        var response = mockMvc.perform(delete(issuesUrl(ctx) + "/" + parentNumber)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        editor.join();
        assertThat(failure.get())
                .as("the competing edit itself failed, so whatever the delete met was not it")
                .isNull();

        assertThat(response.getStatus())
                .as("losing a version race is a documented 409 — 'refresh and retry'. If the "
                    + "binding regressed you will not see a 500 here at all: MockMvc rethrows an "
                    + "unhandled exception rather than rendering it, so the failure arrives as an "
                    + "ERROR on the perform() above — jakarta.servlet.ServletException: Request "
                    + "processing failed: jakarta.persistence.OptimisticLockException — and never "
                    + "reaches this line. That type in that message IS the diagnosis: the advice "
                    + "binds only Spring's DAO exception and this flush is not translated. What "
                    + "this assertion catches instead is a 204, which means the delete never met "
                    + "the conflict — the fixture is wrong, not the product.")
                .isEqualTo(409);
        assertThat(response.getHeader("Retry-After"))
                .as("this is the OPTIMISTIC 409 and carries no Retry-After. If one is present, the "
                    + "delete was refused by the lock bound instead — EDIT_HOLD_MS has drifted past "
                    + "LOCK_MS and the test is measuring the other mechanism.")
                .isNull();
        assertThat(response.getContentAsString())
                .as("the optimistic detail, pinned to the constant rather than to a phrase: "
                    + "\"modified by someone else\" also appears in IssueService's own pre-check "
                    + "refusals and in RoleVersionConflictException. None is reachable from this "
                    + "endpoint today, which is exactly the kind of thing that stops being true "
                    + "quietly.")
                .contains(GlobalExceptionHandler.OPTIMISTIC_LOCK_DETAIL);
    }

    /**
     * A Sub-task under {@code parentId}. Written out rather than reusing {@code postIssue}, which
     * hardcodes the Task type: strict adjacency wants level 1 over level 0, and a second
     * {@code typeId} key in the same JSON object would be a duplicate whose winner is a Jackson
     * setting rather than a decision.
     */
    private UUID createSubtaskOf(Ctx ctx, UUID parentId) throws Exception {
        var body = """
                {"title":"the child","typeId":"%s","statusId":"%s","parentId":"%s"}"""
                .formatted(typeIdOf(ctx, "Sub-task"), todoStatusIdOf(ctx), parentId);
        var created = mockMvc.perform(post(issuesUrl(ctx))
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(created).get("id").asText());
    }

    // The Ctx record's own lookups are package-private to com.hamstrack.issue, so this file reads
    // the same config document rather than moving the test away from its siblings.
    private String issuesUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/issues";
    }

    private static String typeIdOf(Ctx ctx, String name) {
        for (var type : ctx.config().get("issueTypes")) {
            if (type.get("name").asText().equals(name)) {
                return type.get("id").asText();
            }
        }
        throw new AssertionError("the project offers no issue type called " + name);
    }

    private static String todoStatusIdOf(Ctx ctx) {
        for (var status : ctx.config().get("statuses")) {
            if (status.get("category").asText().equals("TODO")) {
                return status.get("id").asText();
            }
        }
        throw new AssertionError("the project workflow has no TODO-category status");
    }

    /**
     * Runs {@code request} while another transaction holds {@code ACCESS EXCLUSIVE} on
     * {@code issues}, and asserts on the way out that the request gave up on its own rather than
     * being released by the holder — without which every assertion above could be satisfied by a
     * request that simply waited.
     */
    private MockHttpServletResponse whileIssuesAreLocked(RequestBuilder request) throws Exception {
        var holding = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    // Bounded like every other transaction, which is fine: taking the lock is one
                    // quick statement and the wait afterwards is Java-side, not a statement.
                    jdbcTemplate.execute("LOCK TABLE issues IN ACCESS EXCLUSIVE MODE");
                    holding.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "issues-lock-holder");
        holder.start();
        assertThat(holding.await(30, TimeUnit.SECONDS))
                .as("the blocking transaction never took the table lock, so the request under "
                    + "test never queued and this test would prove nothing")
                .isTrue();

        var startedAt = System.nanoTime();
        var response = mockMvc.perform(request).andReturn().getResponse();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        holder.join();
        assertThat(failure.get())
                .as("the lock holder itself failed, so whatever the request met was not the lock")
                .isNull();
        assertThat(elapsedMs)
                .as("the request took %d ms; the holder does not let go for %d ms, so anything "
                    + "near that means the request was ended by the OTHER transaction finishing "
                    + "— i.e. it was never bounded at all", elapsedMs, HOLD_MS)
                .isLessThan(HOLD_MS - 1000);
        assertThat(elapsedMs)
                .as("it came back in %d ms, faster than the lock bound — then it never queued "
                    + "behind the lock and the refusal under test is some other refusal", elapsedMs)
                .isGreaterThanOrEqualTo(LOCK_MS - 100L);
        return response;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
