package com.hamstrack.report;

import com.hamstrack.common.ratelimit.PrincipalThrottleInterceptor;
import com.hamstrack.common.ratelimit.WriteRateLimitConfig;
import com.hamstrack.issue.ratelimit.PlanningRateLimitConfig;
import com.hamstrack.report.ratelimit.ReportRateLimitConfig;
import com.hamstrack.report.ratelimit.ReportRateLimiter;
import com.hamstrack.search.ratelimit.SearchRateLimitConfig;
import com.hamstrack.search.ratelimit.SearchRateLimiter;
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
 * <p>Every throttle here is a path binding, which is the right shape: a new report under
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
     * Handlers on the occupancy-bounded surface that may be rate-bounded without being
     * OCCUPANCY-bounded (HD-182).
     *
     * <p><strong>Empty, and it is a different question from {@link #EXEMPT}</strong>, which is why
     * it is a different set: a handler can legitimately be cheap enough per call to want no rate
     * budget and still hold a connection for seconds, or the reverse. Anything added here needs a
     * reason that survives one question — <em>does this handler hold a connection while it works,
     * and if so why may it hold one outside the share?</em>
     */
    private static final Set<Class<?>> CONCURRENCY_EXEMPT = Set.of();

    /**
     * Return types that make a handler asynchronous, i.e. that split one request across two
     * dispatches. Matched by ASSIGNABILITY against the raw return type and every one of its type
     * arguments, so {@code SseEmitter} is caught through {@code ResponseBodyEmitter} and
     * {@code ResponseEntity<StreamingResponseBody>} is caught through its argument — a name match
     * would miss both.
     *
     * <p><strong>A return type is not the only way to go asynchronous</strong>: a handler that
     * calls {@code request.startAsync()} itself, or injects a {@code jakarta.servlet.AsyncContext},
     * splits the same dispatch while declaring an ordinary return type, and is invisible to
     * everything below. There is no such handler in this product and no cheap structural way to
     * find one — so if you are writing it, this list is not what will stop you, and
     * {@code PrincipalThrottleInterceptor}'s account of what a permit means across the gap is the
     * paragraph you owe an answer.
     */
    private static final List<Class<?>> ASYNC_RETURN_TYPES = List.of(
            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class,
            org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class,
            org.springframework.web.context.request.async.DeferredResult.class,
            org.springframework.web.context.request.async.WebAsyncTask.class,
            java.util.concurrent.Callable.class,
            java.util.concurrent.CompletionStage.class);

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
                 javadoc on ReportRateLimitConfig.REPORTS_PATH / INSIGHTS_PATH for the reports side;
                 common/config/PlanningProperties.java for the planning side, which additionally
                 carries the DERIVATION of its number (the SPA traffic model) — kept once, there,
                 so a future reader can CHECK 240 rather than re-guess it
              2. src/main/resources/application.properties               — the app.reports.*,
                 app.search.* and app.planning.* comment blocks AND the numbered list above
                 app.rate-limit.enabled
              3. .env.prod.example                                       — the REPORTS_REQUESTS_PER_MINUTE,
                 SEARCH_REQUESTS_PER_MINUTE and PLANNING_REQUESTS_PER_MINUTE blocks (this file is
                 the one missed last time), AND the master-switch section's list of what
                 RATE_LIMIT_ENABLED=false turns off
              4. docs/self-hosting.md                                    — the operator table rows and
                 the "this one is node-local too" prose below them (deliberately de-ordinalised:
                 the ordinals went stale one entry before the list did, so do not renumber it back)
              5. docs/api-dc.md AND docs/api-cloud.md                    — the env-var table, the
                 "surfaces with throttles of their own" section, and each endpoint's 429 note
              6. src/main/frontend/public/openapi.yaml                   — the info description and the
                 429 responses (NOT the copy in src/main/resources/static, which the Vite build
                 overwrites from public/)
              7. docs/project-state.md                                   — the "Config:" line of the
                 search section AND the bulleted budget list under "Throttling, config and the
                 rule behind them", which enumerates every per-principal pot
              8. docs/hql-search-maintainers-guide.md                    — EVERY claim in that file
                 about what an endpoint on the search surface inherits, which is NOT one row: the
                 package table states it three times (the controller row, the filter/** row, the
                 ratelimit/** row), the cookbook section tells a maintainer what a new endpoint
                 gets for free, and the saved-filter section repeats it per operation. The last
                 binding change had to edit five of them while this line named one — which is the
                 exact shape of the gap it produced: the named row got fixed and the other four
                 did not
              9. src/main/frontend/src/api.ts                            — the 429 comments on the
                 search, insights, saved-filter, storage-breakdown, attachment-upload and the
                 three BACKLOG callers. And on the planning surface the CLIENT owes one more
                 thing than a comment: a 429 of any kind on a section refresh must NOT invalidate
                 the aggregate, or a refusal on the cheap request provokes the expensive one —
                 the same asymmetry PlanningThrottleParityTest forbids, arriving through the
                 error path instead of through the registration (components/sprints.tsx)
             10. src/main/java/.../common/config/WriteProperties.java    — the two write budgets
                 (requests and BYTES), and common/config/StorageQuotaProperties.java for the quota
                 that is deliberately NOT under the master switch
             11. src/main/resources/application.properties               — the app.write.* and
                 app.storage.quota.* blocks, AND both lists in the app.rate-limit.enabled block:
                 the numbered enumeration of what it turns off, and the "deliberately outside it"
                 list (a list, not a count — a number goes stale one entry before the list does,
                 and this very line used to carry one)
             12. src/main/resources/application-cloud.properties         — the 10GB quota override
             13. .env.prod.example                                       — the Write-budget and
                 Storage-quota sections
             14. observability/grafana/provisioning/alerting/rules.yml   — the four Storage* rules
             15. src/main/java/.../common/config/ExpensiveReadProperties.java — the four occupancy
                 numbers, the property they deliver, and the connection-seconds derivation kept
                 once as HISTORY (it is why a rate could not deliver that property, not a live
                 sizing relation)
             16. src/main/java/.../common/config/PoolShareConsistency.java   — the two hard rules
                 and the 60%-of-the-pool WARN; and DatabaseTimeoutConsistency, whose sizing WARN
                 names the pool and must not claim to name the only other dial
             17. src/main/resources/application.properties + .env.prod.example — the
                 app.expensive-read.* block and the EXPENSIVE_READ_* section, and the
                 DB_POOL_MAX_SIZE block, which must say that a share of the pool is reserved
             18. docs/observability.md + observability/.../rules.yml    — every metric row and
                 every alert rule this surface has (the in-flight GAUGE, the force-release
                 counter, the ratelimit kinds, and the rules built on them), plus the rule
                 TABLE in the doc, which is a second copy of the same list. A metric row that
                 prescribes an action needs a rule that prompts it, or the row must say that
                 nothing watches it — the force-release counter was covered by consequence
                 alone for a slice
             19. ops/loadtest/k6/probes.js + ops/loadtest/RESULTS-TEMPLATE.md — probe P1's stated
                 prediction, which this product DELIBERATELY falsified: a harness that keeps
                 documenting a prediction the product set out to break reads as the product being
                 wrong. AND THE VICTIM CLASS ITSELF (HD-174): P1's criterion is "the victim's
                 browse class stays inside its target", so a budgeted endpoint inside that class
                 makes a pass stop meaning what it says. k6/lib/classes.js + k6/browse.js +
                 README.md's targets table and browse rationale are part of this entry — the
                 rationale sentence must describe how the CLASS is composed ("browse is composed
                 only of reads the product does not budget"), never make a claim about the
                 product ("nothing in the product budgets ordinary browsing"), which was true
                 when written and false one release later
             20. THE THREE BOUNDS ON HOW LONG A PERMIT MAY BE HELD, if you touch acquisition or
                 release: TomcatUploadTimeoutCustomizer (in the app, so every deployment has it),
                 PerPrincipalInFlightLimit.sweepStalePermits + its metric, and read_body in the
                 Caddyfile (our edge only, and one of the two files a deploy never syncs). A permit
                 is taken BEFORE the request body is read and given back AFTER the response is
                 written, so the client paces part of its life. WHICH OF THE THREE MAKES THAT PART
                 FINITE IS THE WATCHDOG, AND ONLY THE WATCHDOG — measured, twice: Tomcat's default
                 does not remove the read timeout for the body, it leaves it at connectionTimeout
                 (60 s, and whatever an operator sets server.tomcat.connection-timeout to), so the
                 customizer tightens that gap to 20 s and pins it independently of that dial. That
                 is a packet rate an attacker must sustain, not an end to the hold. Do not write
                 the "unbounded by default" story back into any of them: it is wrong in the
                 direction that stops the next reader looking at the layer that actually bounds it

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
            do.

            AND THERE IS NOW A THIRD AXIS (HD-191). Handing bytes to FileStorage is neither a \
            path question nor a mail question: the upload-byte budget's cost is \
            MultipartFile.getSize(), which an interceptor cannot see, and the workspace storage \
            quota needs a RESOLVED workspace, so neither is expressible as a registered pattern \
            and neither appears in the seal above. Their seal is \
            com.hamstrack.issue.AttachmentDoorsTest, which inverts the axis to STORAGE CALL \
            SITES: every call site of FileStorage.store is preceded, in the same METHOD, by a \
            quota reservation and a byte spend.

            The fourth axis, and the reason the write budget is not fully sealed here: the write \
            registration is METHOD-CONDITIONED, and a path-shaped assertion cannot express \
            "every mutating handler is covered, every read on the same path is not". \
            com.hamstrack.common.ratelimit.WriteThrottleCoverageTest owns that, over every \
            mutating handler under /api/workspaces/**, with an EXEMPT set populated by category \
            and one written reason each.

            So if you are adding an expensive surface, ask THREE questions — is it a path that \
            needs a budget, does it send mail to an address the caller chose, and does it hand \
            bytes to FileStorage? And if it is a WRITE, ask the fourth: is it under a \
            method-conditioned binding, or does it belong in WriteThrottleCoverageTest's EXEMPT \
            set with a reason?

            AND THE FIFTH (HD-182), WHICH IS ABOUT A DIFFERENT AXIS AGAIN — not how OFTEN it may \
            be asked for, but how many may be RUNNING: does it hold a connection while it works, \
            and if so is it inside the occupancy share? A rate budget spends the same unit whether \
            a request takes 8 ms or 8 s, so its protection evaporates exactly as the instance \
            slows down; app.expensive-read.max-in-flight is what keeps the interactive API's \
            connections out of one surface's reach. Registering the path in ANY of the \
            *RateLimitConfig classes buys BOTH controls in one edit — that is why no configurer \
            has a second pattern list for occupancy — and \
            everyExpensiveReadHandlerIsAlsoConcurrencyBounded is what fires when a new handler \
            lands with only half of them. If it is asynchronous, \
            noExpensiveReadHandlerIsAsynchronous fires instead, and says what the permit is owed. \
            A NEW SURFACE JOINS THE EXISTING SHARE AND DOES NOT GET ONE OF ITS OWN (ADR-0031): a \
            second ceiling turns ExpensiveReadShare's derive-from-the-pool default into a \
            PARTITION, which on a pool of 4 is 1 permit per surface and serialises a whole \
            surface instance-wide — and a literal that must sit below the pool has crash-looped \
            every small self-host once already.

            AND THE SIXTH (HD-174): IS IT A PLANNING READ — does it assemble a project's sections, \
            or run an unconditional, cap-blind aggregation over one of them? A budget is earned by \
            the work a handler does, not by where it is mounted; register it in \
            PlanningRateLimitConfig, which carries both controls on one interceptor and one \
            pattern (/api/workspaces/*/projects/*/backlog/**). Its own rate pot at 240/min rather \
            than the reports pot, because a card dragged ACROSS sections costs two section \
            refreshes and 60/min would refuse an ordinary grooming session inside three minutes — \
            a 429 mid-drag reads as a product defect, where a 429 on a report is a delay. NOTE \
            WHAT IS DELIBERATELY NOT ON IT, so the next reader does not "fix" the omission: \
            GET .../issues (board + list) and GET .../sprints are a WEAKER case — not because \
            their work is bounded by what they return (no filtered, ordered, capped query's work \
            is) but because neither runs an unconditional project-wide aggregation. Extending the \
            pattern to them is an argument to be made on their own worst case. The planning \
            surface's own seal is PlanningThrottleParityTest, which derives its handler set from \
            the pattern constant AND from the registrations rather than from URI literals, so a \
            fourth planning read inherits the claim by existing.
            """;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ReportRateLimitConfig reportRateLimitConfig;

    @Autowired
    SearchRateLimitConfig searchRateLimitConfig;

    /** HD-191 — the third configurer, and the first one whose binding is method-conditioned. */
    @Autowired
    WriteRateLimitConfig writeRateLimitConfig;

    /**
     * HD-174 — the fourth configurer, and the first one added to this seal <em>after</em>
     * {@link #expensiveReadSurface()} started deriving its scope from the registrations. That
     * derivation is why this ticket was a small diff: the three {@code BacklogController} handlers
     * entered {@link #everyExpensiveReadHandlerIsAlsoConcurrencyBounded()} and
     * {@link #noExpensiveReadHandlerIsAsynchronous()} with no edit to either tripwire, and only
     * the sealed set below had to change.
     */
    @Autowired
    PlanningRateLimitConfig planningRateLimitConfig;

    /**
     * EVERY configurer in the context, not the three above. {@link #expensiveReadSurface()} derives
     * what the occupancy bound covers from these, so a fourth configurer — or a bound moved between
     * two existing ones — is in scope by existing rather than by being added to a list here.
     */
    @Autowired
    List<WebMvcConfigurer> webMvcConfigurers;

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
     * <strong>Every expensive read is also bounded by OCCUPANCY, not only by rate</strong>
     * (HD-182, AC-8).
     *
     * <p>The sibling above asks whether a {@link PrincipalThrottleInterceptor} is in front of each
     * handler, and until HD-182 that was the whole coverage question. It stopped being sufficient
     * the moment a second registration of the SAME TYPE deliberately did not take an occupancy
     * bound: the write budget uses this interceptor and is out of scope for the bulkhead by a
     * reasoned decision (writes are short, already bounded by {@code lock_timeout} +
     * {@code statement_timeout}, and an occupancy bound there would refuse an SPA saving several
     * inline edits at once). So "there is a PrincipalThrottleInterceptor here" no longer implies
     * "this handler is concurrency-bounded", and a type question has to become a
     * <em>which-controls</em> question.
     *
     * <p>Same inverted polarity as the sibling: everything on the surface is covered unless it is
     * in {@link #CONCURRENCY_EXEMPT} with a written reason. Without this refinement the coverage
     * assertion would keep passing while covering half of what its name claims, which is the
     * failure this file exists to have deleted.
     *
     * <p><strong>And the surface is {@link #expensiveReadSurface()}, not a package list</strong>
     * (HD-182 review). The scope of a tripwire has to be the scope of the bound, or the two agree
     * only until somebody mounts a bounded endpoint somewhere new — which had already happened:
     * the storage breakdown is on the bound and lives in {@code workspace.controller}, so it was
     * invisible to both this test and the async one while every document counted it as covered.
     */
    @Test
    void everyExpensiveReadHandlerIsAlsoConcurrencyBounded() throws Exception {
        var unbounded = new LinkedHashSet<String>();
        var probed = new ArrayList<String>();

        for (var handler : expensiveReadSurface()) {
            probed.add(handler.method() + " " + handler.uri());
            // appliesTo(method) as well as "is in the chain" (HD-182 review): an occupancy bound
            // may be registered with a method condition exactly as the write budget is, and a
            // membership question would then report a bound that never fires on this handler.
            // Nothing on this surface is method-conditioned today — which is precisely the state
            // the RATE axis was in before WriteThrottleCoverageTest had to be written.
            boolean bounded = throttlesFor(handler.method(), handler.uri()).stream()
                    .filter(throttle -> throttle.appliesTo(handler.method()))
                    .anyMatch(throttle -> throttle.concurrencyBound() != null);
            if (!bounded) {
                unbounded.add(handler.describe());
            }
        }

        assertThat(probed)
                .as("the expensive-read surface came back almost empty, so this test is guarding "
                    + "an empty set — a controller package moved, or no registration carries an "
                    + "occupancy bound any more")
                .hasSizeGreaterThan(5);

        assertThat(unbounded)
                .as("these handlers are rate-bounded and NOT occupancy-bounded. A rate budget "
                    + "spends the same unit whether a request takes 8 ms or 8 s, so its protection "
                    + "evaporates precisely as the instance slows down — which is how one "
                    + "principal, inside their documented allowance, saturated a whole replica "
                    + "(HD-182, probe P1). Register the path in ReportRateLimitConfig or "
                    + "SearchRateLimitConfig, which carry BOTH controls on one interceptor; if it "
                    + "genuinely must not hold a share of the pool, put it in CONCURRENCY_EXEMPT "
                    + "with the reason, not here. Note the scanned set is the union of the "
                    + "throttled PACKAGES and whatever the bound is actually registered in front "
                    + "of, so a handler can be in scope here while living in neither package — "
                    + "the storage breakdown is, today."
                    + PROPAGATION_CHECKLIST)
                .isEmpty();
    }

    /**
     * <strong>No handler on a concurrency-bounded surface is asynchronous</strong> (HD-182, AC-10)
     * — the async-leak tripwire, and a CATEGORY test rather than a list because the risk is a path
     * nobody enumerated.
     *
     * <p>A permit is acquired in {@code preHandle} and released in {@code afterCompletion}, and
     * Spring does <strong>not</strong> call {@code afterCompletion} when a handler starts async
     * processing — it calls {@code afterConcurrentHandlingStarted}, then runs the whole interceptor
     * chain again on the ASYNC dispatch. This repository already carries that scar on the security
     * side ({@code DispatcherType.ASYNC} in {@code SecurityConfig}). {@link
     * PrincipalThrottleInterceptor} handles the seam deliberately — only a {@code REQUEST} dispatch
     * acquires, and both terminal callbacks release — so an async handler here would not LEAK; it
     * would silently make the bound weaker than every document says it is, because the
     * asynchronous part of the request would occupy nothing.
     *
     * <p><strong>And the permit WATCHDOG's own trade depends on this test being green</strong>
     * (HD-182 review). {@code PerPrincipalInFlightLimit.sweepStalePermits} force-releases a permit
     * whose request is still running, and calls that a bounded over-issue costing a worker and some
     * heap but never a CONNECTION. That holds only while every handler here is synchronous: such a
     * handler has committed and returned its connection to the pool long before a request is old
     * enough to be swept, so what the over-issue races against is a client-paced response write. A
     * streaming handler holds its connection across exactly the stretch the watchdog gives the
     * permit away in, and the bulkhead would then over-issue the one resource it exists to reserve.
     * The two are coupled; the watchdog's javadoc says so, and this is the test that keeps it true.
     *
     * <p>So the tripwire keeps this surface synchronous, and makes a future streaming report a
     * deliberate edit that must decide what a permit means across the async gap — and what a forced
     * release means while a connection is genuinely held — instead of inheriting either answer by
     * silence.
     */
    @Test
    void noExpensiveReadHandlerIsAsynchronous() throws Exception {
        var asynchronous = new LinkedHashSet<String>();
        var probed = new ArrayList<String>();

        for (var handler : expensiveReadSurface()) {
            var handlerMethod = handler.handler();
            probed.add(handler.method() + " " + handler.uri());
            for (var candidate : returnTypes(handlerMethod.getMethod().getGenericReturnType())) {
                for (var async : ASYNC_RETURN_TYPES) {
                    if (async.isAssignableFrom(candidate)) {
                        asynchronous.add(handlerMethod.getBeanType().getSimpleName() + "."
                                         + handlerMethod.getMethod().getName() + " -> "
                                         + candidate.getSimpleName());
                    }
                }
            }
        }

        assertThat(probed)
                .as("the expensive-read surface came back almost empty, so this tripwire is "
                    + "guarding nothing — a controller package moved, or no registration carries "
                    + "an occupancy bound any more")
                .hasSizeGreaterThan(5);

        assertThat(asynchronous)
                .as("""

                    AN ASYNCHRONOUS HANDLER APPEARED ON THE EXPENSIVE-READ SURFACE, AND IT OWES \
                    THE PERMIT AN ANSWER.

                    Requests here hold one permit out of app.expensive-read.max-in-flight for as \
                    long as they run. Spring does not call afterCompletion when a handler starts \
                    async processing (afterConcurrentHandlingStarted runs instead, and the whole \
                    interceptor chain runs again on the ASYNC dispatch), so \
                    PrincipalThrottleInterceptor releases the permit AT THE ASYNC GAP: nothing \
                    leaks, and the asynchronous part of your request occupies NOTHING. For a \
                    streaming report that is precisely backwards — the streaming is the expensive \
                    part.

                    Decide, and write the decision down: either keep the handler synchronous (a \
                    report that buffers is what every other one here does), or extend the permit \
                    across the gap and prove the release on the async dispatch, the timeout \
                    dispatch AND the error dispatch. Do not simply add the handler and leave this \
                    test edited.""")
                .isEmpty();
    }

    /**
     * <strong>Every pattern the occupancy bound is registered on has a handler the tripwires above
     * actually scan</strong> (HD-182 review) — the seal on the derivation itself.
     *
     * <p>{@link #expensiveReadSurface()} is a union, and a union is only as good as its second
     * half. If a bound pattern matched no scanned handler, both tripwires would be back to the
     * package list while reading as though they covered the whole surface — which is the state
     * this review found: the storage breakdown was on the bound, in a package neither list named,
     * and therefore invisible to both.
     *
     * <p>Phrased over patterns rather than over the handler that exposed it, so it stays true as
     * endpoints move: it also fails on a registration whose pattern matches nothing at all, which
     * is dead configuration wearing a bulkhead's clothes.
     */
    @Test
    void everyOccupancyBoundedPatternHasAHandlerOnTheScannedSurface() {
        var scanned = expensiveReadSurface();

        for (var pattern : concurrencyBoundedPatterns()) {
            assertThat(scanned)
                    .as("no scanned handler matches the occupancy-bounded pattern %s — either the "
                        + "pattern matches nothing (dead configuration) or the scan cannot see the "
                        + "handlers it covers, which puts both tripwires back to the package list "
                        + "while every document says otherwise", pattern.getPatternString())
                    .anyMatch(handler -> pattern.matches(PathContainer.parsePath(handler.uri())));
        }
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
     * <strong>The throttled path set is sealed — every pattern each configurer registers, asserted
     * exactly</strong> (HD-140 R6 round 4, item 3; extended by HD-191).
     *
     * <p>Deliberately not introduced by a count. The sentence this replaced said "exactly four
     * patterns, two per budget", and it was false the moment a third budget arrived — a number
     * goes stale one entry before the list does, which is a rule this repository has already paid
     * for twice.</p>
     *
     * <p>Nor does the assertion below seal every LIMITER; it seals the path bindings. Three
     * limiters in the product are not path bindings at all (the recipient-keyed mail ceilings, the
     * upload-byte budget, the storage quota) and one is a path binding whose method condition this
     * cannot see. {@link #PROPAGATION_CHECKLIST} names the sibling seal for each.</p>
     *
     * <p>Original argument, unchanged:</p>
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
                    + " Nor is it only REPORTS: the workspace storage BREAKDOWN is on this budget"
                    + " (HD-191), because it is a grouped aggregate over every attachment row in"
                    + " the workspace — O(workspace content), which is this pot's denomination."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder(
                        "/api/workspaces/*/projects/*/reports/**",
                        "/api/workspaces/*/search/insights",
                        "/api/workspaces/*/storage/projects");

        assertThat(registeredPatterns(searchRateLimitConfig))
                .as("the SEARCH budget covers a different set of paths than the documents say."
                    + " Note in particular that it is NOT only .../search/**: saved filters are on"
                    + " it because HQL validation is search-surface work wherever it is mounted,"
                    + " and that is the addition the last round's documents were missing."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder(
                        "/api/workspaces/*/search/**",
                        "/api/workspaces/*/filters/**");

        assertThat(registeredPatterns(writeRateLimitConfig))
                .as("the WRITE budget covers a different set of paths than the documents say."
                    + " It is ONE pattern on purpose — the mutating content surface as a category,"
                    + " not a list of today's endpoints — and it is deliberately NOT"
                    + " /api/workspaces/**, which would charge one pot for administrative writes,"
                    + " membership writes and saved-filter writes that are already bounded"
                    + " elsewhere or on a different axis. It is also the first registration in this"
                    + " file whose binding is METHOD-CONDITIONED, so this assertion is only half of"
                    + " its seal: WriteThrottleCoverageTest owns the other half, on the method axis."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder("/api/workspaces/*/projects/*/issues/**");

        assertThat(registeredPatterns(planningRateLimitConfig))
                .as("the PLANNING budget covers a different set of paths than the documents say."
                    + " It is ONE pattern on purpose — the planning surface as a category, so the"
                    + " aggregate GET .../backlog and every section read under it are budgeted by"
                    + " the same registration and a fourth planning read inherits both controls"
                    + " the moment its @GetMapping exists. It is deliberately NOT"
                    + " /api/workspaces/*/projects/*/**, which would charge one pot for issues,"
                    + " versions, components and config that are bounded elsewhere or on a"
                    + " different axis; and it deliberately does not reach GET .../issues or"
                    + " GET .../sprints, which are a WEAKER case because neither runs an"
                    + " unconditional project-wide aggregation — not because their work is bounded"
                    + " by what they return, which is not true of any filtered, ordered, capped"
                    + " query."
                    + PROPAGATION_CHECKLIST)
                .containsExactlyInAnyOrder("/api/workspaces/*/projects/*/backlog/**");
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

    /**
     * <strong>The handlers the two tripwires above scan — derived from the RUNTIME, so their scope
     * is the bound's scope</strong> (HD-182 review).
     *
     * <p>It is the union of two questions, and both halves are load-bearing:
     *
     * <ul>
     *   <li><strong>Every handler under {@link #THROTTLED_PACKAGES}</strong>, which is what makes
     *       {@code everyExpensiveReadHandlerIsAlsoConcurrencyBounded} able to FAIL: a new report
     *       controller that lands with a rate budget and no occupancy bound is caught because it is
     *       in scope by package, not by being bounded.</li>
     *   <li><strong>Every handler an occupancy-bounded interceptor is actually in front of</strong>,
     *       whatever package it lives in. This half is why the async tripwire now sees
     *       {@code WorkspaceStorageController}: the storage breakdown is on the bound
     *       ({@code /api/workspaces/*}{@code /storage/projects} is registered in
     *       {@code ReportRateLimitConfig}) while living in {@code workspace.controller}, so a
     *       package list could not see it — and a {@code StreamingResponseBody} on a grouped
     *       aggregate over every attachment row is the most plausible way this surface goes
     *       asynchronous. It passed green before this, while making the bound weaker than every
     *       document claims.</li>
     * </ul>
     *
     * <p>The second half is read from the interceptor registrations of <em>every</em>
     * {@link WebMvcConfigurer} in the context rather than from the ones this class autowires — a
     * count here would go stale one configurer before the list does, which is why this sentence
     * says "every" — so a new configurer is in scope by existing. That is the rule this file
     * keeps re-learning: a
     * throttle is earned by the work a handler does, not by where it is mounted, and two lists that
     * happen to agree are one commit away from not agreeing.
     */
    private List<ProbedHandler> expensiveReadSurface() {
        var boundedPatterns = concurrencyBoundedPatterns();
        var surface = new ArrayList<ProbedHandler>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var beanType = entry.getValue().getBeanType();
            if (CONCURRENCY_EXEMPT.contains(beanType)) {
                continue;
            }
            var patterns = Objects.requireNonNull(entry.getKey().getPathPatternsCondition(),
                                                  "a handler with no path patterns to probe");
            for (var pattern : patterns.getPatterns()) {
                var method = httpMethod(entry.getKey().getMethodsCondition().getMethods());
                var uri = concrete(pattern.getPatternString());
                boolean bound = boundedPatterns.stream()
                        .anyMatch(bounded -> bounded.matches(PathContainer.parsePath(uri)));
                if (mustBeThrottled(beanType) || bound) {
                    surface.add(new ProbedHandler(entry.getValue(), method, uri,
                                                  pattern.getPatternString()));
                }
            }
        }
        return surface;
    }

    /**
     * Every path pattern an occupancy-bounded {@link PrincipalThrottleInterceptor} is registered
     * on, across every configurer in the context.
     *
     * <p>Read from the registrations rather than by probing every mapped handler through
     * {@code getHandler}: a probe has to satisfy a mapping's {@code consumes}, {@code produces},
     * {@code params} and {@code headers} conditions before a chain comes back at all, so a
     * multipart or content-negotiated handler would silently answer "no chain" and drop out of the
     * scanned set — the exact hole this method exists to close, reintroduced by the mechanism that
     * closed it.
     */
    private Set<PathPattern> concurrencyBoundedPatterns() {
        var parser = new PathPatternParser();
        var patterns = new LinkedHashSet<PathPattern>();

        for (var configurer : webMvcConfigurers) {
            var registry = new ExposedRegistry();
            configurer.addInterceptors(registry);
            for (var registered : registry.registered()) {
                var interceptor = registered instanceof MappedInterceptor mapped
                        ? mapped.getInterceptor()
                        : registered;
                if (!(interceptor instanceof PrincipalThrottleInterceptor throttle)
                        || throttle.concurrencyBound() == null) {
                    continue;
                }
                assertThat(registered)
                        .as("an occupancy bound was registered with NO path patterns, so a share of "
                            + "the connection pool is spent on EVERY request in the application")
                        .isInstanceOf(MappedInterceptor.class);
                var includes = Objects.requireNonNull(
                        ((MappedInterceptor) registered).getIncludePathPatterns(),
                        "an occupancy bound was registered with excludes or method conditions but "
                        + "no INCLUDE patterns — state the paths positively");
                for (var include : includes) {
                    patterns.add(parser.parse(include));
                }
            }
        }

        assertThat(patterns)
                .as("no interceptor registration in this context carries an occupancy bound, so "
                    + "both tripwires below would be guarding the package list alone and the "
                    + "expensive-read surface would be unbounded")
                .isNotEmpty();
        return patterns;
    }

    /** One mapped handler, with the concrete URI the probes use for it. */
    private record ProbedHandler(HandlerMethod handler, String method, String uri, String pattern) {
        String describe() {
            return method + " " + pattern + "  (" + handler.getBeanType().getSimpleName() + ")";
        }
    }

    /** A return type and every type argument it carries, as raw classes. */
    private static List<Class<?>> returnTypes(java.lang.reflect.Type type) {
        var found = new ArrayList<Class<?>>();
        if (type instanceof Class<?> raw) {
            found.add(raw);
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            found.addAll(returnTypes(parameterized.getRawType()));
            for (var argument : parameterized.getActualTypeArguments()) {
                found.addAll(returnTypes(argument));
            }
        }
        return found;
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
