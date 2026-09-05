package com.hamstrack.issue;

import com.hamstrack.common.ratelimit.ExpensiveReadConcurrencyLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The planning surface takes permits from the expensive-read share, one per request, and
 * gives them back on every terminal path</strong> (HD-174, AC-8; the
 * {@code ExpensiveReadConcurrencyTest} shape applied to the surface that joined the share).
 *
 * <p>This is the more important half of HD-174. {@code BacklogService.view} is
 * {@code @Transactional(readOnly = true)} over the whole method, so one Hikari connection is held
 * across {@code 12 + N} statements — 32 at {@code AGILE_MAX_OPEN_SPRINTS=20};
 * {@code DB_STATEMENT_TIMEOUT_MS} bounds each of them and nothing bounds their sum, so the
 * worst-case hold for one planning aggregate is ~320 s, while everybody else waits out the pool's
 * acquisition bound and then fails. A rate budget provably cannot bound that: it spends the same unit whether a
 * request takes 8 ms or 8 s.
 *
 * <p><strong>The important assertion in this file is that permits come BACK.</strong> A leaked
 * permit is a permanent, silent capacity loss on a replica — the surface degrades to refusing
 * everything until a restart — so every refusal test is followed by a request that must succeed.
 *
 * <p>Requests are held in flight by a test-only {@link HandlerInterceptor} registered
 * <em>after</em> the throttle interceptors, blocking on a {@link CountDownLatch}: no sleeps and no
 * timing race, so "a request is in flight" is a fact rather than a hope. It parks in
 * {@code preHandle}, i.e. before the handler opens a transaction, so a held request costs a permit
 * and no connection — which is the point, since it is the permit's accounting under test.
 *
 * <p>The ceilings are deliberately tiny (1 per principal, 2 for the surface) so the properties are
 * observable; the shipped numbers are 3 and 6, derived from the pool by {@code ExpensiveReadShare}.
 * <strong>No number in HD-174 has to sit below {@code DB_POOL_MAX_SIZE}</strong> — ADR-0031 — which
 * is why this file configures the EXISTING {@code app.expensive-read.*} dials and there is no
 * {@code app.planning.max-in-flight} to configure.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.planning.requests-per-minute=4",
        "app.expensive-read.max-in-flight-per-principal=1",
        "app.expensive-read.max-in-flight=2",
        "app.expensive-read.acquire-wait-ms=250",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PlanningConcurrencyTest extends LabelTestBase {

    /** Long enough to be certain, short enough that a genuine hang fails the build quickly. */
    private static final int WAIT_SECONDS = 10;

    @Autowired RequestGate gate;
    @Autowired ExpensiveReadConcurrencyLimit limit;

    private ExecutorService held;
    private final List<Future<?>> inFlight = new ArrayList<>();

    @BeforeEach
    void openTheGate() {
        gate.reset();
        held = Executors.newCachedThreadPool();
    }

    /**
     * Nothing may stay parked between tests: a held request still holds a permit, and a class whose
     * first failure poisons every later assertion tells you nothing about which one broke.
     */
    @AfterEach
    void releaseEverything() throws Exception {
        gate.open();
        for (var future : inFlight) {
            try {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a held request that failed is the test's business, not the teardown's
            }
        }
        inFlight.clear();
        held.shutdownNow();
        assertThat(held.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * <strong>A planning read occupies exactly one permit, and the ceiling is per principal.</strong>
     *
     * <p>The second half is what makes it a per-principal ceiling rather than a global one, and it
     * is the half that matters to the trade ADR-0031 accepted: a planning-heavy team can be the
     * reason a colleague's report is refused, but no single planner can ever be the whole cause.
     */
    @Test
    @Timeout(60)
    void aPlanningReadHoldsOnePermitAndTheCeilingIsPerPrincipal() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        hold(backlogUri(mine), mine.token());
        gate.awaitArrivals(1);

        assertThat(limit.inFlightFor(mine.owner().getId()))
                .as("a planning read must take exactly ONE permit — not zero (the surface would be "
                    + "outside the bulkhead while every document says it is inside) and not two "
                    + "(the per-principal ceiling would be half what it says)")
                .isEqualTo(1);

        backlog(mine)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.errorType").value("TOO_MANY_IN_FLIGHT"));

        // A different principal in the same instant is admitted: the surface has 2.
        backlog(theirs).andExpect(status().isOk());
    }

    /**
     * <strong>The permit comes back on normal completion.</strong> Without this the bound is not a
     * bulkhead but a countdown to a restart.
     */
    @Test
    @Timeout(60)
    void permitsComeBackWhenTheHeldRequestFinishes() throws Exception {
        var ctx = newProject();

        hold(backlogUri(ctx), ctx.token());
        gate.awaitArrivals(1);
        backlog(ctx).andExpect(status().isTooManyRequests());

        gate.open();
        awaitDrained();

        assertThat(limit.inFlightFor(ctx.owner().getId())).isZero();
        backlog(ctx).andExpect(status().isOk());
    }

    /**
     * <strong>A handler that refuses still returns its permit.</strong> A planning read against a
     * workspace the caller cannot see is a 404 raised from inside the handler, so the release has
     * to happen on the exception path — {@code afterCompletion} runs for it, and if it did not, a
     * scanner would drain the share by asking for things that do not exist.
     */
    @Test
    @Timeout(60)
    void aRefusingHandlerStillReturnsItsPermit() throws Exception {
        var ctx = newProject();

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID() + "/projects/"
                                + UUID.randomUUID() + "/backlog")
                            .header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isNotFound());
        }

        assertThat(limit.inFlight())
                .as("four 404s left permits behind: the release does not happen on the handler's "
                    + "exception path, so anyone can drain this share by asking for workspaces "
                    + "that do not exist")
                .isZero();
    }

    /**
     * <strong>Rate first, permit last — so a request the budget refuses never held a share of the
     * connection pool</strong> (AC-8's third path).
     *
     * <p>Asserted through the refusal a caller can actually observe rather than through a counter,
     * which is what makes it a test of the ORDER: the surface is deliberately full while the
     * refused principal's own minute budget is also exhausted. If the permit were taken first, the
     * answer would be {@code EXPENSIVE_SURFACE_BUSY} after a 250 ms wait on a full surface; because
     * the budget is spent first, the answer is the budget's own 429 — no {@code errorType} —
     * immediately, and the two held permits are untouched.
     */
    @Test
    @Timeout(60)
    void aBudgetRefusalIsAnsweredBeforeAnyPermitIsTaken() throws Exception {
        var exhausted = newProject();
        var holderA = newProject();
        var holderB = newProject();

        // Spend the whole planning budget of one principal on requests that complete.
        for (int i = 0; i < 4; i++) {
            backlog(exhausted).andExpect(status().isOk());
        }

        // Fill the surface with two OTHER principals, one permit each.
        hold(backlogUri(holderA), holderA.token());
        hold(backlogUri(holderB), holderB.token());
        gate.awaitArrivals(2);
        assertThat(limit.inFlight()).isEqualTo(2);

        backlog(exhausted)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorType").doesNotExist());

        assertThat(limit.inFlight())
                .as("a request the RATE budget refused must not have taken, waited for, or leaked "
                    + "a permit — the ordering is PrincipalThrottleInterceptor's and is inherited, "
                    + "not re-implemented here")
                .isEqualTo(2);
    }

    /**
     * <strong>The section reads are on the same share as the aggregate, and take one permit
     * each.</strong>
     *
     * <p>The parity property in its occupancy denomination: a surface where the cheap request is
     * bounded and the expensive one is not — or the reverse — is worse than either whole answer,
     * because the client refused on one falls back to the other.
     */
    @Test
    @Timeout(60)
    void aSectionReadTakesAPermitFromTheSameShare() throws Exception {
        var ctx = newProject();

        hold(backlogUri(ctx) + "/sections/backlog", ctx.token());
        gate.awaitArrivals(1);

        assertThat(limit.inFlightFor(ctx.owner().getId())).isEqualTo(1);

        // The AGGREGATE is refused by the permit the SECTION read is holding: one share, not two.
        backlog(ctx)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorType").value("TOO_MANY_IN_FLIGHT"));
    }

    // ------------------------------------------------------------------ plumbing

    private String backlogUri(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/backlog";
    }

    private ResultActions backlog(Ctx ctx) throws Exception {
        return mockMvc.perform(get(backlogUri(ctx))
                .header("Authorization", "Bearer " + ctx.token()));
    }

    /** Start a request that will park inside the handler chain until the gate opens. */
    private void hold(String uri, String token) {
        inFlight.add(held.submit(() -> mockMvc.perform(get(uri)
                        .header("Authorization", "Bearer " + token)
                        .header(RequestGate.HEADER, "1"))
                .andReturn()));
    }

    /** Wait for every held request to have finished — the permits are back only when it has. */
    private void awaitDrained() throws Exception {
        for (var future : inFlight) {
            var result = (MvcResult) future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
        inFlight.clear();
    }

    /**
     * The latch every held request parks on, and the arrival counter the test waits on. A bean
     * rather than a static so it is scoped to this context, and resettable because the context is
     * cached across the methods of this class.
     */
    static final class RequestGate {

        static final String HEADER = "X-Hamstrack-Test-Hold";

        private volatile CountDownLatch release = new CountDownLatch(1);
        private final Semaphore arrivals = new Semaphore(0);

        void reset() {
            release.countDown();
            release = new CountDownLatch(1);
            arrivals.drainPermits();
        }

        void open() {
            release.countDown();
        }

        void awaitArrivals(int count) throws InterruptedException {
            if (!arrivals.tryAcquire(count, WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError(count + " held requests never reached the gate");
            }
        }

        void park() throws InterruptedException {
            arrivals.release();
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Registered LAST, deliberately: it has to sit behind the throttle interceptors so a parked
     * request is one that has already taken its permit.
     */
    @TestConfiguration
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class HoldingInterceptorConfig implements WebMvcConfigurer {

        private final RequestGate gate = new RequestGate();

        @Bean
        RequestGate planningRequestGate() {
            return gate;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                         Object handler) throws Exception {
                    if (request.getHeader(RequestGate.HEADER) != null) {
                        gate.park();
                    }
                    return true;
                }
            });
        }
    }
}
