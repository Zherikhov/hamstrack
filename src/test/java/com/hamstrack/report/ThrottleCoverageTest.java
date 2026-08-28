package com.hamstrack.report;

import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import com.hamstrack.report.ratelimit.ReportRateLimitConfig;
import com.hamstrack.report.ratelimit.ReportRateLimiter;
import com.hamstrack.search.ratelimit.SearchRateLimitConfig;
import com.hamstrack.search.ratelimit.SearchRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every expensive handler is behind a budget — asked of the handler mapping, not of a
 * config file</strong> (HD-140 R6 round 2, security item 15).
 *
 * <p>Both throttles are path bindings, which is the right shape: a new report under
 * {@code …/reports/**} inherits its budget the moment its {@code @GetMapping} exists. But a path
 * binding covers exactly the paths it names, and this epic has now produced three endpoints that
 * landed <em>outside</em> the pattern that was supposed to cover them — the Insights panel, which
 * is a report that does not live under {@code /reports}; the search endpoints, which ran the same
 * predicate more expensively than the panel and had no budget at all; and saved filters, which
 * validate HQL (and therefore build a {@code ResolutionContext}: eight statements, a workspace-wide
 * label projection, a full member scan) on a path named after neither. All three were caught by a
 * person reading a config file. The next one will not be.
 *
 * <p>So the question is put to the runtime instead: for every handler that must be throttled,
 * synthesize a request against its own mapped pattern, ask the real
 * {@link RequestMappingHandlerMapping} for the chain, and assert a
 * {@link PrincipalThrottleInterceptor} is in it. That is true or false about the application as
 * assembled — pattern syntax, registration order, {@code PathPattern} semantics and all — rather
 * than about a string somebody compared by eye. R7's {@code .csv} variants are covered by it
 * without anyone remembering to come back here, which is the whole point.
 *
 * <p><strong>Both surfaces are named as packages, and coverage is the default</strong> (round 3,
 * item 2). Round 2 named the search side as a single type, {@code Set.of(SearchController.class)} —
 * a hand-written list of the endpoints somebody had thought of, i.e. exactly the artefact this file
 * exists to replace, and one a future {@code SearchExportController} would slip past in silence.
 * The polarity is inverted now: everything under either package must be throttled unless it is in
 * {@link #EXEMPT}, so an exemption is a reviewable edit and forgetting is not an option.
 *
 * <p><strong>A second, opposite property lives here too</strong> (round 4, item 3):
 * {@link #theThrottledPathSetIsSealed()} asserts the throttled path set has not <em>grown</em>.
 * Coverage and sealing are not the same question — coverage can stay perfect while every artefact
 * describing the paths goes stale, which is what happened when saved filters joined the search pot
 * and four review rounds each turned up one more document still saying "the search endpoints". That
 * test carries the propagation checklist in its failure message, so a third pattern fails one test
 * that names the files to edit, at the moment it is added.
 *
 * <p>What it does NOT assert: how big a budget is. One interceptor of that type in the chain is the
 * property, plus the ORDER when a path carries two — {@code InsightsThrottleTest} and
 * {@code SearchThrottleTest} own the behaviour of each pot.
 *
 * <p><strong>Nor does it assert anything about limiters that are not path bindings, and there is
 * now one.</strong> HD-190's invitation ceilings are keyed on the <em>recipient</em> and are spent
 * inside {@code WorkspaceService.inviteMember}; they register no path pattern, so
 * {@link #theThrottledPathSetIsSealed()} stays green across that whole feature — correctly, because
 * a recipient-keyed refusal spent in an interceptor would answer a cross-tenant question to a
 * non-member. Their seal is {@code com.hamstrack.common.mail.MailThrottleCoverageTest}, which asks
 * the same kind of question on the axis of <em>mailers</em> rather than paths. The
 * {@link #PROPAGATION_CHECKLIST} says which question to ask when.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ThrottleCoverageTest {

    /**
     * Every handler declared under either of these must be throttled. Packages, not types, so a
     * seventh report controller or a second search controller is covered by existing.
     */
    private static final List<String> THROTTLED_PACKAGES = List.of(
            "com.hamstrack.report.controller",
            // Includes search.filter.controller: a saved filter is a saved search, and creating one
            // validates its HQL through the same ResolutionContext build that /search/schema pays
            // for. HQL validation is search-surface work wherever it is mounted.
            "com.hamstrack.search");

    /**
     * Handlers under those packages that may be unthrottled.
     *
     * <p><strong>Empty, and that is the finding rather than an oversight.</strong> Round 2 excluded
     * saved-filter CRUD here with a reason that was true about its queries and silent about its HQL
     * validation; round 3 resolved that by <em>throttling</em> it instead of writing a better
     * exemption. Anything added here needs a reason that survives the same question: what is the
     * most expensive thing one authenticated caller can make this handler do in a loop?
     */
    private static final Set<Class<?>> EXEMPT = Set.of();

    /**
     * <strong>The checklist, in the one place a maintainer cannot fail to read it: a failing
     * assertion at the moment of the change.</strong>
     *
     * <p>It used to live in a comment on {@code SearchProperties.requestsPerMinute}, and that comment
     * was stale one round after it was written — it named five artefacts where the change had touched
     * eight, omitting {@code .env.prod.example}, which is precisely the file that then shipped wrong.
     * A comment points; it does not fire. Nothing about the direction was wrong, only about whether
     * direction is a mechanism.
     *
     * <p>Deliberately NOT asserted against the documents themselves. A test that greps prose for
     * {@code "filters"} fires on one symptom of one path, dies on the first rewording, and gets
     * deleted as noise; and javadoc is not retained at runtime, so the comment cannot be tested
     * either. What is testable is the property the comment is a proxy for — that the set of throttled
     * paths has not grown — so that is what {@link #theThrottledPathSetIsSealed()} pins, and this
     * text rides along on its failure.
     */
    private static final String PROPAGATION_CHECKLIST = """

            A throttled path is described in every artefact below as well as in the config class. \
            Adding or renaming one means editing ALL of them in the same change — each enumerates \
            the paths, so each is a claim a new pattern falsifies. (Deliberately not prefixed with \
            a count: "five targets" is what the comment this replaced said while the change had \
            touched eight, and a number goes stale one entry before the list does.)

              1. src/main/java/.../common/config/SearchProperties.java   — the enumeration on
                 requestsPerMinute (the source of truth the others were reconciled TO), and the
                 javadoc on ReportRateLimitConfig.REPORTS_PATH / INSIGHTS_PATH for the reports side
              2. src/main/resources/application.properties               — the app.reports.* and
                 app.search.* comment blocks AND the numbered list above app.rate-limit.enabled
              3. .env.prod.example                                       — the REPORTS_REQUESTS_PER_MINUTE
                 and SEARCH_REQUESTS_PER_MINUTE blocks (the one missed last time)
              4. docs/self-hosting.md                                    — the operator table rows and
                 the numbered "Nth mechanism is node-local" prose below it
              5. docs/api-dc.md AND docs/api-cloud.md                    — the env-var table, the
                 "surfaces with throttles of their own" section, and each endpoint's 429 note
              6. src/main/frontend/public/openapi.yaml                   — the info description and the
                 429 responses (NOT the copy in src/main/resources/static, which the Vite build
                 overwrites from public/)
              7. docs/project-state.md                                   — the "Config:" line of the
                 search section
              8. docs/hql-search-maintainers-guide.md                    — the ratelimit/** row of the
                 package table
              9. src/main/frontend/src/api.ts                            — the 429 comments on the
                 search, insights and saved-filter callers

            Two claims to re-check rather than copy, because both have been false in this tree \
            already: "the whole X surface" (insights is a report OUTSIDE .../reports/**) and "the \
            only bound" (insights is inside BOTH budgets — the lower configured value binds).

            AND THE CLAIM THIS TEST'S OWN NAME MAKES: it seals ONE axis. "The throttled path set \
            is sealed" is true and is not the same sentence as "everything expensive is behind a \
            budget" — a limiter can be correct and be invisible here, because it is not a path \
            binding at all. One already is. The invitation ceilings (HD-190) are keyed on the \
            RECIPIENT and are spent inside WorkspaceService.inviteMember, deliberately, and they \
            register no path pattern — which is why the assertion above stays green across that \
            whole feature and is not weakened by it.

            The reason they cannot live on this axis is tenancy, not convenience. A \
            PrincipalThrottleInterceptor is spent before the controller resolves anything, and \
            PerPrincipalMinuteBudget argues that this is safe precisely because the key is the \
            CALLER: "the 429 is identical for a real workspace, a nonexistent one and somebody \
            else's". A recipient-keyed refusal does not have that property — spent early it would \
            answer, to a caller who is not a member of the workspace in the path, a question about \
            mail traffic elsewhere in the instance, i.e. a 429 where this project requires a 404.

            So the failure message below ("do not add a check inside the service, which is the \
            line the next endpoint forgets") is advice about PATH-SHAPED, PRINCIPAL-KEYED budgets \
            and is wrong about victim-keyed ones. The sibling seal for those is \
            com.hamstrack.common.mail.MailThrottleCoverageTest, which inverts the axis from PATH \
            to MAILER: every ProductMetrics.EmailType is either recipient-throttled or exempt with \
            a written reason, so a fourth kind of outbound mail fails one test that names what to \
            do. If you are adding an expensive surface, ask BOTH questions — is it a path that \
            needs a budget, and does it send mail to an address the caller chose?
            """;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ReportRateLimitConfig reportRateLimitConfig;

    @Autowired
    SearchRateLimitConfig searchRateLimitConfig;

    @Test
    void everyReportAndSearchHandlerSitsBehindAPerPrincipalThrottle() throws Exception {
        var probed = new ArrayList<String>();
        var unthrottled = new LinkedHashSet<String>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var beanType = entry.getValue().getBeanType();
            if (!mustBeThrottled(beanType)) {
                continue;
            }
            var patterns = entry.getKey().getPathPatternsCondition();
            assertThat(patterns)
                    .as("%s has no path patterns to probe", entry.getValue())
                    .isNotNull();
            for (var pattern : patterns.getPatterns()) {
                var method = httpMethod(entry.getKey().getMethodsCondition().getMethods());
                var uri = concrete(pattern.getPatternString());
                probed.add(method + " " + uri);
                if (throttlesFor(method, uri).isEmpty()) {
                    unthrottled.add(method + " " + pattern.getPatternString()
                                    + "  (" + beanType.getSimpleName() + ")");
                }
            }
        }

        // Tripwire: a probe that matched nothing would pass the assertion below while guarding an
        // empty set — the failure this file exists to prevent, so it is checked rather than assumed.
        assertThat(probed)
                .as("the handler mapping produced almost no report/search handlers, so this test "
                    + "is guarding an empty set — a controller package moved")
                .hasSizeGreaterThan(5);

        assertThat(unthrottled)
                .as("these handlers are reachable with no per-principal budget in front of them. "
                    + "A report is O(project history), a search is O(issues x predicates) and an "
                    + "HQL validation is eight statements before it can even say no — none of them "
                    + "is bounded by what it returns, and Cache-Control: private means no shared "
                    + "cache absorbs a repeat, so an unthrottled one is a connection-pool "
                    + "exhaustion made of legal 200s. Add the path to ReportRateLimitConfig or "
                    + "SearchRateLimitConfig; do not add a check inside the service, which is the "
                    + "line the next endpoint forgets.")
                .isEmpty();
    }

    /**
     * The one path covered by two budgets spends them in the documented order (round 3, item 1).
     *
     * <p>Not a preference. If search ran first, an insights request the <em>reports</em> budget goes
     * on to refuse would already have spent a unit of the search budget — so a client hammering an
     * exhausted reports budget would burn its search allowance and eventually lock itself out of
     * the plain search box. That is a reports overrun leaking into the search pot, which is the one
     * thing two separate pots are for. Reports-first also makes the 429's message and its metric
     * deterministic instead of dependent on bean-registration order.
     */
    @Test
    void insightsSpendsTheReportsBudgetBeforeTheSearchBudget() throws Exception {
        var uri = "/api/workspaces/" + UUID.randomUUID() + "/search/insights";

        var throttles = throttlesFor("POST", uri);

        assertThat(throttles)
                .as("insights must sit behind BOTH budgets — it is a report that happens to live "
                    + "on the search path")
                .hasSize(2);
        assertThat(throttles.get(0).budget())
                .as("the reports budget must be spent first, so a report-refused request never "
                    + "charges the search pot")
                .isInstanceOf(ReportRateLimiter.class);
        assertThat(throttles.get(1).budget()).isInstanceOf(SearchRateLimiter.class);
    }

    /**
     * <strong>The throttled path set is sealed: exactly four patterns, two per budget</strong>
     * (HD-140 R6 round 4, item 3).
     *
     * <p>The sibling test above asks "is every handler covered?", which is the safety property. This
     * one asks the documentation property, which is different and had been failing quietly: <em>has
     * the set of throttled paths changed since the eight documents describing it were written?</em>
     * Coverage can stay perfect while every artefact naming the paths goes stale — that is exactly
     * what happened when {@code …/filters/**} joined the search pot and four rounds of review kept
     * finding one more file that still said "the search endpoints".
     *
     * <p><strong>Why an equality assertion is the right shape here.</strong> The mechanism it
     * replaces is a comment, and a comment cannot fail. This can, and it fails at the only moment
     * anyone can act on cheaply: the commit that adds the third pattern, not the review round after
     * it. Everything a maintainer then has to do is in {@link #PROPAGATION_CHECKLIST}, printed by
     * the failure — so the test is not a gate to be satisfied and forgotten, it is where the list
     * lives.
     *
     * <p>Asserted against what the configurers actually <em>register</em> rather than against their
     * {@code static final String} constants, so an inline literal in {@code addPathPatterns} is
     * caught too; and the expected values are spelled out here rather than imported, so changing a
     * constant's value — which every one of those documents quotes verbatim — fails as loudly as
     * adding one.
     */
    @Test
    void theThrottledPathSetIsSealed() {
        assertThat(registeredPatterns(reportRateLimitConfig))
                .as("the REPORTS budget covers a different set of paths than the documents say."
                    + " Note in particular that it is NOT only .../reports/**: the Insights panel"
                    + " is a report bound explicitly, which is why 'the whole reports surface' and"
                    + " 'the only bound on the work a report does' were both false for two rounds."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder(
                        "/api/workspaces/*/projects/*/reports/**",
                        "/api/workspaces/*/search/insights");

        assertThat(registeredPatterns(searchRateLimitConfig))
                .as("the SEARCH budget covers a different set of paths than the documents say."
                    + " Note in particular that it is NOT only .../search/**: saved filters are on"
                    + " it because HQL validation is search-surface work wherever it is mounted,"
                    + " and that is the addition the last round's documents were missing."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder(
                        "/api/workspaces/*/search/**",
                        "/api/workspaces/*/filters/**");
    }

    /**
     * Every path pattern a {@link WebMvcConfigurer} binds an interceptor to, taken from the
     * {@link MappedInterceptor}s its own {@code addInterceptors} produces.
     *
     * <p><strong>Every registration is examined, not just the mapped ones</strong> (round 4
     * follow-up). {@code InterceptorRegistration.getInterceptor()} returns the interceptor
     * <em>raw</em> when nothing narrowed it — no include patterns, no excludes, no method
     * conditions — and wraps it in a {@code MappedInterceptor} only otherwise. So an
     * {@code instanceof} filter here would have silently skipped the single worst registration
     * anyone could write: a throttle applied to EVERY request in the application, which contributes
     * no patterns and would therefore have left the seal green. Removing patterns from an existing
     * registration was always caught (the set shrinks and the equality fails); this is the
     * additive case, and it is the one the message below claims to cover — a guard that names a
     * case it cannot see is what this epic has spent five rounds deleting.
     *
     * <p>The null check behind it is a second, narrower door rather than a duplicate: a
     * registration carrying only {@code excludePathPatterns} or only method conditions IS a
     * {@code MappedInterceptor}, with null includes.
     */
    private static Set<String> registeredPatterns(WebMvcConfigurer config) {
        var registry = new ExposedRegistry();
        config.addInterceptors(registry);
        return registry.registered().stream()
                .flatMap(registered -> {
                    assertThat(registered)
                            .as("an interceptor was registered with NO path patterns at all, so it "
                                + "applies to EVERY request in the application — never what either "
                                + "budget wants. Spring hands such a registration back raw instead "
                                + "of wrapping it in a MappedInterceptor, which is why this is "
                                + "asserted rather than filtered out")
                            .isInstanceOf(MappedInterceptor.class);
                    return Arrays.stream(Objects.requireNonNull(
                            ((MappedInterceptor) registered).getIncludePathPatterns(),
                            "an interceptor was registered with excludes or method conditions but "
                            + "no INCLUDE patterns, so it applies to every request those "
                            + "conditions do not rule out — state the paths positively"));
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * {@code InterceptorRegistry.getInterceptors()} is protected, and a subclass is the sanctioned
     * way to read it — the alternative, reaching into {@code AbstractHandlerMapping}'s adapted
     * interceptors, would mix in every other configurer's registrations and stop being an assertion
     * about these two classes.
     */
    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    /**
     * The {@link PrincipalThrottleInterceptor}s the real handler mapping puts in front of this URI,
     * <strong>in chain order</strong>. Goes through {@code getHandler} rather than reading the
     * registered patterns, so what is asserted is the matching the {@code DispatcherServlet} will
     * actually do.
     */
    private List<PrincipalThrottleInterceptor> throttlesFor(String method, String uri)
            throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        // The DispatcherServlet normally does this; without it a PathPattern-based mapping has no
        // parsed path to look up.
        ServletRequestPathUtils.parseAndCache(request);
        var chain = handlerMapping.getHandler(request);
        assertThat(chain).as("no handler matched the probe %s %s", method, uri).isNotNull();
        return chain.getInterceptorList().stream()
                .filter(PrincipalThrottleInterceptor.class::isInstance)
                .map(PrincipalThrottleInterceptor.class::cast)
                .toList();
    }

    private static boolean mustBeThrottled(Class<?> beanType) {
        if (EXEMPT.contains(beanType)) {
            return false;
        }
        var packageName = beanType.getPackageName();
        return THROTTLED_PACKAGES.stream()
                .anyMatch(p -> packageName.equals(p) || packageName.startsWith(p + "."));
    }

    /** A concrete URI for a mapped pattern: every {@code {var}} becomes a UUID. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^/}]*}", UUID.randomUUID().toString());
    }

    private static String httpMethod(Set<RequestMethod> methods) {
        return methods.isEmpty() ? "GET" : List.copyOf(methods).get(0).name();
    }
}
