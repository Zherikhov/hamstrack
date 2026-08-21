package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every planning read carries the same interceptor chain</strong> (HD-96, spec §8).
 *
 * <p>The single-section endpoints ship with the same per-principal budget as the aggregate
 * they refresh a section of — which today is none — and that is a deliberate statement of
 * parity rather than an omission. A budget is earned by the work a handler does, not by
 * where it is mounted. A section fetch <em>divides</em> the row assembly and the response
 * and <em>repeats</em> the cap-blind grouped stats query, so it does not lower the ceiling
 * and does not raise it either; throttling it alone would in fact cost the server more,
 * since a client refused on a section falls back to re-fetching the whole view, which runs
 * that same aggregation and ships every section's rows with it. (Do not paraphrase this as
 * "a section fetch is cheaper than the aggregate" — that claim shipped once, was false in
 * its load-bearing term, and is corrected in {@code BacklogController}'s javadoc and in
 * spec §8.)
 *
 * <p><strong>Phrased as a property of the planning surface, not as a list of today's
 * paths</strong>, so a third planning read inherits the claim by being probed rather than by
 * someone remembering to come back here. What it forbids is the asymmetry: a surface that is
 * half-budgeted, where a client refused on the cheap request retries with the expensive one.
 * It says nothing about whether the budget should be zero — that question belongs to the
 * whole planning surface (HD-174, earned by the cap-blind stats query rather than by response
 * size), and the answer today is recorded where it can be changed deliberately: {@code ThrottleCoverageTest.theThrottledPathSetIsSealed()},
 * whose failure message is the propagation checklist. When that follow-up lands and adds a
 * pattern covering {@code …/backlog/**}, this test keeps passing — which is exactly right,
 * because it is the sameness that is being defended, not the emptiness.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PlanningThrottleParityTest {

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyPlanningReadCarriesTheSameInterceptorChain() throws Exception {
        var base = "/api/workspaces/" + UUID.randomUUID() + "/projects/" + UUID.randomUUID()
                   + "/backlog";

        var aggregate = interceptorsFor(base);
        assertThat(aggregate)
                .as("the planning aggregate matched no handler at all, so this test is comparing "
                    + "two empty lists — the path moved")
                .isNotNull();

        assertThat(interceptorsFor(base + "/sections/backlog"))
                .as("the BACKLOG section fetch sits behind a different interceptor chain than the "
                    + "view it refreshes a section of. A half-budgeted surface is worse than "
                    + "either whole answer: the client refused on the cheap request retries with "
                    + "the expensive one")
                .isEqualTo(aggregate);

        assertThat(interceptorsFor(base + "/sections/" + UUID.randomUUID()))
                .as("the SPRINT section fetch sits behind a different interceptor chain than the "
                    + "planning aggregate")
                .isEqualTo(aggregate);
    }

    /**
     * The interceptor types the real handler mapping puts in front of a GET, in chain order
     * — asked of the assembled application (pattern syntax, registration order,
     * {@code PathPattern} semantics and all) rather than of a config file somebody compared
     * by eye.
     */
    private List<String> interceptorsFor(String uri) throws Exception {
        var request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        // The DispatcherServlet normally does this; without it a PathPattern-based mapping
        // has no parsed path to look up.
        ServletRequestPathUtils.parseAndCache(request);
        var chain = handlerMapping.getHandler(request);
        assertThat(chain).as("no handler matched %s", uri).isNotNull();
        return chain.getInterceptorList().stream().map(i -> i.getClass().getName()).toList();
    }
}
