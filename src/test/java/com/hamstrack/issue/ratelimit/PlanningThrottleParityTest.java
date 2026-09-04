package com.hamstrack.issue.ratelimit;

import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every planning read carries the same interceptor chain, and that chain carries BOTH
 * controls</strong> (HD-96, strengthened by HD-174).
 *
 * <p>The property this file was born defending is the <em>asymmetry</em>: a surface where the cheap
 * request is refused and the expensive one is not is worse than either whole answer, because the
 * client refused on the section falls back to re-fetching the whole view — which runs the same
 * cap-blind aggregation and ships every section's rows with it. HD-96 defended it while the answer
 * was "no budget at all", and its javadoc predicted that HD-174 would add a pattern covering
 * {@code …/backlog/**} and that this test should keep passing. <strong>That prediction was correct
 * and is now fulfilled</strong>: it is the sameness that is defended, never the emptiness, so the
 * arrival of a budget changed what the chain contains and not what this file asserts about it.
 *
 * <p>What HD-174 <em>did</em> change is the strength of the claim. It is no longer "the same chain"
 * but "the same chain, carrying the planning budget <em>and</em> an occupancy bound" — a
 * half-budgeted surface and a half-bounded one fail in the same shape.
 *
 * <h2>The scanned set is DERIVED, and contains no URI literal</h2>
 *
 * <p>The version this replaces probed three URI literals. It was a good test defending the right
 * property, and a fourth {@code @GetMapping} on {@code BacklogController} was invisible to it —
 * which is the exact failure the surface's own budget is designed against. The set is now a
 * <strong>union of two halves</strong>, both read from the running application:
 *
 * <ul>
 *   <li><strong>Half A — the pattern half.</strong> {@link PlanningRateLimitConfig#PLANNING_PATH},
 *       parsed, matched against every mapping in {@link RequestMappingHandlerMapping}. A fourth
 *       planning endpoint is in scope <em>by existing</em>.</li>
 *   <li><strong>Half B — the registration half.</strong> Every pattern that a
 *       {@link PrincipalThrottleInterceptor} whose {@code budget()} is the
 *       {@link PlanningRateLimiter} is registered on, read from the {@link InterceptorRegistry} of
 *       <em>every</em> {@link WebMvcConfigurer} in the context. This is what puts a planning read
 *       registered on a <em>second</em> pattern into scope automatically.</li>
 * </ul>
 *
 * <p><strong>Half B is read from the registrations and NOT by probing every mapped handler through
 * {@code getHandler}, and that reason is HD-182's, reused verbatim rather than re-derived:</strong>
 * {@code getHandler} returns <em>no chain at all</em> when a mapping's {@code consumes},
 * {@code produces}, {@code params} or {@code headers} conditions are not satisfied by the probe, so
 * a multipart or content-negotiated handler would silently answer "no chain" and drop out of the
 * scanned set — the hole the derivation exists to close, reintroduced by the mechanism that closes
 * it. It must not be weakened back into a probe.
 *
 * <p>{@code getHandler} is therefore used for <strong>one</strong> job — obtaining the chain of a
 * handler already known to be in scope — and its result is asserted <strong>non-null with a message
 * naming the fix</strong>. A handler that cannot be probed is loud, never a silent drop.
 *
 * <h2>Two tripwires, both of which this file's history says are needed</h2>
 *
 * <ul>
 *   <li>the scanned set has size <strong>&ge; 3</strong>, so a moved path cannot leave this test
 *       comparing empty lists and reporting a pass;</li>
 *   <li><strong>every pattern from half B matches at least one scanned handler</strong>, so a
 *       registration whose pattern matches nothing — dead configuration wearing a bulkhead's
 *       clothes — fails here rather than passing green.</li>
 * </ul>
 *
 * <p>What this file still cannot see, said plainly: <em>a planning read mounted somewhere else</em>.
 * Nothing structural can identify one. That is the insights failure mode — a report that did not
 * live under {@code /reports} — and it is the gap
 * {@code ThrottleCoverageTest.PROPAGATION_CHECKLIST}'s sixth question names and cannot close.
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

    @Autowired
    PlanningRateLimiter planningRateLimiter;

    /**
     * EVERY configurer in the context, not {@link PlanningRateLimitConfig} alone: half B has to see
     * a planning binding that was moved to another configurer, or the union degrades to half A the
     * moment somebody reorganises the registrations and nothing says so.
     */
    @Autowired
    List<WebMvcConfigurer> webMvcConfigurers;

    @Test
    void everyPlanningReadCarriesTheSameChainAndBothControls() throws Exception {
        var surface = planningSurface();

        assertThat(surface)
                .as("fewer than three planning handlers were found, so this test is comparing "
                    + "almost nothing — either BacklogController moved or %s no longer matches it. "
                    + "Both are silent failures: the surface keeps working, unbudgeted and "
                    + "unbounded, and every document still says otherwise",
                    PlanningRateLimitConfig.PLANNING_PATH)
                .hasSizeGreaterThanOrEqualTo(3);

        List<String> reference = null;
        String referenceName = null;

        for (var handler : surface) {
            var chain = chainFor(handler);

            var types = chain.stream().map(i -> i.getClass().getName()).toList();
            if (reference == null) {
                reference = types;
                referenceName = handler.describe();
                continue;
            }
            assertThat(types)
                    .as("%s sits behind a different interceptor chain than %s. A half-budgeted "
                        + "planning surface is worse than either whole answer: the client refused "
                        + "on the cheap section refresh falls back to the whole-view refetch, "
                        + "which runs the same cap-blind aggregation and ships every section's "
                        + "rows with it", handler.describe(), referenceName)
                    .isEqualTo(reference);
        }

        for (var handler : surface) {
            var throttles = chainFor(handler).stream()
                    .filter(PrincipalThrottleInterceptor.class::isInstance)
                    .map(PrincipalThrottleInterceptor.class::cast)
                    .filter(throttle -> throttle.appliesTo(handler.method()))
                    .toList();

            assertThat(throttles)
                    .as("%s carries no PrincipalThrottleInterceptor that applies to its own verb. "
                        + "Membership in the chain is not coverage: a method-conditioned binding "
                        + "(the write budget's shape) is in the chain of handlers it never fires "
                        + "on", handler.describe())
                    .isNotEmpty();

            assertThat(throttles)
                    .as("%s is not on the PLANNING budget. Register the path in "
                        + "PlanningRateLimitConfig, which carries both controls on one "
                        + "interceptor; do not give a planning read a pot of its own, and do not "
                        + "put it on the reports pot — 60/min would refuse an ordinary grooming "
                        + "session inside three minutes", handler.describe())
                    .anyMatch(throttle -> throttle.budget() == planningRateLimiter);

            assertThat(throttles)
                    .as("%s is rate-bounded and NOT occupancy-bounded. That is the half of HD-174 "
                        + "that matters: BacklogService holds ONE pool connection across up to 32 "
                        + "statements in a single read-only transaction, DB_STATEMENT_TIMEOUT_MS "
                        + "bounds each statement and nothing bounds their sum, and a rate budget "
                        + "spends the same unit whether a request takes 8 ms or 8 s — so its "
                        + "protection evaporates precisely as the instance slows down. The share "
                        + "is the EXISTING ExpensiveReadConcurrencyLimit (ADR-0031), never a "
                        + "second one", handler.describe())
                    .anyMatch(throttle -> throttle.concurrencyBound() != null);
        }
    }

    /**
     * <strong>The registered planning pattern is not dead configuration.</strong>
     *
     * <p>Half B is only as good as the patterns it reads: one that matches no mapped handler would
     * leave the union quietly equal to half A while the registration reads, in a config file, as
     * though a surface were covered. Phrased over patterns rather than over today's handlers, so it
     * stays true as endpoints move.
     */
    @Test
    void everyRegisteredPlanningPatternMatchesAHandler() {
        var surface = planningSurface();
        var patterns = registeredPlanningPatterns();

        assertThat(patterns)
                .as("no interceptor registration in this context carries the planning budget, so "
                    + "the planning surface is unbudgeted and half B of the scan is empty")
                .isNotEmpty();

        for (var pattern : patterns) {
            assertThat(surface)
                    .as("no mapped handler matches the registered planning pattern %s — either it "
                        + "matches nothing at all (dead configuration wearing a bulkhead's "
                        + "clothes) or the scan cannot see the handlers it covers",
                        pattern.getPatternString())
                    .anyMatch(handler -> pattern.matches(PathContainer.parsePath(handler.uri())));
        }
    }

    // ------------------------------------------------------------------ the derivation

    /**
     * The union of half A (handlers the planning pattern constant matches) and half B (handlers
     * under any pattern the planning budget is registered on). Deduplicated by mapped pattern +
     * verb, since a handler can legitimately arrive through both halves.
     */
    private List<ProbedHandler> planningSurface() {
        var patterns = new LinkedHashSet<PathPattern>();
        patterns.add(new PathPatternParser().parse(PlanningRateLimitConfig.PLANNING_PATH));
        patterns.addAll(registeredPlanningPatterns());

        var seen = new LinkedHashSet<String>();
        var surface = new ArrayList<ProbedHandler>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var mapped = Objects.requireNonNull(entry.getKey().getPathPatternsCondition(),
                                                "a handler with no path patterns to probe");
            for (var pattern : mapped.getPatterns()) {
                var method = httpMethod(entry.getKey().getMethodsCondition().getMethods());
                var uri = concrete(pattern.getPatternString());
                boolean planning = patterns.stream()
                        .anyMatch(p -> p.matches(PathContainer.parsePath(uri)));
                if (planning && seen.add(method + " " + pattern.getPatternString())) {
                    surface.add(new ProbedHandler(entry.getValue(), method, uri,
                                                  pattern.getPatternString()));
                }
            }
        }
        return surface;
    }

    /**
     * Every path pattern a {@link PrincipalThrottleInterceptor} carrying the
     * {@link PlanningRateLimiter} is registered on, across every configurer in the context.
     *
     * <p>Read from the registrations, deliberately — see the class javadoc: a probe has to satisfy
     * a mapping's {@code consumes} / {@code produces} / {@code params} / {@code headers} conditions
     * before a chain comes back at all, so probing would drop a multipart or content-negotiated
     * handler out of the scanned set in silence.
     */
    private Set<PathPattern> registeredPlanningPatterns() {
        var parser = new PathPatternParser();
        var patterns = new LinkedHashSet<PathPattern>();

        for (var configurer : webMvcConfigurers) {
            var registry = new ExposedRegistry();
            configurer.addInterceptors(registry);
            for (var registered : registry.registered()) {
                var interceptor = registered instanceof MappedInterceptor wrapped
                        ? wrapped.getInterceptor()
                        : registered;
                if (!(interceptor instanceof PrincipalThrottleInterceptor throttle)
                        || throttle.budget() != planningRateLimiter) {
                    continue;
                }
                assertThat(registered)
                        .as("the planning budget was registered with NO path patterns, so it is "
                            + "spent on EVERY request in the application")
                        .isInstanceOf(MappedInterceptor.class);
                var includes = Objects.requireNonNull(
                        ((MappedInterceptor) registered).getIncludePathPatterns(),
                        "the planning budget was registered with excludes or method conditions "
                        + "but no INCLUDE patterns — state the paths positively");
                for (var include : includes) {
                    patterns.add(parser.parse(include));
                }
            }
        }
        return patterns;
    }

    /**
     * The chain the real {@link RequestMappingHandlerMapping} puts in front of a handler already
     * known to be on this surface.
     *
     * <p>The non-null assertion is the point. {@code getHandler} is the only place this file has to
     * go through a probe, and it answers "no chain" — indistinguishably from "no interceptors" if
     * one were careless — whenever the synthesized request does not satisfy the mapping's
     * {@code consumes} / {@code produces} / {@code params} conditions. A handler that drops out of
     * this scan is a handler this seal silently stops covering, so the drop is made loud.
     */
    private List<Object> chainFor(ProbedHandler handler) throws Exception {
        var request = new MockHttpServletRequest(handler.method(), handler.uri());
        request.setRequestURI(handler.uri());
        // The DispatcherServlet normally does this; without it a PathPattern-based mapping has no
        // parsed path to look up.
        ServletRequestPathUtils.parseAndCache(request);
        var chain = handlerMapping.getHandler(request);
        assertThat(chain)
                .as("a planning handler could not be probed: %s. Give the probe the consumes / "
                    + "produces / params its mapping requires — a handler that drops out of this "
                    + "scan is a handler this seal silently stops covering", handler.describe())
                .isNotNull();
        return List.copyOf(chain.getInterceptorList());
    }

    /**
     * {@code InterceptorRegistry.getInterceptors()} is protected, and a subclass is the sanctioned
     * way to read it — reaching into {@code AbstractHandlerMapping}'s adapted interceptors would
     * mix in every other configurer's registrations and stop being an assertion about a binding.
     */
    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    /** One mapped handler, with the concrete URI the probe uses for it. */
    private record ProbedHandler(HandlerMethod handler, String method, String uri, String pattern) {
        String describe() {
            return method + " " + pattern + "  (" + handler.getBeanType().getSimpleName() + "."
                   + handler.getMethod().getName() + ")";
        }
    }

    /**
     * A concrete URI for a mapped pattern: every {@code {var}} becomes a UUID. Derived from the
     * mapping, which is what keeps this file free of URI literals — the only path string it
     * contains is {@link PlanningRateLimitConfig#PLANNING_PATH}, the constant under test.
     */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^/}]*}", UUID.randomUUID().toString());
    }

    private static String httpMethod(Set<RequestMethod> methods) {
        return methods.isEmpty() ? "GET" : List.copyOf(methods).get(0).name();
    }
}
