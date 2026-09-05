package com.hamstrack.common.persistence;

import com.hamstrack.common.exception.DatabaseBusyFilter;
import com.hamstrack.common.ratelimit.AuthRateLimitFilter;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <strong>Where {@code DatabaseBusyFilter} sits is TWO relations, and the one nothing pinned was
 * decided by a tie</strong> (HD-233).
 *
 * <p>The filter has to run <em>inside</em> two other filters, for two unrelated reasons:
 *
 * <ul>
 *   <li>inside {@link AuthRateLimitFilter}, or the refund cannot see the 503 — an exception caught
 *       above the limiter unwinds past the line that reads {@code response.getStatus()}. Sealed by
 *       {@link DatabaseBusyAuthBudgetTest} by construction, since without it that test's requests
 *       spend budget;</li>
 *   <li>inside Boot's {@link ServerHttpObservationFilter}, which is the relation this class exists
 *       for. It was a <strong>coin toss</strong>: {@code WebMvcObservationAutoConfiguration}
 *       registers that filter at {@code Ordered.HIGHEST_PRECEDENCE + 1}, the exact constant this
 *       one was first given, and two {@code FilterRegistrationBean}s of equal order are separated
 *       by a stable sort over bean-definition registration order — which nothing in this tree
 *       states, tests or would notice changing.</li>
 * </ul>
 *
 * <p><strong>What losing that toss costs.</strong> In the outside direction the acquisition failure
 * unwinds <em>through</em> the observation filter, which stops the observation while
 * {@code response.getStatus()} is still {@code 200}; the filter then sets 503 on a response nobody
 * is measuring any more. Every filter-path refusal is then recorded {@code status="200",
 * outcome="SUCCESS"}, the provisioned {@code HighErrorRate} rule selects {@code status=~"5.."} and
 * never sees it, and the latency histogram counts a starved instance as fast successes. That is
 * this ticket's own defect — an alert that is quietest during the incident it is for — moved from
 * the product counter onto the framework one, and landing on exactly the authenticated half the
 * filter was built to cover.
 *
 * <p>So the deliverable here is not the constant. It is <strong>one test per relation, each failing
 * if its relation inverts</strong>: the framework counter is asserted behaviourally, because that
 * is the property an operator depends on, and the three orders are asserted structurally, because
 * that is what says <em>why</em> when the first one goes red.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + PoolStarvedBase.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=" + PoolStarvedBase.ACQUISITION_MS,
        "spring.datasource.hikari.validation-timeout=" + PoolStarvedBase.ACQUISITION_MS,
        "app.locking.lock-timeout-ms=" + PoolStarvedBase.ACQUISITION_MS,
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DatabaseBusyFilterOrderTest extends PoolStarvedBase {

    /** Authenticated and cheap: what matters is that a token has to be resolved first. */
    private static final String AUTHENTICATED_ENDPOINT = "/api/workspaces";

    @Autowired private ApplicationContext context;

    /**
     * <strong>The refusal has to reach the framework's own request counter as a 5xx.</strong> Not
     * the product counter — {@code DatabaseBusyRefusalTest} owns that one — but
     * {@code http_server_requests}, which is what every latency panel and the `HighErrorRate` alert
     * are built on, and which nothing else in this suite looks at.
     */
    @Test
    @Timeout(180)
    void aFilterPathRefusalIsRecordedByTheFrameworkAsA503AndNotAsASuccess() throws Exception {
        var ctx = newProject();
        double refusalsBefore = httpRequests("503");
        double successesBefore = httpRequests("200");

        var response = whileTheWholePoolIsHeld(() -> mockMvc.perform(
                        get(AUTHENTICATED_ENDPOINT).header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse());

        assertThat(response.getStatus())
                .as("the premise: the acquisition has to fail before the dispatcher, so that "
                    + "DatabaseBusyFilter is what answers")
                .isEqualTo(503);
        assertThat(httpRequests("503") - refusalsBefore)
                .as("""
                    THE REFUSAL MUST BE COUNTED AS A 5xx BY http_server_requests.

                    If this is 0 and the 200 count moved instead, DatabaseBusyFilter is now \
                    OUTSIDE Boot's ServerHttpObservationFilter: the exception unwinds through it \
                    while the response still says 200, the observation is stopped with that \
                    status, and the 503 is set afterwards on a request nobody is measuring. The \
                    provisioned HighErrorRate rule selects status=~"5.." and would go blind to \
                    every authenticated refusal - the same alert-quietest-during-the-incident \
                    defect this ticket exists to fix, one counter over. The order is a TIE at \
                    HIGHEST_PRECEDENCE + 1, which is why DatabaseBusyFilterConfig uses + 2.""")
                .isEqualTo(1.0);
        assertThat(httpRequests("200") - successesBefore)
                .as("and it must not be recorded as a fast success, which is the other half of the "
                    + "same inversion: the latency histogram would say the instance was healthy")
                .isEqualTo(0.0);
    }

    /**
     * The structural half, which says <em>why</em> when the behavioural one goes red — and which
     * fails on a Boot upgrade that moves {@code ServerHttpObservationFilter} even before any
     * behaviour has been observed.
     */
    @Test
    void theThreeFiltersAreOrderedOutsideIn() {
        int limiter = orderOf(AuthRateLimitFilter.class);
        int observation = orderOf(ServerHttpObservationFilter.class);
        int busy = orderOf(DatabaseBusyFilter.class);

        assertThat(limiter)
                .as("""
                    AuthRateLimitFilter MUST STAY OUTSIDE DatabaseBusyFilter.

                    It reads response.getStatus() after its own chain.doFilter returns, to refund \
                    the token a 503 the instance issued about itself would otherwise cost. An \
                    exception caught above it unwinds past that line and the refund never runs.""")
                .isLessThan(busy);
        assertThat(observation)
                .as("""
                    ServerHttpObservationFilter MUST STAY OUTSIDE DatabaseBusyFilter.

                    Otherwise the refusal unwinds through it with the response still at 200 and \
                    every filter-path 503 is recorded as a success. Note these two were EQUAL \
                    (HIGHEST_PRECEDENCE + 1) once, and equality here is not a draw - it resolves \
                    by bean-definition registration order, silently, in whichever direction the \
                    scan happened to take.""")
                .isLessThan(busy);
        assertThat(limiter)
                .as("and the limiter stays outermost of the three, so a 429 costs no observation "
                    + "and no acquisition")
                .isLessThan(observation);
    }

    /** Every {@code http.server.requests} sample carrying this status, summed across its tags. */
    private double httpRequests(String status) {
        return meterRegistry.find("http.server.requests").tag("status", status).timers().stream()
                .mapToDouble(Timer::count)
                .sum();
    }

    /**
     * The registered order of the one filter of this type. Looked up by the filter's own type
     * rather than by bean name: two of the three names belong to this project and the third is
     * Boot's, and a rename of any of them must not turn this seal into a silent pass.
     */
    private int orderOf(Class<? extends Filter> type) {
        @SuppressWarnings("rawtypes")
        List<FilterRegistrationBean> matching =
                context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                        .filter(registration -> type.isInstance(registration.getFilter()))
                        .toList();
        assertThat(matching)
                .as("exactly one registration for %s — if this is empty the filter is no longer "
                    + "registered as a FilterRegistrationBean and its ORDER is no longer what "
                    + "decides anything", type.getSimpleName())
                .hasSize(1);
        return matching.getFirst().getOrder();
    }
}
