package com.hamstrack.report;

import com.hamstrack.common.ratelimit.ConcurrencyLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The bulkhead, against a REAL connection pool</strong> (HD-182, layer 2 of three, AC-6 —
 * the positive direction).
 *
 * <p>Layer 1 ({@code ExpensiveReadConcurrencyTest}) proves the accounting with no pool involved.
 * This proves the thing the accounting exists for: while the expensive-read surface is saturated,
 * an <em>interactive</em> endpoint with nothing to do with reports still answers, because the
 * counted share left connections it could never hold.
 *
 * <p><strong>It adds no pressure to the shared local PostgreSQL.</strong> A
 * {@code @SpringBootTest(properties = …)} context owns its own Hikari pool and the annotation sits
 * above the surefire system property, so this class pins its own pool of {@value #POOL_SIZE} — the
 * same size the suite already caps every context at — and a {@code connection-timeout} of
 * {@value #CONNECTION_TIMEOUT_MS} ms, so a starved request fails in a second and a half rather than
 * in Hikari's default thirty.
 *
 * <p><strong>Its negative control is a separate class</strong>
 * ({@code ExpensiveReadBulkheadControlTest}), because "the cap is off" is a property of a Spring
 * context and cannot be varied inside one. Read the two together: without the control this class
 * proves only that an instance with four connections can serve a request, which is not a claim
 * about the bulkhead at all.
 *
 * <p><strong>This context also carries {@code app.rate-limit.enabled=false}</strong>, which is the
 * behavioural half of AC-13: the master switch that turns off every rate budget does not reach the
 * occupancy bound, and the refusal below happens anyway. Two kinds of control, two switches.
 *
 * <p>Why this should not flake: the context is single-threaded apart from the test's own threads,
 * nothing else competes for its pool, the holding is latch-driven rather than time-driven, and the
 * held connections are closed in a {@code finally} so a failure cannot poison the context. Layer 3
 * — "a fully entitled principal no longer degrades a real instance" — is a property of a deployment
 * under load and is deliberately NOT in this suite; probe P1 re-run is its evidence.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + BulkheadSaturationBase.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout="
        + BulkheadSaturationBase.CONNECTION_TIMEOUT_MS,
        "app.rate-limit.enabled=false",
        "app.expensive-read.limit-enabled=true",
        "app.expensive-read.max-in-flight=2",
        "app.expensive-read.max-in-flight-per-principal=2",
        "app.expensive-read.acquire-wait-ms=250",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@Import(BulkheadSaturationBase.HoldingInterceptorConfig.class)
class ExpensiveReadBulkheadSaturationTest extends BulkheadSaturationBase {

    @Test
    @Timeout(120)
    void aSaturatedExpensiveSurfaceLeavesTheInteractiveApiAnswering() throws Exception {
        var first = newProject();
        var second = newProject();
        var third = newProject();

        // Two expensive requests, each holding one permit AND one real connection out of four.
        holdWithConnection(flow(first), first.token());
        holdWithConnection(flow(second), second.token());
        gate.awaitArrivals(2);

        long started = System.nanoTime();
        report(flow(third), third.token())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorType")
                        .value(ConcurrencyLimitedException.EXPENSIVE_SURFACE_BUSY));
        long refusalMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(refusalMs)
                .as("the third expensive request must be REFUSED by the bulkhead, in "
                    + "milliseconds, rather than queueing for a connection and failing after "
                    + "%s ms — converting a slow collapse into a fast refusal is the whole trade",
                    CONNECTION_TIMEOUT_MS)
                .isLessThan(Long.parseLong(CONNECTION_TIMEOUT_MS));

        assertThat(interactiveStatus(third.token()))
                .as("""

                    THE INTERACTIVE API WAS NOT SERVED WHILE THE EXPENSIVE SURFACE WAS SATURATED, \
                    WHICH IS THE ENTIRE POINT OF THE BULKHEAD.

                    Two expensive requests hold two of this context's %s connections. \
                    app.expensive-read.max-in-flight is 2, so the surface can never hold more, and \
                    GET /api/workspaces must answer from the remainder well inside the %s ms \
                    acquisition window. If this fails, either the share is no longer bounded \
                    (check PoolShareConsistency and the permit release) or something else in this \
                    context is holding connections.""".formatted(POOL_SIZE, CONNECTION_TIMEOUT_MS))
                .isEqualTo(200);
    }
}
