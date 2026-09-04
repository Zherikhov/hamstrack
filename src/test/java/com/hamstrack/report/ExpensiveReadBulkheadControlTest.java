package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The negative control, and without it the positive test proves nothing</strong> (HD-182,
 * layer 2, AC-6 — the other direction).
 *
 * <p>{@code ExpensiveReadBulkheadSaturationTest} shows an interactive endpoint answering while two
 * expensive requests hold two of four connections. On its own that is a claim about an instance
 * with spare capacity, not about the bulkhead: nothing in it would change if the bound were
 * deleted. This class runs the same scenario with the bound OFF and lets four expensive requests
 * take every connection — at which point the interactive endpoint cannot be served at all. Two
 * directions, one mechanism.
 *
 * <p><strong>The cap is turned off with its own switch rather than by widening the number</strong>,
 * and that is not a stylistic choice: {@code PoolShareConsistency} refuses to start a context whose
 * {@code max-in-flight} reaches the pool size, precisely because that configuration reserves
 * nothing — so "set it to 4" is not an expressible control. {@code EXPENSIVE_READ_LIMIT_ENABLED
 * =false} is, and it also exercises the documented off switch on the way past.
 *
 * <p>What this reproduces is the state HD-182 was opened for, in miniature: everything on the
 * replica waits out the pool's acquisition timeout and fails, including endpoints with nothing to
 * do with reports. The shipped behaviour converts that into a fast, explained 429 on the expensive
 * surface alone.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + BulkheadSaturationBase.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout="
        + BulkheadSaturationBase.CONNECTION_TIMEOUT_MS,
        "app.rate-limit.enabled=false",
        "app.expensive-read.limit-enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@Import(BulkheadSaturationBase.HoldingInterceptorConfig.class)
class ExpensiveReadBulkheadControlTest extends BulkheadSaturationBase {

    @Test
    @Timeout(120)
    void withTheBoundOffTheExpensiveSurfaceTakesTheWholePoolAndStarvesEverythingElse()
            throws Exception {
        var first = newProject();
        var second = newProject();
        var third = newProject();
        var fourth = newProject();
        var browser = newProject();

        // Four expensive requests, one connection each — every connection this context has.
        holdWithConnection(flow(first), first.token());
        holdWithConnection(flow(second), second.token());
        holdWithConnection(flow(third), third.token());
        holdWithConnection(flow(fourth), fourth.token());
        gate.awaitArrivals(4);

        assertThat(interactiveStatus(browser.token()))
                .as("""

                    THE CONTROL DID NOT STARVE, SO THE POSITIVE TEST BESIDE IT PROVES NOTHING.

                    With app.expensive-read.limit-enabled=false, four expensive requests hold all \
                    %s connections and GET /api/workspaces — an endpoint with nothing to do with \
                    reports — must fail to be served inside the %s ms acquisition window. That is \
                    the state HD-182 exists to delete, and it has to be reproducible or the \
                    guarantee its sibling asserts is unfalsifiable. If this now answers 200, the \
                    holding interceptor has stopped actually checking out connections.\
                    """.formatted(POOL_SIZE, CONNECTION_TIMEOUT_MS))
                // Not merely "not 200": 401/403 would mean the request was REFUSED rather than
                // starved, which would make this a test of authentication and not of the pool.
                // With every connection held, the JWT filter's own user lookup is what fails, and
                // it fails by throwing — hence -1, or a 5xx if anything ever translates it.
                .isNotIn(200, 401, 403);

        // And the surface is refusing nothing while it does it: the bound is off, so the fifth
        // expensive request is not turned away — it queues for a connection like everything else.
        // (Asserted after the release, because before it there is no connection for it to get.)
        gate.open();
        report(flow(browser), browser.token()).andExpect(status().isOk());
    }
}
