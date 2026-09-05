package com.hamstrack.report;

import com.hamstrack.issue.LabelTestBase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Shared machinery for the two halves of HD-182's layer-2 measurement — the saturation test and
 * its negative control, which must live in two classes because "the cap is on" is a property of a
 * Spring context.
 *
 * <p>The interceptor here is what makes this layer different from layer 1: it <strong>checks out a
 * real connection from the {@link DataSource}</strong> before parking, so a held request genuinely
 * occupies a permit <em>and</em> a connection, which is the pair the bulkhead's whole argument is
 * about. The connection is closed in a {@code finally} so a failed assertion cannot poison the
 * cached context for every later class.
 */
abstract class BulkheadSaturationBase extends LabelTestBase {

    /**
     * The same size as the suite-wide surefire cap, so this pair of contexts takes no more from the
     * shared local PostgreSQL than any other test class — the point of pinning it here is that a
     * {@code @SpringBootTest(properties = …)} value sits ABOVE that system property, so the number
     * is a fact of this test rather than a default it inherited and could lose.
     */
    static final String POOL_SIZE = "4";

    /**
     * Short enough that the negative control fails in a second and a half rather than at the
     * shipped acquisition bound — the control has to demonstrate starvation, and a starvation that
     * costs seconds per assertion is a starvation nobody puts in a suite. Pinned here rather than
     * inherited for the reason a starved-pool test always pins it: this pair of classes must not
     * change behaviour when the shipped bound is tuned.
     */
    static final String CONNECTION_TIMEOUT_MS = "1500";

    static final int WAIT_SECONDS = 20;

    @Autowired protected RequestGate gate;

    private ExecutorService held;
    private final List<Future<?>> inFlight = new ArrayList<>();

    @BeforeEach
    void resetGate() {
        gate.reset();
        held = Executors.newCachedThreadPool();
    }

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

    protected String flow(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/reports/flow";
    }

    protected ResultActions report(String uri, String token) throws Exception {
        return mockMvc.perform(get(uri).header("Authorization", "Bearer " + token));
    }

    /** Start an expensive request that parks holding one permit and one real connection. */
    protected void holdWithConnection(String uri, String token) {
        inFlight.add(held.submit(() -> mockMvc.perform(get(uri)
                        .header("Authorization", "Bearer " + token)
                        .header(RequestGate.HEADER, "1"))
                .andReturn()));
    }

    /**
     * The status an INTERACTIVE endpoint answers, or {@code -1} when it could not be served at all.
     *
     * <p>{@code -1} is the honest answer for the control: with every connection held, the JWT
     * filter's own user lookup cannot acquire one and the failure escapes the filter chain rather
     * than reaching an {@code @ExceptionHandler} — there is no status because the request never got
     * far enough to have one, which is exactly the state HD-182 exists to delete.
     */
    protected int interactiveStatus(String token) {
        try {
            return mockMvc.perform(get("/api/workspaces")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        } catch (Exception failedToBeServed) {
            return -1;
        }
    }

    /**
     * The latch every held request parks on, the arrival counter the test waits on, and — unlike
     * layer 1 — a real connection held for the duration.
     */
    static final class RequestGate {

        static final String HEADER = "X-Hamstrack-Test-Hold";

        private final DataSource dataSource;
        private volatile CountDownLatch release = new CountDownLatch(1);
        private final Semaphore arrivals = new Semaphore(0);

        RequestGate(DataSource dataSource) {
            this.dataSource = dataSource;
        }

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

        /** Take a connection, announce arrival, park — and give the connection back, always. */
        void parkHoldingAConnection() throws Exception {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.isClosed()).isFalse();
                arrivals.release();
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Registered LAST so a parked request has already passed the throttle interceptors and taken
     * its permit.
     */
    @TestConfiguration
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class HoldingInterceptorConfig implements WebMvcConfigurer {

        private final RequestGate gate;

        HoldingInterceptorConfig(DataSource dataSource) {
            this.gate = new RequestGate(dataSource);
        }

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
                        gate.parkHoldingAConnection();
                    }
                    return true;
                }
            });
        }
    }
}
