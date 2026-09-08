package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Doors equals the runtime (HD-296, D6).</strong>
 *
 * <p>{@link Doors#handlers()} selects and composes handlers by reflection so that category tests
 * need no Spring context; {@link RequestMappingHandlerMapping} is what the {@code DispatcherServlet}
 * routes. This class holds the two equal — the set of {@code (Handler.id(), verb, pattern)} triples
 * in <em>both</em> directions, and per handler the parameter count — restricted on the runtime side
 * to handlers whose bean type lives under the production code source (a test-scoped probe controller
 * such as {@code DeclaredConstraintRefusalTest}'s {@code /api/__test/**} is outside Doors by design).
 *
 * <p>This equality is what lets {@code RequestFieldLengthBoundTest} and {@code WriteThrottleCoverageTest}
 * drop their private scans without weakening: each enumerated a subset of what the runtime routes,
 * and the runtime is now proven equal to Doors. A difference is fixed in Doors' composition — or
 * recorded in its javadoc as a runtime behaviour it missed — never filtered away here.
 *
 * <p><strong>The context is the one with every bean condition on.</strong> Doors counts classes,
 * not beans, so a handler on a {@code @Conditional}-meta class ({@code CspReportController},
 * {@code app.csp.sink-enabled}) is a member whether or not a given context instantiates it; the
 * equality can only hold in a context that does. This class therefore shares
 * {@code CspReportBatchBudgetTest}'s property set verbatim — the main shared set plus the sink and
 * its budget numbers, which a class that sends no request never spends — rather than
 * {@code WriteThrottleCoverageTest}'s (the spec's first choice, under which the first run reported
 * exactly that handler as "only Doors"). A new conditional controller shows up here as an
 * "only Doors" line marked {@code [conditional bean]}, and the fix is one more property below.
 */
@SpringBootTest(properties = {
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=5",
        "app.csp.reports-per-minute=10000",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DoorsHandlerMappingParityTest {

    private static final String WHAT_TO_DO = """

            Doors and Spring disagree about the handler set.

              only Doors:   %s
              only Spring:  %s

            Doors composes paths and verbs from the merged @RequestMapping by reflection so that
            category tests need no context; RequestMappingHandlerMapping is what the
            DispatcherServlet routes. Every consumer of writeHandlers()/readHandlers() inherits
            whichever side is wrong — fix Doors' composition (or record the Spring behaviour it
            missed in Doors' javadoc), never filter the difference away here. An "only Doors" line
            marked [conditional bean] is a controller this context did not instantiate: add the
            property that enables it to this class's @SpringBootTest set.
            """;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void doorsAndTheHandlerMappingAgreeOnEveryVerbAndPattern() {
        var doorsByTriple = new LinkedHashMap<String, Doors.Handler>();
        for (var handler : Doors.handlers().floor(250)) {
            for (var path : handler.paths()) {
                for (var verb : verbsOf(handler.verbs().stream().map(Enum::name).toList())) {
                    doorsByTriple.put(handler.id() + " " + verb + " " + path, handler);
                }
            }
        }
        var doors = new TreeSet<>(doorsByTriple.keySet());

        var spring = new TreeSet<String>();
        for (var entry : productionHandlers().entrySet()) {
            var info = entry.getKey();
            var handler = entry.getValue();
            var id = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            var patterns = info.getPathPatternsCondition() == null ? List.<String>of()
                    : new ArrayList<>(info.getPathPatternsCondition().getPatternValues());
            for (var path : patterns) {
                for (var verb : verbsOf(info.getMethodsCondition().getMethods().stream().map(Enum::name).toList())) {
                    spring.add(id + " " + verb + " " + path);
                }
            }
        }
        assertThat(spring)
                .as("the runtime routed almost no production handlers — the code-source filter is wrong or the "
                    + "context is not the application's")
                .hasSizeGreaterThan(250);

        var onlyDoors = new ArrayList<String>();
        for (var triple : doors) {
            if (!spring.contains(triple)) {
                onlyDoors.add(triple + (doorsByTriple.get(triple).conditional() ? "  [conditional bean]" : ""));
            }
        }
        var onlySpring = new TreeSet<>(spring);
        onlySpring.removeAll(doors);

        assertThat(onlyDoors.isEmpty() && onlySpring.isEmpty())
                .as(WHAT_TO_DO, String.join("\n                ", onlyDoors), String.join("\n                ", onlySpring))
                .isTrue();
    }

    /** {@link Doors#controllerParams()} sees every parameter the runtime binds, per handler. */
    @Test
    void doorsSeesEveryParameterTheRuntimeBinds() {
        var doorsCount = new LinkedHashMap<Method, Integer>();
        for (var param : Doors.controllerParams().floor(500)) {
            doorsCount.merge(param.handler().method(), 1, Integer::sum);
        }

        var disagreements = new ArrayList<String>();
        var compared = 0;
        for (var handler : productionHandlers().values()) {
            compared++;
            var runtime = handler.getMethodParameters().length;
            var doors = doorsCount.getOrDefault(handler.getMethod(), 0);
            if (runtime != doors) {
                disagreements.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
                                  + ": runtime binds " + runtime + ", Doors sees " + doors);
            }
        }
        assertThat(compared).as("no production handlers compared — see the sibling test").isGreaterThan(250);
        assertThat(disagreements)
                .as("Doors.controllerParams() must see exactly the parameters HandlerMethod binds, or a rule over "
                    + "parameters certifies a subset")
                .isEmpty();
    }

    /** The runtime's handler map, restricted to bean types compiled into the production code source. */
    private Map<RequestMappingInfo, HandlerMethod> productionHandlers() {
        var root = Doors.codeSourceRoot();
        var production = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var source = entry.getValue().getBeanType().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                continue;
            }
            try {
                if (Path.of(source.getLocation().toURI()).equals(root)) {
                    production.put(entry.getKey(), entry.getValue());
                }
            } catch (java.net.URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return production;
    }

    /** The verbs a mapping answers, spelled the same way on both sides: {@code ANY} for an unconditioned mapping. */
    private static List<String> verbsOf(List<String> verbs) {
        return verbs.isEmpty() ? List.of("ANY") : verbs;
    }
}
