package com.hamstrack.report;

import com.hamstrack.common.ratelimit.ConcurrencyLimitedException;
import com.hamstrack.common.ratelimit.ExpensiveReadConcurrencyLimit;
import com.hamstrack.issue.LabelTestBase;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Permit accounting on the expensive-read surface — deterministic, and with no clock in
 * any assertion that decides an outcome</strong> (HD-182, layer 1 of three).
 *
 * <p>Requests are held in flight by a test-only {@link HandlerInterceptor} registered <em>after</em>
 * the throttle interceptors, blocking on a {@link CountDownLatch}. No sleeps, no pool pressure and
 * no timing race: the test waits for arrivals on a semaphore and releases them on a latch, so
 * "two requests are in flight" is a fact rather than a hope.
 *
 * <p><strong>The important assertion in this file is that permits come BACK.</strong> A leaked
 * permit is a permanent, silent capacity loss on a replica — the surface degrades to refusing
 * everything until a restart — so every refusal test here is followed by a request that must
 * succeed, and the release is exercised on the ugly paths as well as the happy one: a handler that
 * throws, and a refusal thrown by a LATER interceptor after this one already took the permit. That
 * last one is the least obvious path in the whole feature, which is why it is verified by test
 * rather than by reading {@code HandlerExecutionChain}.
 *
 * <p><strong>Two budgets are configured asymmetrically on purpose.</strong> The reports budget is
 * effectively off (10000) so the permit tests measure occupancy and nothing else, while the search
 * budget is 2 so {@code POST …/search/insights} — the one real route behind two interceptors — can
 * be refused by the SECOND of them with the first one holding the permit. Every permit test
 * therefore drives {@code …/reports/flow}, which is on the reports budget alone.
 *
 * <p>Layer 2 is {@code ExpensiveReadBulkheadSaturationTest} (a real pool, both directions). Layer 3
 * is a production probe re-run and is deliberately NOT in this suite — see
 * {@code docs/design/expensive-read-concurrency-proposal.md} §10.3.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        "app.reports.requests-per-minute=10000",
        "app.search.requests-per-minute=2",
        "app.expensive-read.max-in-flight-per-principal=2",
        "app.expensive-read.max-in-flight=3",
        "app.expensive-read.acquire-wait-ms=250",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ExpensiveReadConcurrencyTest extends LabelTestBase {

    /** Long enough to be certain, short enough that a genuine hang fails the build quickly. */
    private static final int WAIT_SECONDS = 10;

    @Autowired RequestGate gate;
    @Autowired ExpensiveReadConcurrencyLimit limit;

    @Autowired EntityManagerFactory entityManagerFactory;

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
     * AC-1 — a principal at their own ceiling is refused with {@code TOO_MANY_IN_FLIGHT}, and a
     * DIFFERENT principal in the same state is admitted. The second half is what makes the first a
     * per-principal ceiling rather than a global one.
     */
    @Test
    @Timeout(60)
    void oneMorePersonalRequestIsRefusedWhileADifferentPrincipalIsAdmitted() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        hold(flow(mine), mine.token());
        hold(flow(mine), mine.token());
        gate.awaitArrivals(2);

        long started = System.nanoTime();
        report(flow(mine), mine.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.errorType")
                        .value(ConcurrencyLimitedException.TOO_MANY_IN_FLIGHT))
                .andExpect(jsonPath("$.detail").value(
                        "Too many of your requests are running at once — wait for one to finish."));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs)
                .as("the refusal must arrive after the configured acquire wait (250 ms) and not "
                    + "after some other timeout — a refused caller is refused promptly rather than "
                    + "parked")
                .isLessThan(5_000);

        assertThat(limit.inFlightFor(theirs.owner().getId()))
                .as("the other principal holds nothing yet")
                .isZero();
        report(flow(theirs), theirs.token())
                .andExpect(status().isOk());
    }

    /**
     * AC-2 — the SURFACE ceiling refuses a principal who holds nothing at all, and the sentence
     * says so without naming a count, a tenant or an administrator.
     *
     * <p>Getting this backwards would tell a caller with no requests in flight to reduce their own
     * concurrency: a refusal prescribing an action its reader cannot perform, which this project
     * has shipped three times. The {@code administrator} assertion is the same one
     * {@code statement-timeout-proposal} earned — on Cloud that advice is a dead end.
     */
    @Test
    @Timeout(60)
    void aFullSurfaceRefusesAPrincipalHoldingNothing() throws Exception {
        var first = newProject();
        var second = newProject();
        var third = newProject();
        var newcomer = newProject();

        hold(flow(first), first.token());
        hold(flow(second), second.token());
        hold(flow(third), third.token());
        gate.awaitArrivals(3);

        assertThat(limit.inFlight()).isEqualTo(3);
        assertThat(limit.inFlightFor(newcomer.owner().getId()))
                .as("the refused caller holds NOTHING — which is the whole reason this refusal may "
                    + "not tell them to reduce their concurrency")
                .isZero();

        var body = report(flow(newcomer), newcomer.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.errorType")
                        .value(ConcurrencyLimitedException.EXPENSIVE_SURFACE_BUSY))
                .andReturn().getResponse().getContentAsString();

        // The DETAIL, not the whole envelope: `instance` echoes the caller's own request URI, whose
        // ids are theirs already and are not a disclosure this refusal made.
        var detail = json.readTree(body).get("detail").asText();
        assertThat(detail)
                .as("no count, no tenant, no digit at all: a figure here would vary with other "
                    + "tenants' behaviour and the reader could do nothing with it")
                .isEqualTo("This instance is running as many expensive requests as it can at once. "
                           + "Try again in a moment.")
                .doesNotContainPattern("\\d")
                .doesNotContainIgnoringCase("administrator");
    }

    /**
     * AC-3, and the one that matters most: <strong>releasing the held requests gives the permits
     * back.</strong> If it did not, this surface would refuse everything on this instance until it
     * was restarted, and only the gauge would say why.
     */
    @Test
    @Timeout(60)
    void permitsComeBackWhenTheHeldRequestsFinish() throws Exception {
        var ctx = newProject();

        hold(flow(ctx), ctx.token());
        hold(flow(ctx), ctx.token());
        gate.awaitArrivals(2);
        report(flow(ctx), ctx.token()).andExpect(status().isTooManyRequests());

        gate.open();
        awaitDrained();

        assertThat(limit.inFlight()).isZero();
        report(flow(ctx), ctx.token()).andExpect(status().isOk());
        report(flow(ctx), ctx.token()).andExpect(status().isOk());
        report(flow(ctx), ctx.token()).andExpect(status().isOk());
    }

    /**
     * AC-3 continued — the permit survives a handler that <em>fails</em>. Spring calls
     * {@code afterCompletion} for every interceptor whose {@code preHandle} returned true,
     * including when the dispatch ends in an exception, and this is where that is verified rather
     * than assumed: a 404 raised inside the service, and a 400 raised by argument binding, are the
     * two shapes a report request actually fails in.
     */
    @Test
    @Timeout(60)
    void aFailingHandlerStillReturnsItsPermit() throws Exception {
        var ctx = newProject();

        for (int i = 0; i < 4; i++) {
            report("/api/workspaces/" + UUID.randomUUID() + "/projects/" + UUID.randomUUID()
                   + "/reports/flow", ctx.token())
                    .andExpect(status().isNotFound());
        }
        for (int i = 0; i < 4; i++) {
            report(flow(ctx) + "?from=not-a-date", ctx.token())
                    .andExpect(status().is4xxClientError());
        }

        assertThat(limit.inFlight())
                .as("eight failed requests leaked no permits — if they had, this surface would "
                    + "already be refusing everything on this instance")
                .isZero();
        report(flow(ctx), ctx.token()).andExpect(status().isOk());
    }

    /**
     * AC-3 finished, and this is the least obvious path in the feature: <strong>a LATER
     * interceptor refuses after this one already took the permit.</strong>
     *
     * <p>{@code POST …/search/insights} is the real two-interceptor route — the reports budget runs
     * first (and takes the permit), the search budget second. With the search budget at 2, the
     * third insights request is refused by the second interceptor, at which point the first one's
     * {@code afterCompletion} is the only thing standing between this feature and a permanent
     * capacity loss. {@code HandlerExecutionChain.applyPreHandle} advances its index only for
     * interceptors that returned true and {@code doDispatch} triggers completion for those — true
     * by reading, and verified here because "by reading" is how this repository acquired most of
     * its scars.
     */
    @Test
    @Timeout(60)
    void aRefusalFromTheSECONDInterceptorStillReturnsTheFIRSTOnesPermit() throws Exception {
        var ctx = newProject();

        insights(ctx).andExpect(status().isOk());
        insights(ctx).andExpect(status().isOk());

        insights(ctx)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorType").doesNotExist());

        assertThat(limit.inFlight())
                .as("the reports interceptor took a permit and the SEARCH budget then refused the "
                    + "request; if afterCompletion did not run for an interceptor that never saw "
                    + "the handler, this is where the leak would be")
                .isZero();
        report(flow(ctx), ctx.token()).andExpect(status().isOk());
    }

    /**
     * AC-4 — {@code …/search/insights} sits behind BOTH configurers and holds <strong>one</strong>
     * permit, not two. It occupies one connection, so it costs one permit; charging per interceptor
     * would make a route's occupancy depend on how many patterns happen to match it.
     */
    @Test
    @Timeout(60)
    void insightsHoldsOnePermitNotTwo() throws Exception {
        var ctx = newProject();

        holdPost("/api/workspaces/" + ctx.wsId() + "/search/insights", ctx.token(),
                 "{\"query\":\"\",\"slice\":\"STATUS\"}");
        gate.awaitArrivals(1);

        assertThat(limit.inFlight())
                .as("one request behind two throttle interceptors is ONE permit")
                .isEqualTo(1);
        assertThat(limit.inFlightFor(ctx.owner().getId())).isEqualTo(1);
    }

    /**
     * AC-5 — <strong>the bound costs nothing it is protecting</strong>, in the
     * {@code PermissionResolutionQueryCountTest} shape: an assertion about COST rather than about
     * behaviour.
     *
     * <p>A limiter that needed a connection in order to decide whether to hand out a connection
     * would be self-defeating, and the failure would be invisible — the refusal would still be
     * correct, just twice as expensive as the work it declined. Asserted against the primitive
     * directly rather than through MockMvc, because an authenticated HTTP request costs one
     * statement in the JWT filter before any interceptor runs, and that statement is not this
     * bound's.
     *
     * <p><strong>The connection half is asserted STRUCTURALLY, and the runtime reading it replaced
     * was flaky — measured, not feared.</strong> Comparing
     * {@code HikariPoolMXBean.getActiveConnections()} before and after the refusal passed in
     * isolation and failed in the full suite: the number is process-wide, and a scheduled job that
     * returned a connection during the microseconds of the measurement moved it by one with nothing
     * to do with this bound. A test whose green depends on nothing else in the JVM touching the
     * database is a test that fails for reasons its message does not name — and this file's whole
     * point is that a permit leak must fail LOUDLY and legibly. So the claim is made where it is
     * total rather than sampled: the limiter cannot take a connection because it holds nothing that
     * could reach one. That is stronger than "it did not take one this time".
     */
    @Test
    @Timeout(60)
    void aRefusalIssuesNoStatementAndCannotTakeAConnection() throws Exception {
        var principal = UUID.randomUUID();
        var statistics = statistics();

        var first = limit.acquire(principal);
        var second = limit.acquire(principal);
        try {
            long statementsBefore = statistics.getPrepareStatementCount();

            assertThatThrownBy(() -> limit.acquire(principal))
                    .isInstanceOf(ConcurrencyLimitedException.class);

            assertThat(statistics.getPrepareStatementCount())
                    .as("the occupancy bound issued a statement to decide whether to admit a "
                        + "request — it is two integers in memory and must stay that way")
                    .isEqualTo(statementsBefore);
        } finally {
            limit.release(first);
            limit.release(second);
        }
        assertThat(limit.inFlight()).isZero();

        var reachable = new ArrayList<Class<?>>();
        for (Class<?> type = limit.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                reachable.add(field.getType());
            }
        }
        assertThat(reachable)
                .as("the expensive-read bound holds a reference through which it could reach the "
                    + "very resource it exists to protect. It must stay two counters and a metric "
                    + "sink: a limiter that needs a connection in order to decide whether to hand "
                    + "one out spends the scarce thing twice, and the refusal would still look "
                    + "correct while doing it. %s", reachable)
                .doesNotContain(DataSource.class, EntityManagerFactory.class);
    }

    /**
     * <strong>Releasing twice is a no-op, not extra capacity.</strong> The interceptor releases
     * from whichever terminal callback runs first and both may run, so the idempotence is load
     * bearing in the other direction: a double release would hand the surface more permits than it
     * has and the bulkhead would silently widen with traffic.
     */
    @Test
    @Timeout(60)
    void releasingAPermitTwiceDoesNotWidenTheBulkhead() {
        var principal = UUID.randomUUID();
        var permit = limit.acquire(principal);

        limit.release(permit);
        limit.release(permit);

        assertThat(limit.inFlight()).isZero();
        assertThat(limit.inFlightFor(principal)).isZero();
    }

    // ------------------------------------------------------------------ plumbing

    private String flow(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/reports/flow";
    }

    private ResultActions report(String uri, String token) throws Exception {
        return mockMvc.perform(get(uri).header("Authorization", "Bearer " + token));
    }

    private ResultActions insights(Ctx ctx) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search/insights")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\",\"slice\":\"STATUS\"}"));
    }

    /** Start a request that will park inside the handler chain until the gate opens. */
    private void hold(String uri, String token) {
        inFlight.add(held.submit(() -> mockMvc.perform(get(uri)
                        .header("Authorization", "Bearer " + token)
                        .header(RequestGate.HEADER, "1"))
                .andReturn()));
    }

    private void holdPost(String uri, String token, String body) {
        inFlight.add(held.submit(() -> mockMvc.perform(post(uri)
                        .header("Authorization", "Bearer " + token)
                        .header(RequestGate.HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
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
     * request is one that has already taken its permit. {@code Ordered.LOWEST_PRECEDENCE} puts this
     * configurer after both rate-limit configurers, whose own order is load-bearing for a different
     * reason.
     */
    @TestConfiguration
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class HoldingInterceptorConfig implements WebMvcConfigurer {

        private final RequestGate gate = new RequestGate();

        @Bean
        RequestGate requestGate() {
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
