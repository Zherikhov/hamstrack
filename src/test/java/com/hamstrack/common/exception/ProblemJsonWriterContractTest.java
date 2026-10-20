package com.hamstrack.common.exception;

import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.common.security.ContentSecurityPolicy;
import com.hamstrack.common.security.CspReportGuardFilter;
import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.FixedWindow;
import com.hamstrack.common.testsupport.Population;
import com.hamstrack.common.testsupport.ProductionBytecode;
import com.hamstrack.common.testsupport.Unique;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>Every refusal written before the dispatcher is a problem document, with the label, the
 * encoding and the shape its siblings have</strong> (HD-305).
 *
 * <p>Three doors in this product answer with an RFC 9457 body that no message converter rendered,
 * and before this seal existed each had decided for itself what to call it and how to produce it.
 * The measured state on 2026-10-18, against the running application:
 *
 * <pre>
 * 401 /api/workspaces   Content-Type: application/json
 *                       {"type":null,"title":"Unauthorized","status":401,
 *                        "detail":"Authentication required","instance":null,"properties":null}
 * 429 /api/auth/login   Content-Type: application/problem+json;charset=ISO-8859-1
 *                       {"type":"about:blank",...,"detail":"Too many requests ? try again later"}
 * 400 /api/auth/login   Content-Type: application/problem+json          (MVC's own, for reference)
 *                       {"detail":"...","instance":"/api/auth/login","status":400,"title":"Bad Request"}
 * </pre>
 *
 * <p>So the ticket's premise — "the 401 has the wrong label" — understated it twice. The 401 was a
 * different <em>document</em>: a bare Jackson 2 {@code ObjectMapper} constructed inside the entry
 * point is not the mapper MVC renders with, so {@code ProblemDetailJacksonMixin} never applied and
 * three null members no other refusal emits went out with it. And the 429, whose label was right,
 * went out through a {@code PrintWriter} with no charset set — ISO-8859-1 by default — which turned
 * the em dash in the only sentence it carries into a literal {@code ?} on the wire.
 *
 * <h2>Four arms, because no one of them can see what the next one sees</h2>
 * <ol>
 *   <li><strong>The roster</strong> ({@link #theRosterOfPreMvcDoorsIsWhatDoorsEnumerates}) —
 *       {@link Doors#problemJsonWriters()} narrowed to the pre-MVC kinds, floor 5, the same
 *       narrowing {@code DoorsHarnessTest} floors. Reflection sees <em>places that can write</em>;
 *       its own javadoc says it cannot see whether they do, or with which content type, and that a
 *       static helper writing on a filter's behalf ({@link DatabaseBusyRefusal}) is not a member at
 *       all. That is why the ticket's "a seal over {@code problemJsonWriters()}" could not be built
 *       as written, and why this arm is a roster rather than a verdict.</li>
 *   <li><strong>The property</strong> ({@link #everyPreMvcDoorFramesItsBodyThroughTheOneWriter}) —
 *       the framing sites from <em>bytecode</em>, so the question is asked of the typed call and not
 *       of a variable name or a comment: who calls
 *       {@code setContentType}/{@code setCharacterEncoding}/{@code setContentLength}/{@code getWriter}/
 *       {@code getOutputStream} on a response. One site may exist, {@link ProblemJson#write}, plus
 *       one pinned exception whose pin is asserted below rather than written in prose. This arm
 *       cannot see what goes on the wire — a writer could call the right methods with the wrong
 *       arguments.</li>
 *   <li><strong>The wire</strong> ({@link #theRefusalsAPreMvcDoorWritesAreFramedAsProblemDocuments}
 *       and {@link #aNonAsciiDetailSurvivesTheWire}) — the 401 and the 429 taken from MockMvc as
 *       bytes. This is the only arm that would have caught either defect, and it carries its own
 *       plant: the pre-HD-305 writer is reconstructed from literals in
 *       {@link #thePredicateRefusesThePreHd305Writer} and the predicate must refuse it.</li>
 *   <li><strong>The mapper</strong> ({@link #theOneWriterRendersWhatMvcRenders}) — that the injected
 *       mapper is the mixin-carrying one, and that {@link DatabaseBusyRefusal}'s constant is
 *       character-for-character what it renders. {@code DatabaseBusyRefusalTest} separately pins
 *       that constant against what MVC actually sends under a starved pool, so the two together say
 *       {@code ProblemJson} renders what MVC renders — a claim neither test can make alone.</li>
 * </ol>
 *
 * <h2>What this seal does not cover, stated</h2>
 * The 20 {@code @ExceptionHandler} members of the same population are outside it: they return a
 * {@code ProblemDetail} and the content type is negotiated by
 * {@code AbstractJacksonHttpMessageConverter.getMediaTypesForProblemDetail}, which is Spring's
 * decision and not this product's. The 503's two writers are sealed by
 * {@code DatabaseBusyRefusalTest}, which holds the whole connection pool to starve it — a fixture
 * this class has no business building a second time; the pin in arm 4 is the half of it that needs
 * no pool. And a refusal that writes
 * <em>no</em> body ({@code CspReportGuardFilter}, by design — a report sender is told nothing) is
 * not excused by a sentence here: {@link #classifyPreMvcDoors()} puts it in the
 * {@code "writes no body"} bucket from its own bytecode, and that bucket is what both arms that
 * excuse it assert, so the day it grows a body it becomes a member of the rule.
 *
 * <h2>The 429's security headers: a state this class now holds, instead of a sentence</h2>
 * Measured on the running application, before and after this change: the 401 and the 400 carry the
 * full block ({@code nosniff}, {@code DENY}, {@code no-store}, the CSP), and the 429 carries
 * <em>none</em> of it, because {@code AuthRateLimitFilter} sits at {@code HIGHEST_PRECEDENCE} and
 * returns without ever entering the security chain that writes them. HD-264 closed the same gap for
 * the 503 by restoring the block by hand in {@link DatabaseBusyRefusal}; the honest closure here is
 * <em>one</em> shared block for all three pre-chain doors, which is production code in three filters
 * and a ticket of its own.
 *
 * <p>Until then the gap is not exploitable, and the reason is three properties of this door rather
 * than a judgement about it. Each was one line from being removed silently — so each is asserted
 * here, and the block's absence is asserted beside them
 * ({@link #the429CarriesNoSecurityBlockAndIsSafeWithoutOne}) so that it is a <em>witnessed</em>
 * state and not an unexamined one:
 * <ul>
 *   <li><strong>POST only</strong> ({@link #theBudgetDoorRefusesOnlyAPost}) —
 *       {@code shouldNotFilter} is one {@code ||} away from covering GET, and a GET to a budgeted
 *       path with the budget spent answers {@code 200 text/html} <em>with</em> the full block
 *       (measured on the wire), i.e. the document a missing {@code nosniff} would matter for is one
 *       the browser never gets from this door;</li>
 *   <li><strong>a constant body, nothing caller-derived</strong>
 *       ({@link #the429sDocumentIsTheConstantNothingCallerDerived}) —
 *       {@code RateLimitedException(String, long)} exists and three call sites already hand it
 *       composed text, so "this door sends a constant" is a fact about one {@code catch} and not
 *       about the exception type;</li>
 *   <li><strong>explicitly framed</strong> — type, charset and length, the first two by the shared
 *       predicate and the length in the header arm; nothing is left for a sniffing heuristic to
 *       guess at.</li>
 * </ul>
 * None of the three is an argument that the block does not belong on this response. They are why
 * its absence is not a must-fix today, and the arms are what turn "one of them went away" into a
 * red build rather than into a 429 that is suddenly worth attacking.
 *
 * <p>And the rule is not about one door, which is the trap a seal written for one measurement falls
 * into. Three refusals are written in front of the chain, and each one's header state is accounted
 * for by a check rather than by a sentence: the 503 restores the block by hand and
 * {@code DatabaseBusyRefusalTest} compares it against the advice's, the 429's three properties are
 * held here, and {@code CspReportGuardFilter}'s 413/429 are excused because they write <em>no
 * body</em> — the last assertion of that arm reads {@link #classifyPreMvcDoors()}'s bucket for that
 * filter, so the excuse expires by itself the day it grows one. What holds that is the
 * <em>classification</em>, and the distinction is not pedantry: the same excuse written as
 * "no framing site is keyed to this filter" was <strong>measured green against a real body</strong>
 * (tests gate, round 2) because a body written through {@link ProblemJson} frames the response
 * inside {@code ProblemJson#write} — so the site-keyed form excused exactly the door it was watching
 * and, worse, excused it for doing what the failure message told it to do.
 *
 * <p>One convention this file depends on and cannot enforce: each arm here spends its own
 * {@code 198.51.100.0/24} suffix ({@code .105}–{@code .109}), because
 * {@code RateLimitService.ipWindows} is per-context state no test resets and this class shares its
 * Spring context with any sibling carrying the same properties. A third test that reached for one of
 * those addresses would flake these arms rather than fail them — pick a different one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=5",
        "app.rate-limit.login-failure-threshold=100",
        "app.rate-limit.trust-forwarded-for=true",
        "seed.admin.email=",
        "app.demo.seed-on-first-login=false"
})
@AutoConfigureMockMvc
class ProblemJsonWriterContractTest {

    /** Any authenticated endpoint; what matters is that the security chain refuses before MVC. */
    private static final String AUTHENTICATED_ENDPOINT = "/api/workspaces";

    /**
     * The response methods that frame or emit a body. {@code setStatus} and {@code setHeader} are
     * deliberately absent: a door owns its own status and its own {@code Retry-After}, and a rule
     * that claimed otherwise would make every refusal in the product a member.
     *
     * <p>{@code sendError} is here although there is not one call to it under {@code src/main/java}
     * today, because it is the cheapest way out of this rule and the <em>worst</em> one — it is not
     * an equivalent of {@code getWriter()}. It commits the response through the container's ERROR
     * dispatch, which {@code SecurityConfig} permits
     * ({@code dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()}), so the caller receives a
     * container-rendered page instead of a problem document: a different media type, a different
     * shape, and whatever the container decides to put in it. A door reaching for it escapes this
     * seal while looking like it refused properly.
     *
     * <p>{@code sendRedirect} is NOT a member, and the distinction is the body: it commits a 3xx
     * with a {@code Location} and no problem document, so a door that used it would be answering a
     * different thing entirely rather than framing a refusal body by hand. This rule is about the
     * document, not about every way a response can be committed.
     */
    private static final Set<String> FRAMING_METHODS = Set.of(
            "setContentType", "setCharacterEncoding", "setContentLength", "getWriter",
            "getOutputStream", "sendError");

    /**
     * The site allowed to frame a response besides {@link ProblemJson#write}, and the reason is a
     * property this class asserts rather than a sentence: {@link DatabaseBusyRefusal} writes a
     * <strong>precomputed constant</strong> whose bytes are pinned twice — against what this
     * product's one writer renders ({@link #theOneWriterRendersWhatMvcRenders}) and against what MVC
     * actually sends under a starved pool ({@code DatabaseBusyRefusalTest}). It also has to set a
     * {@code Content-Length} on a response it has just {@code reset()}, with the security block
     * restored around it, which is more than a body writer's job.
     *
     * <p><strong>Which half of it this class holds, stated because the exclusion reads as more than
     * it is.</strong> This class asserts the <em>constant</em> — that {@link DatabaseBusyRefusal#BODY_JSON}
     * is what {@link ProblemJson} renders. It asserts nothing about what the excused
     * <em>method</em> puts on the wire: rewriting {@code write} to emit {@code application/json}
     * through a {@code PrintWriter} while leaving the constant untouched leaves every arm here
     * green, measured on 2026-10-18. The method's own emission — its label, its charset, its
     * header block — is held by {@code DatabaseBusyRefusalTest}, which runs it against a genuinely
     * starved pool. Read the two together or neither covers the door.
     *
     * <p>Declared through {@link Population#excluding}, so the day that method stops framing a
     * response this entry is refused as stale instead of quietly excusing nothing.
     */
    private static final String PINNED_CONSTANT_WRITER =
            ProductionBytecode.site(DatabaseBusyRefusal.class.getName(), "write");

    /**
     * The security header block, by the names {@code DatabaseBusyRefusalTest}'s own tripwire uses —
     * one list for one idea, rather than a second spelling of it three files away.
     *
     * <p>Four names and not the whole of Spring Security's defaults: these are the ones measured on
     * both sides of the comparison in {@link #the429CarriesNoSecurityBlockAndIsSafeWithoutOne} —
     * present on a refusal written inside the chain, absent on one written in front of it. HSTS is
     * deliberately not here: it is conditional on {@code isSecure()} and no response in this product
     * carries it today, so a list containing it would make the tripwire fail for a reason that has
     * nothing to do with this door. {@code Pragma} and {@code Expires} are omitted for the opposite
     * reason — they travel with {@code Cache-Control} from the same writer, so naming all three
     * tests one thing three times.
     */
    private static final List<String> SECURITY_BLOCK = List.of(
            "X-Content-Type-Options", "X-Frame-Options", HttpHeaders.CACHE_CONTROL,
            ContentSecurityPolicy.HEADER);

    @Autowired MockMvc mockMvc;
    @Autowired ProblemJson problemJson;
    @Autowired JsonMapper jsonMapper;

    // ---------------------------------------------------------------- arm 1: the roster

    /**
     * The population, printed, with the floor {@code DoorsHarnessTest} already holds on this
     * narrowing.
     *
     * <p><strong>Six members, and the sixth is why this arm is listed rather than counted.</strong>
     * The ticket and its dispatch both described the pre-MVC set as the three filters plus the entry
     * point plus {@code CspReportGuardFilter}; the population also contains
     * {@code PrincipalThrottleInterceptor}, a {@code HandlerInterceptor} — a writer <em>kind</em> the
     * scan looks for and that nobody enumerating "the filters and the entry point" thinks of. It
     * refuses by throwing, so an advice renders its body and arm 2 classifies it as framing nothing;
     * that is a measurement of its bytecode, not a note about its design.
     */
    @Test
    void theRosterOfPreMvcDoorsIsWhatDoorsEnumerates() {
        var doors = preMvcDoors();

        System.out.println("[problem-writers] " + doors.describe());
        doors.forEach(w -> System.out.println("[problem-writers]   " + w.describe()));

        assertThat(doors.stream().map(w -> w.owner().getSimpleName()).toList())
                .as("""
                    THE PRE-MVC DOOR ROSTER MOVED.

                    This is a roster and not a verdict: reflection sees places that CAN write a \
                    response, never whether they do or with which content type (Doors#problemJson \
                    Writers says so). A new member here is not a failure - it is a prompt to run \
                    the arm below over it, which is what decides whether the new door frames its \
                    body through ProblemJson. Add it to this list once that passes.""")
                .containsExactlyInAnyOrder("AuthRateLimitFilter", "CspReportGuardFilter",
                        "DatabaseBusyFilter", "JwtAuthenticationFilter",
                        "JwtAuthenticationEntryPoint", "PrincipalThrottleInterceptor");
    }

    // ---------------------------------------------------------------- arm 2: the property

    /**
     * <strong>One writer frames a response, and every pre-MVC door reaches it.</strong>
     *
     * <p>The population is <em>call sites</em> from bytecode, keyed {@code Owner#method} — the unit
     * HD-306 cost a fix loop to learn. A class-keyed population would let one clean method answer
     * for a second framing method in the same class, so the granularity control at the end of this
     * arm refuses exactly that: {@link ProblemJson} may hold one framing method, not two.
     *
     * <p>Bytecode and not source text, because the question is about a typed receiver. A source scan
     * for {@code setContentType} finds {@code attachment.setContentType(...)} in
     * {@code AttachmentService} — an attachment's own content type, nothing to do with a response —
     * and would miss a response held in a variable spelled any other way. Both the interface the
     * compiler may record as the call's owner ({@link ServletResponse}, where these methods are
     * declared) and the one written at the call site ({@link HttpServletResponse}) are asked, and
     * the sites are unioned; which of the two the bytecode actually carries is printed.
     */
    @Test
    void everyPreMvcDoorFramesItsBodyThroughTheOneWriter() {
        var sites = framingSites();
        System.out.println("[problem-writers] " + sites.describe());
        sites.forEach(s -> System.out.println("[problem-writers]   frames a response: " + s));

        assertThat(sites.members())
                .as("the scan must see the one legitimate writer, or everything below passes over "
                    + "an empty set")
                .contains(ProductionBytecode.site(ProblemJson.class.getName(), "write"));

        var unexpected = sites
                .excluding("the pinned constant writer, whose body constant is asserted in this "
                           + "class", Set.of(PINNED_CONSTANT_WRITER))
                .stream()
                .filter(site -> !site.startsWith(ProblemJson.class.getName() + "#"))
                .toList();
        assertThat(unexpected)
                .as("""
                    A RESPONSE BODY IS FRAMED BY ProblemJson, NOWHERE ELSE.

                    Each site listed above calls setContentType / setCharacterEncoding / \
                    setContentLength / getWriter / getOutputStream on a response itself. Doing that \
                    by hand is how this product ended up with three refusals that disagreed about \
                    their own label, their encoding and their shape (HD-305): a 401 under \
                    application/json with three null members, and a 429 whose em dash arrived as a \
                    question mark. Inject ProblemJson and call write(response, problemDetail) - it \
                    owns status, content type, charset, length and body together. If a door really \
                    must frame its own response, pin its bytes the way DatabaseBusyRefusal's are \
                    pinned and declare the site beside that one.""")
                .isEmpty();

        var framingMethodsOfTheWriter = sites.stream()
                .filter(site -> site.startsWith(ProblemJson.class.getName() + "#")).toList();
        assertThat(framingMethodsOfTheWriter)
                .as("""
                    THE GRANULARITY CONTROL: ONE WRITER MEANS ONE WRITING METHOD.

                    This population is keyed Owner#method precisely so that a second framing method \
                    inside the exempt class cannot inherit the first one's clean bill of health. If \
                    ProblemJson grows a second writer, every assertion in this class is about one of \
                    them and says nothing about the other - which is the HD-306 shape, planted \
                    inside the test written to kill it. Fold the new path into write(), or give this \
                    class an arm per writing method.""")
                .hasSize(1);
    }

    /**
     * The other half of arm 2, and the half that keeps the roster honest: every door either reaches
     * {@link ProblemJson}, or reaches the pinned constant writer, or frames nothing at all — and
     * which of the three is <em>computed</em>, never declared. A door that frames by hand is in none
     * of the three and fails the arm above; a door that writes no body is excused by a measurement
     * of its own bytecode rather than by a sentence about its design.
     */
    @Test
    void eachDoorIsEitherAWriterThroughTheOneWriterOrFramesNothing() {
        var classification = classifyPreMvcDoors();

        assertThat(classification.values())
                .as("""
                    A PRE-MVC DOOR THAT WRITES A BODY WRITES IT THROUGH ProblemJson.

                    Each door above was classified from its own bytecode, so "writes no body" is a \
                    measurement and not a design note - the day CspReportGuardFilter or \
                    JwtAuthenticationFilter grows a body, it leaves that bucket by itself. BY HAND \
                    is the state HD-305 found three doors in.""")
                .doesNotContain("BY HAND");
        assertThat(classification.values().stream().filter(how -> how.startsWith("through")).count())
                .as("the floor on this arm: if nobody reaches the one writer any more, the arm "
                    + "above is passing over a tree with no problem writers in it at all")
                .isGreaterThanOrEqualTo(3L);
    }

    /**
     * <strong>How each pre-MVC door writes its body, computed from bytecode, in the one place two
     * arms ask the question.</strong> Keyed by simple name, valued {@code "through ProblemJson"} /
     * {@code "through the pinned constant writer"} / {@code "BY HAND"} / {@code "writes no body"}.
     *
     * <p>It is shared rather than copied because the header arm's excuse for
     * {@code CspReportGuardFilter} has to ask exactly the question this arm asks, and asking it a
     * second way is how the two came to disagree: that excuse was first written as
     * {@code framingSites()} filtered on the door's own name, which is a <em>site</em>-keyed
     * question ({@code Owner#method}) asked of a <em>class</em>-level property. A body written
     * through {@link ProblemJson} frames the response inside {@code ProblemJson#write}, so no site
     * is keyed to the filter and the excuse survived a real body — measured by the tests gate, 10/10
     * green with a {@code ProblemJson}-written body planted in that filter's 429 branch, while this
     * classification printed {@code through ProblemJson} for it in the very same run. An in-class
     * helper would have been caught and the out-of-class one was not, which is the worse half: the
     * failure message prescribes writing the body through {@code ProblemJson}, so following the
     * instruction disarmed the check.
     */
    private static TreeMap<String, String> classifyPreMvcDoors() {
        var throughTheWriter = callerClassesOf(ProblemJson.class);
        var throughThePinnedConstant = ProductionBytecode
                .callSitesOfMethods(Set.of(), DatabaseBusyRefusal.class, Set.of("write")).stream()
                .map(site -> site.substring(0, site.indexOf('#'))).collect(toLinkedSet());
        var framingClasses = framingSites().stream()
                .map(site -> site.substring(0, site.indexOf('#'))).collect(toLinkedSet());

        var classification = new TreeMap<String, String>();
        for (var door : preMvcDoors()) {
            var name = door.owner().getName();
            var how = throughTheWriter.contains(name) ? "through ProblemJson"
                    : throughThePinnedConstant.contains(name) ? "through the pinned constant writer"
                    : framingClasses.contains(name) ? "BY HAND"
                    : "writes no body";
            classification.put(door.owner().getSimpleName(), how);
        }
        classification.forEach((door, how) ->
                System.out.println("[problem-writers]   " + door + ": " + how));
        return classification;
    }

    // ---------------------------------------------------------------- arm 3: the wire

    /**
     * The acceptance criterion, taken from MockMvc as bytes: the 401 an unauthenticated
     * {@code /api/**} call receives, and the 429 the auth budget answers, are both problem documents
     * with a UTF-8 label and no empty members.
     *
     * <p>Both doors in one method on purpose — they share the predicate, and a predicate exercised
     * once is a predicate whose second caller silently drifts.
     */
    @Test
    void theRefusalsAPreMvcDoorWritesAreFramedAsProblemDocuments() throws Exception {
        var unauthenticated = mockMvc.perform(get(AUTHENTICATED_ENDPOINT)).andReturn().getResponse();
        assertFramedAsAProblemDocument("JwtAuthenticationEntryPoint (401 on " + AUTHENTICATED_ENDPOINT + ")",
                HttpStatus.UNAUTHORIZED, unauthenticated);
        assertThat(documentOf(unauthenticated)).containsEntry("detail", "Authentication required");

        FixedWindow.inOneMinute(attempt -> {
            // A /24 of this class's own: RateLimitService.ipWindows is per-context state no test
            // resets, so an address another member has spent is spent for every member sharing the
            // context. The suffix is what makes a discarded attempt re-run on a fresh key.
            var peer = "198.51.105." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                assertThat(login(peer).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }
            var refused = login(peer);
            assertFramedAsAProblemDocument("AuthRateLimitFilter (429 on /api/auth/login)",
                    HttpStatus.TOO_MANY_REQUESTS, refused);
            assertThat(refused.getHeader("Retry-After")).isNotBlank();
        });
    }

    /**
     * <strong>The body is written as bytes, so a detail that ISO-8859-1 cannot encode survives.</strong>
     *
     * <p>This is the arm the measurement demanded. {@code AuthRateLimitFilter} wrote its hand-built
     * JSON through {@link HttpServletResponse#getWriter()}, which encodes with the response's
     * charset; nothing set one, so Tomcat's ISO-8859-1 default replaced the em dash in
     * {@link RateLimitedException}'s default message with {@code ?} — measured on the running
     * application, {@code "Too many requests ? try again later"}.
     *
     * <p><strong>What this arm cannot see, measured rather than assumed.</strong> It was planted
     * against the pre-HD-305 writer (hand-built JSON through {@code getWriter()}) and stayed
     * <em>green</em>: {@code MockHttpServletResponse} defaults to UTF-8 where a container defaults
     * to ISO-8859-1, so the bytes are right under MockMvc whatever the writer did. The mangling is
     * a container fact and its only honest witness is the {@code curl} in the ticket. What MockMvc
     * <em>does</em> show is whether a charset was ever declared — that is the property
     * {@link #assertFramedAsAProblemDocument} now requires, and it is what reds this arm against
     * that plant. So this method asserts the detail round-trips, and the predicate asserts the
     * declaration; neither alone would have failed.
     *
     * <p>It carries its own non-vacuity tripwire: the probe is the production message, and if that
     * message ever becomes pure ASCII this arm can no longer tell a UTF-8 writer from a Latin-1 one
     * and must be given another probe.
     */
    @Test
    void aNonAsciiDetailSurvivesTheWire() throws Exception {
        var expected = new RateLimitedException(60).getMessage();
        assertThat(expected.chars().anyMatch(c -> c > 0x7F))
                .as("""
                    THIS ARM'S PROBE HAS LOST ITS POINT.

                    It proves a UTF-8 body by sending a character ISO-8859-1 cannot encode, and the \
                    character comes from RateLimitedException's default message. That message is now \
                    pure ASCII, so a PrintWriter under the default charset would pass this arm \
                    unchanged. Give the arm a probe that is still non-ASCII, or delete it and say \
                    where the encoding is proven instead.""")
                .isTrue();

        FixedWindow.inOneMinute(attempt -> {
            var peer = "198.51.106." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                assertThat(login(peer).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            }
            var refused = login(peer);

            assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(documentOf(refused))
                    .as("""
                        THE 429's DETAIL WAS MANGLED ON THE WAY OUT.

                        The body must be written as UTF-8 BYTES. A PrintWriter encodes with the \
                        response charset, which defaults to ISO-8859-1 and replaces anything \
                        outside Latin-1 with a question mark - which is exactly what this refusal \
                        shipped until HD-305. ProblemJson writes bytes and declares the charset; a \
                        door that reaches for getWriter() reintroduces this.""")
                    .containsEntry("detail", expected);
            assertThat(new String(refused.getContentAsByteArray(), StandardCharsets.UTF_8))
                    .as("decoded as UTF-8 it reads back; the replacement character would not")
                    .doesNotContain("?");
        });
    }

    /**
     * <strong>The plant, driven from literals rather than from a repository edit.</strong> The
     * pre-HD-305 entry point is reconstructed here — {@code application/json} plus a bare Jackson 2
     * {@code ObjectMapper} — and the predicate the two arms above use must refuse it. Without this,
     * a predicate that had quietly stopped asserting anything would read as coverage.
     */
    @Test
    void thePredicateRefusesThePreHd305Writer() throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
        var planted = new MockHttpServletResponse();
        planted.setStatus(HttpStatus.UNAUTHORIZED.value());
        planted.setContentType(MediaType.APPLICATION_JSON_VALUE);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(planted.getOutputStream(), problem);

        assertThat(planted.getContentAsString())
                .as("the plant is the real pre-fix document, not an approximation of it")
                .contains("\"type\":null", "\"instance\":null", "\"properties\":null");

        assertThatThrownBy(() -> assertFramedAsAProblemDocument("the planted pre-HD-305 writer",
                HttpStatus.UNAUTHORIZED, planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the planted pre-HD-305 writer");
    }

    // ---------------------------------------------------------------- arm 4: the mapper

    /**
     * <strong>The injected mapper is the one MVC renders with, and the 503's constant is what it
     * produces.</strong>
     *
     * <p>Two facts, one arm, because each is the other's reason for being believable. Boot 4.1's
     * {@code JacksonAutoConfiguration} adds {@code ProblemDetailJacksonMixin} to the
     * {@code JsonMapper} builder, which is what unwraps a {@code ProblemDetail}'s extension
     * properties to top level and drops the empty members; a mapper constructed with {@code new} —
     * what the entry point used to do — has neither. And {@link DatabaseBusyRefusal#BODY_JSON} is a
     * hand-written constant that {@code DatabaseBusyRefusalTest} pins, under a genuinely starved
     * pool, against what MVC actually sends. So if this writer reproduces that constant
     * character-for-character, it renders what MVC renders — which is the claim the ticket needed
     * and neither test can make on its own.
     */
    @Test
    void theOneWriterRendersWhatMvcRenders() {
        var withAnExtension = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "nope");
        withAnExtension.setProperty("errorType", "SOMETHING");
        assertThat(new String(problemJson.render(withAnExtension), StandardCharsets.UTF_8))
                .as("""
                    THE INJECTED MAPPER IS NOT THE ONE MVC RENDERS WITH.

                    An extension property must be unwrapped to TOP LEVEL ("errorType":"SOMETHING") \
                    and empty members must be dropped. Both come from ProblemDetailJacksonMixin, \
                    which Boot registers into the JsonMapper bean and which a mapper built with new \
                    ObjectMapper() does not have - Spring's own javadoc on that mixin says it \
                    applies "unless an ObjectMapper is instantiated directly". A nested \
                    "properties" object here means something is handing ProblemJson a mapper of its \
                    own instead of the context's.""")
                .contains("\"errorType\":\"SOMETHING\"")
                .doesNotContain("\"properties\"");

        assertThat(new String(problemJson.render(DatabaseBusyRefusal.problemDetail()),
                StandardCharsets.UTF_8))
                .as("""
                    THE 503's HAND-WRITTEN CONSTANT AND THIS WRITER HAVE DRIFTED APART.

                    DatabaseBusyRefusal.BODY_JSON is the bytes the database-busy filter sends, and \
                    DatabaseBusyRefusalTest pins them - under a starved pool, against what MVC \
                    sends from the same ProblemDetail. This comparison is what joins the two: it \
                    says ProblemJson renders what MVC renders, cheaply, on every run. If it fails, \
                    find out which side moved before touching either - a Jackson or Spring upgrade \
                    that changes field order or inclusion moves the constant, while an edit to \
                    problemDetail() moves the document.""")
                .isEqualTo(DatabaseBusyRefusal.BODY_JSON);
    }

    // --------------------------------- the three properties that stand in for the 429's headers

    /**
     * <strong>This door's 429 is a constant document; nothing the caller sent appears in it.</strong>
     *
     * <p>The pre-HD-305 writer interpolated {@code e.getMessage()} into JSON by hand, and the thing
     * that kept that safe was never the exception type — {@code RateLimitedException(String, long)}
     * exists and three call sites pass composed text to it. It was the {@code catch} in
     * {@code AuthRateLimitFilter.doFilterInternal}, which wraps one call
     * ({@code checkAuthRequestAllowed}) and not the chain, so only {@code RateLimitService}'s two
     * numeric-constructor throws can reach it. That is control flow, and control flow is editable
     * without touching anything this suite otherwise watches. So: the whole document, compared
     * against what the constant renders to, under a request that tries to get bytes of its own into
     * it — a quotation mark in the e-mail, a quotation mark in the forwarded chain, a
     * {@code <script>} in the user agent.
     *
     * <p>{@code 60} in the expectation is arbitrary and must be: {@code Retry-After} is the door's
     * header and is deliberately not in the document, so the comparison would be wrong if it
     * depended on how many seconds the window had left.
     */
    @Test
    void the429sDocumentIsTheConstantNothingCallerDerived() throws Exception {
        var constant = documentOf(problemJson.render(new RateLimitedException(60).problemDetail()));
        assertThat(constant)
                .as("the comparison below is vacuous unless the constant carries the sentence")
                .containsEntry("detail", new RateLimitedException(60).getMessage());

        FixedWindow.inOneMinute(attempt -> {
            var peer = "198.51.107." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                hostileLogin(peer);
            }
            var refused = hostileLogin(peer);

            assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(documentOf(refused))
                    .as("""
                        THE 429 NOW CARRIES TEXT THAT IS NOT THE CONSTANT.

                        Every member of this document must come from RateLimitedException's default \
                        message, rendered by ProblemJson - the request that produced it put a quote \
                        in its e-mail, a quote in X-Forwarded-For and a <script> in its user agent, \
                        and none of that may appear. Two ways this reds: a caller-derived string \
                        reached the body (the hazard HD-305's hand-built JSON had, and the reason \
                        this door's missing security header block is tolerable - see the class \
                        javadoc, which must be rewritten if you keep the change), or the filter now \
                        composes its own text, in which case put the new sentence in a constant and \
                        compare against that instead.""")
                    .isEqualTo(constant);
        });
    }

    /**
     * <strong>The budget door refuses a POST and nothing else.</strong>
     *
     * <p>{@code shouldNotFilter} is {@code !"POST".equalsIgnoreCase(...)} — one {@code ||} from
     * covering GET, and the GET is the request whose response a browser renders. Measured on the
     * wire on 2026-10-18: a GET to a budgeted path with the budget spent answers
     * {@code 200 text/html} with the full security header block, because it never reaches this
     * filter. The status asserted here is only "not 429": what a GET to {@code /api/auth/login}
     * answers is the SPA fallback's business and not this seal's.
     */
    @Test
    void theBudgetDoorRefusesOnlyAPost() throws Exception {
        FixedWindow.inOneMinute(attempt -> {
            var peer = "198.51.108." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                login(peer);
            }
            assertThat(login(peer).getStatus())
                    .as("the tripwire: %s's budget must really be spent, or the GET below is a GET "
                        + "against an unspent budget and proves nothing", peer)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            var viaGet = mockMvc.perform(get("/api/auth/login")
                            .with(r -> {
                                r.setRemoteAddr("10.0.0.9");
                                return r;
                            })
                            .header("X-Forwarded-For", "1.2.3.4, " + peer))
                    .andReturn().getResponse();
            assertThat(viaGet.getStatus())
                    .as("""
                        THE BUDGET DOOR NOW REFUSES A GET.

                        It must not: a GET is answered behind the security chain, with the whole \
                        header block on it, and that is one of the three properties that make this \
                        door's own missing block tolerable (class javadoc). A 429 here means \
                        shouldNotFilter was widened, and a hand-written refusal with no nosniff and \
                        no frame refusal is now reachable by a plain browser navigation. If the \
                        widening is wanted, the block has to come with it - see HD-264's restore in \
                        DatabaseBusyRefusal.""")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        });
    }

    /**
     * <strong>The 429 carries none of the security header block — the measured state, recorded as a
     * state and not as a remark.</strong>
     *
     * <p>Measured on 2026-10-18 against the running application and reproduced here: a refusal
     * written at {@code HIGHEST_PRECEDENCE} never enters {@code HeaderWriterFilter}, so it has no
     * {@code nosniff}, no {@code DENY}, no cache directive and no CSP, while the 401 two lines up —
     * refused inside the chain — carries all four. The 503 is the one pre-chain door that restores
     * them (HD-264), by hand, because {@code response.reset()} had taken off a block it did have.
     *
     * <p>This arm asserts the absence rather than the presence, which is unusual and is the point:
     * the state was unwitnessed in <em>both</em> directions before it, since
     * {@code DatabaseBusyRefusalTest}'s header comparison asserts 503 on both of its responses and
     * derives its expected set from the advice's 503, and {@code SecurityHeadersReachTheBrowserTest}
     * — the only harness that reads header bytes off a real container — boots with
     * {@code app.rate-limit.enabled=false}, so the door is switched off in the one place that could
     * have seen it. Asserting the absence means the day the block arrives, somebody has to come here
     * and delete a paragraph that explains why it was not needed, which is the only mechanism that
     * makes the three properties above expire with the reason for them.
     *
     * <p>The {@code Content-Length} assertion is the third property: the body is explicitly framed,
     * so no sniffing heuristic has anything to guess at.
     */
    @Test
    void the429CarriesNoSecurityBlockAndIsSafeWithoutOne() throws Exception {
        assertThat(SECURITY_BLOCK)
                .as("""
                    THE FLOOR UNDER BOTH DIRECTIONS OF THIS ARM.

                    Every assertion below is quantified over SECURITY_BLOCK, in BOTH directions: \
                    the 401 must carry all of it and the 429 none of it. Empty it and the arm \
                    passes while measuring nothing at all - measured, not feared. Shrinking the \
                    list is merely weaker; emptying it is total, so the size is held here and a \
                    deliberate change to the block is a deliberate change to this number.""")
                .hasSize(4);

        var insideTheChain = mockMvc.perform(get(AUTHENTICATED_ENDPOINT)).andReturn().getResponse();
        assertThat(carriedOf(SECURITY_BLOCK, insideTheChain))
                .as("""
                    THE TRIPWIRE: THE 401 MUST CARRY THE BLOCK.

                    This arm says the 429 carries none of these. If the 401 - written inside the \
                    security chain, by HeaderWriterFilter and SecurityConfig - stops carrying them \
                    too, then the comparison below is an equality between two empty sets and the \
                    product has a much larger problem than this seal was written about.""")
                .containsExactlyElementsOf(SECURITY_BLOCK);

        FixedWindow.inOneMinute(attempt -> {
            var peer = "198.51.109." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                login(peer);
            }
            var refused = login(peer);
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            assertThat(carriedOf(SECURITY_BLOCK, refused))
                    .as("""
                        THE 429's SECURITY HEADER STATE CHANGED - WHICH IS GOOD NEWS, AND AN EDIT.

                        This arm records a measured absence (2026-10-18): a refusal written at \
                        HIGHEST_PRECEDENCE never enters HeaderWriterFilter. It is tolerable only \
                        because the door is POST-only, its body is a constant with nothing \
                        caller-derived, and it is explicitly framed - the three properties this \
                        class asserts beside it. If you have given the 429 the block, say so here \
                        and in the class javadoc, delete the three-property argument, and do it for \
                        CspReportGuardFilter's 413/429 in the same change: one shared restore for \
                        the pre-chain doors was the closure HD-305 declined as a ticket of its own, \
                        and a second hand-written copy of Spring Security's defaults is what that \
                        ticket exists to avoid.""")
                    .isEmpty();

            assertThat(refused.getContentLength())
                    .as("""
                        THE 429's BODY IS NO LONGER EXPLICITLY FRAMED.

                        Content-Length is the third of the three properties: with no block on this \
                        response, nothing but the declared type, charset and length tells an \
                        intermediary or a browser what these bytes are. ProblemJson sets all three \
                        together; a door that writes a body around it loses them together.""")
                    .isEqualTo(refused.getContentAsByteArray().length);
        });

        assertThat(classifyPreMvcDoors().get(CspReportGuardFilter.class.getSimpleName()))
                .as("""
                    THE THIRD PRE-CHAIN DOOR GREW A BODY, AND IT HAS NO HEADER BLOCK EITHER.

                    This assertion is what keeps the rule above from being about one door. Three \
                    refusals in this product are written in front of the security chain, and each \
                    one's header state is accounted for by a live check and not by a sentence: the \
                    503 restores the block by hand (DatabaseBusyRefusalTest compares it against the \
                    advice's), this class holds the 429's three standing properties, and \
                    CspReportGuardFilter's 413/429 are excused because they WRITE NO BODY - the \
                    classification arm 2 computes from bytecode, asked here rather than re-derived, \
                    because a body written through ProblemJson frames the response inside \
                    ProblemJson#write and a site-keyed question about this filter's own name cannot \
                    see it. A body on that door puts bytes with no nosniff, no frame refusal and no \
                    cache directive in front of a report sender. Writing it through ProblemJson is \
                    right and does NOT answer this: decide the header question for all three \
                    pre-chain doors, and record the answer here.""")
                .isEqualTo("writes no body");
    }

    /** The members of {@code names} this response actually carries, in the order given. */
    private static List<String> carriedOf(List<String> names, MockHttpServletResponse response) {
        return names.stream().filter(name -> response.getHeader(name) != null).toList();
    }

    /**
     * A hostile but well-formed login: a quotation mark inside the e-mail, a quotation mark in the
     * forwarded chain and a {@code <script>} user agent. The budgeted key is the right-most element
     * of {@code X-Forwarded-For} ({@code ClientIp#of}), so the quote goes to the left of it and the
     * attempt still charges {@code peer}.
     */
    private MockHttpServletResponse hostileLogin(String peer) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(r -> {
                            r.setRemoteAddr("10.0.0.9");
                            return r;
                        })
                        .header("X-Forwarded-For", "\"1.2.3.4, " + peer)
                        .header(HttpHeaders.USER_AGENT, "<script>alert(1)</script>")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a\\\"b" + Unique.email("nobody")
                                 + "\",\"password\":\"x-wrong-pass\"}"))
                .andReturn().getResponse();
    }

    // ---------------------------------------------------------------- the shared predicate

    /**
     * The one definition of "framed as a problem document", used by both live doors and by the
     * plant. Three properties: the media type ignoring its parameters, the charset if one is
     * declared, and that no member of the document is empty — the last being the mixin's
     * {@code NON_EMPTY} guarantee, and the one the bare-mapper 401 failed.
     */
    private void assertFramedAsAProblemDocument(String who, HttpStatus expected,
                                                MockHttpServletResponse response) {
        assertThat(response.getStatus()).as("%s: status", who).isEqualTo(expected.value());

        var contentType = response.getContentType();
        assertThat(contentType).as("%s: a refusal with a body says what the body is", who).isNotNull();
        var mediaType = MediaType.parseMediaType(contentType);
        assertThat(new MediaType(mediaType.getType(), mediaType.getSubtype()))
                .as("""
                    %s: A REFUSAL BODY IS application/problem+json.

                    Every refusal MVC renders is labelled that way, openapi.yaml declares it for \
                    the 401 on dozens of paths, and a client or an intermediary that branches on \
                    the label sees a document it has no reason to parse as a problem. Was: %s.""",
                        who, contentType)
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mediaType.getCharset())
                .as("""
                    %s: A HAND-WRITTEN REFUSAL DECLARES charset=UTF-8.

                    This is the property that stands in for a defect MockMvc cannot show. A \
                    PrintWriter encodes with the response's charset, and on a real container that \
                    default is ISO-8859-1 - measured on 2026-10-18 against the running application, \
                    the 429's em dash arrived as a literal "?". MockHttpServletResponse defaults to \
                    UTF-8 instead, so the BYTES look right here whatever the writer did; what \
                    differs between the two writers under MockMvc is whether a charset was \
                    declared at all. Declaring it is also what makes the label honest for an \
                    intermediary. Was: %s. MVC's own refusals declare none because its converter \
                    chooses the encoding; a door that writes its own bytes has no such excuse.""",
                        who, contentType)
                .isEqualTo(StandardCharsets.UTF_8);

        var document = documentOf(response);
        var empty = document.entrySet().stream()
                .filter(e -> e.getValue() == null || "".equals(e.getValue())).map(Map.Entry::getKey)
                .toList();
        assertThat(empty)
                .as("""
                    %s: A PROBLEM DOCUMENT CARRIES NO EMPTY MEMBERS.

                    ProblemDetailJacksonMixin declares @JsonInclude(NON_EMPTY), so every refusal \
                    rendered through the context's mapper drops them. Members present and null mean \
                    this body was serialised by a mapper built with new ObjectMapper(), which is \
                    not the mapper MVC uses - the shape of the 401 before HD-305 \
                    ({"type":null,...,"instance":null,"properties":null}). Write through \
                    ProblemJson.""", who)
                .isEmpty();
        assertThat(document).as("%s: the status is in the document too", who)
                .containsEntry("status", expected.value());
    }

    private Map<String, Object> documentOf(MockHttpServletResponse response) {
        return documentOf(response.getContentAsByteArray());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentOf(byte[] body) {
        return jsonMapper.readValue(body, Map.class);
    }

    private MockHttpServletResponse login(String peer) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(r -> { r.setRemoteAddr("10.0.0.9"); return r; })
                        .header("X-Forwarded-For", "1.2.3.4, " + peer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + Unique.email("nobody")
                                 + "\",\"password\":\"x-wrong-pass\"}"))
                .andReturn().getResponse();
    }

    // ---------------------------------------------------------------- the populations

    private static Population<Doors.ProblemWriter> preMvcDoors() {
        return Doors.problemJsonWriters()
                .filter("pre-MVC doors", w -> w.kind() != Doors.WriterKind.ADVICE_HANDLER)
                .floor(5);
    }

    /**
     * Every production site that frames a response, from both the interface the methods are declared
     * on and the one written at the call site — unioned, because which of the two a call records is
     * the compiler's business and a seal that asked only one of them could lose every member at once.
     */
    private static Population<String> framingSites() {
        var sites = new LinkedHashSet<String>();
        for (var owner : List.of(ServletResponse.class, HttpServletResponse.class)) {
            var found = ProductionBytecode.callSitesOfMethods(Set.of(), owner, FRAMING_METHODS);
            System.out.println("[problem-writers] " + owner.getSimpleName() + " records "
                               + found.size() + " framing site(s)");
            sites.addAll(found);
        }
        return Population.of("response framing sites", new ArrayList<>(sites)).floor(2);
    }

    private static Set<String> callerClassesOf(Class<?> helper) {
        return ProductionBytecode.callersOf(Set.of(), helper).stream()
                .map(com.tngtech.archunit.core.domain.JavaClass::getName).collect(toLinkedSet());
    }

    private static java.util.stream.Collector<String, ?, Set<String>> toLinkedSet() {
        return java.util.stream.Collectors.toCollection(LinkedHashSet::new);
    }
}
