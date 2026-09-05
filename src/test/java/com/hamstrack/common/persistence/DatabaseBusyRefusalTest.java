package com.hamstrack.common.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.common.exception.DatabaseBusyFilter;
import com.hamstrack.common.exception.DatabaseBusyRefusal;
import com.hamstrack.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>What a caller is told when the pool has no connection to give</strong> (HD-233): 503, an
 * {@code errorType} it can branch on, a {@code Retry-After}, and not one word about the pool.
 *
 * <p>Until this existed the answer was a bare <strong>500</strong> — verified against all of
 * {@code GlobalExceptionHandler}'s handlers, none of which was bound to it, and
 * {@code handleQueryTimeout} declares three narrow types precisely so that it does <em>not</em>
 * swallow this condition. That was tolerable while the bound was Hikari's unset 30 s and the
 * condition was rare; at 3 s it is the ordinary shape of saturation and needs a status.
 *
 * <h2>Two writers, because a status can only be given where the failure can be caught</h2>
 * A {@code @RestControllerAdvice} sees what a <em>handler</em> raises, and on an authenticated
 * request the first thing to touch the database is {@code JwtAuthenticationFilter}'s user lookup —
 * which unwinds through the filter chain and reaches no advice. So the refusal has two writers and
 * this class tests both: {@code POST /api/auth/login} carries no token and opens its transaction
 * inside the controller (the advice), and any authenticated endpoint fails before the dispatcher
 * (the filter). The second is the traffic that matters, and it is not merely tidiness that it is
 * covered: the SPA declines to retry a 503 and deliberately DOES retry a 500, so while that half
 * answered 500 a starved instance was asked again by every open tab.
 *
 * <p>The bodies are asserted <strong>byte-identical</strong> rather than merely equivalent, because
 * one of them is hand-built — Boot 4 exposes no {@code ObjectMapper} bean in a filter — and two
 * hand-written copies of one JSON document is the defect this ticket is otherwise about.
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
class DatabaseBusyRefusalTest extends PoolStarvedBase {

    /** Authenticated, cheap, and stable: what matters is that a token has to be resolved first. */
    private static final String AUTHENTICATED_ENDPOINT = "/api/workspaces";

    @Autowired private GlobalExceptionHandler handler;

    @Test
    @Timeout(120)
    void aPoolWithNothingToGiveAnswers503WithRetryAfterAndANamedErrorType() throws Exception {
        double before = acquisitionFailures();

        var response = whileTheWholePoolIsHeld(this::loginAnonymously);

        assertThat(response.getStatus())
                .as("""
                    A STARVED POOL MUST ANSWER 503 DATABASE_BUSY, NOT 500.

                    A 500 says "we failed" where the truth is "we decided", and it renders in the \
                    SPA as a crash rather than as a sentence. 5xx is right here for exactly the \
                    reason it is wrong for STATEMENT_BUDGET_EXCEEDED: a failed acquisition is \
                    transient by construction - the obstacle is somebody else's transaction, which \
                    ends - so one automatic retry costs one acquisition attempt rather than a \
                    re-run of an expensive query, and the status that invites a retry is the \
                    correct one. If this is 500, the handler is gone or its cause-chain \
                    discriminator no longer recognises Hikari's exception.""")
                .isEqualTo(503);
        assertThat(response.getHeader("Retry-After"))
                .as("the obstacle is a transaction that ends rather than a window that rolls, so "
                    + "one second is the honest hint — the same shape the lock 409 uses")
                .isEqualTo("1");

        var body = response.getContentAsString();
        assertThat(body).contains("\"errorType\":\"DATABASE_BUSY\"");
        assertBodyDescribesNoPool(body);

        assertThat(acquisitionFailures() - before)
                .as("the counter is what makes a rate of these alertable without anybody reading a "
                    + "log; Hikari's own hikaricp_connections_timeout_total says the pool refused "
                    + "somebody, never which route they were on")
                .isEqualTo(1.0);
    }

    /**
     * <strong>The half the advice cannot reach, and the half that carries the traffic.</strong>
     *
     * <p>The discriminator is what an <em>un</em>authenticated request gets from the same endpoint:
     * a 401, decided by the security chain without touching the database. So a 503 here proves two
     * things at once — {@link DatabaseBusyFilter} turned the failure into a status, and
     * {@code JwtAuthenticationFilter} still <strong>failed closed</strong> while doing it. A filter
     * that swallowed the acquisition failure and let the request continue would show up here as
     * that 401 (or, if it also lost the security chain, as a 200), and it is the catastrophic
     * version of this fix: an unauthenticated request served as somebody.
     */
    @Test
    @Timeout(180)
    void anAuthenticatedRequestIsRefusedRatherThanPassedThroughUnauthenticated() throws Exception {
        var ctx = newProject();
        double before = acquisitionFailures();

        var responses = whileTheWholePoolIsHeld(() -> List.of(
                perform(get(AUTHENTICATED_ENDPOINT)
                        .header("Authorization", "Bearer " + ctx.token())),
                perform(get(AUTHENTICATED_ENDPOINT))));
        var withToken = responses.get(0);
        var withoutToken = responses.get(1);

        assertThat(withoutToken.getStatus())
                .as("the control: with no token this endpoint is decided by the security chain "
                    + "before anything reads the database, so a starved pool cannot change it — if "
                    + "THIS is 503 the test below proves nothing")
                .isEqualTo(401);
        assertThat(withToken.getStatus())
                .as("""
                    AN AUTHENTICATED REQUEST MUST BE REFUSED 503, AND MUST NOT PROCEED.

                    Its token is resolved to a user inside the security filter chain, so the \
                    acquisition fails before any handler exists and no @RestControllerAdvice can \
                    see it. 500 means DatabaseBusyFilter is gone or is no longer outside the \
                    security chain - and a 500 is the one 5xx the SPA retries, so this is also the \
                    amplification path: every open tab re-asks at the moment the pool is empty. \
                    401 or 2xx is far worse: it means JwtAuthenticationFilter swallowed a database \
                    failure and let the request through without authenticating, which is a request \
                    served as nobody.""")
                .isEqualTo(503);
        assertThat(withToken.getHeader("Retry-After")).isEqualTo("1");
        assertBodyDescribesNoPool(withToken.getContentAsString());

        assertThat(acquisitionFailures() - before)
                .as("""
                    THE COUNTER MUST SEE THE FILTER'S HALF TOO.

                    DatabaseConnectionAcquisitionFailing is built on this counter. While only the \
                    advice wrote it, the alert counted the unauthenticated minority and was \
                    therefore quietest during a real incident - the alert and the thing it watches \
                    have to be the same population.""")
                .isEqualTo(1.0);
    }

    /**
     * <strong>One refusal, two writers, and exactly one permitted difference between them.</strong>
     * The advice renders a {@code ProblemDetail} through the MVC message converters; the filter
     * writes bytes it built itself, because Boot 4 has no {@code ObjectMapper} bean out there.
     * Compared as whole documents rather than parsed, so that a change to Jackson's field order, to
     * Spring's {@code ProblemDetail} shape, or to the response encoding fails here rather than
     * reaching a caller who now sees two different documents for one condition.
     *
     * <p>The permitted difference is {@code instance}, and it is asserted rather than tolerated —
     * both that the advice HAS it and that it is the ONLY thing that differs. Spring MVC fills a
     * null {@code instance} with the request URI on the way out, which is why "the body is entirely
     * compile-time constants" is true of what the application writes and false of the bytes the
     * advice sends. The filter does not reproduce it, because doing so would mean hand-escaping a
     * caller-supplied path into a hand-built JSON document — a worse thing to own than an absent
     * optional member no client here reads.
     */
    @Test
    @Timeout(180)
    void theTwoWritersOfTheRefusalSendTheSameDocument() throws Exception {
        var ctx = newProject();

        var responses = whileTheWholePoolIsHeld(() -> List.of(
                loginAnonymously(),
                perform(get(AUTHENTICATED_ENDPOINT)
                        .header("Authorization", "Bearer " + ctx.token()))));
        var viaAdvice = responses.get(0);
        var viaFilter = responses.get(1);

        assertThat(viaAdvice.getStatus()).isEqualTo(503);
        assertThat(viaFilter.getStatus()).isEqualTo(503);
        var fromAdvice = viaAdvice.getContentAsString();
        assertThat(fromAdvice)
                .as("the member MVC adds, pinned in the direction that says the subtraction below "
                    + "is still subtracting something: if Spring ever stops adding it, this "
                    + "comparison has to tighten to the whole document")
                .contains("\"instance\":\"/api/auth/login\"");
        assertThat(withoutInstance(fromAdvice))
                .as("""
                    THE TWO WRITERS OF 503 DATABASE_BUSY MUST SEND THE SAME DOCUMENT.

                    One is a hand-built string in DatabaseBusyRefusal, the other is what Jackson \
                    makes of DatabaseBusyRefusal.problemDetail(). If they differ by anything but \
                    the instance member MVC adds, a client branching on this refusal sees a \
                    different document depending on how far into the server its request got - \
                    which is exactly the drift a single shared definition exists to prevent. Fix \
                    the CONSTANT to match what MVC renders; do not teach the client about two \
                    shapes.""")
                .isEqualTo(viaFilter.getContentAsString());
        assertThat(viaFilter.getHeader("Retry-After")).isEqualTo(viaAdvice.getHeader("Retry-After"));
        assertThat(viaFilter.getContentType())
                .as("problem+json from both, or an intermediary treats one of them as opaque")
                .startsWith("application/problem+json");
        assertThat(viaAdvice.getContentType()).startsWith("application/problem+json");
    }

    /**
     * <strong>And the same security headers, which the filter has to put back by hand.</strong>
     * The advice's response passes through Spring Security's {@code HeaderWriterFilter}; the
     * filter's is written outside the whole security chain — and worse than merely outside it,
     * because {@code HeaderWriterFilter} writes its block in a {@code finally}, so the headers
     * <em>are</em> on the response when the acquisition failure unwinds past it and then
     * {@code response.reset()} takes them off again. Without {@code DatabaseBusyRefusal.write}
     * restoring them, one response in this product ships with no {@code nosniff}, no frame refusal
     * and no cache directive, and the reverse proxy adds none.
     *
     * <p>Compared as <strong>whatever the advice carries</strong> rather than against a list this
     * test writes down: the values in {@code DatabaseBusyRefusal} are a copy of Spring Security's
     * defaults, and a copy is only safe while something fails when the original moves. An
     * enumeration here would be a second copy of the same decision and would go stale in the same
     * direction. Content framing is excluded because that is the one thing the two writers are
     * allowed to decide separately — one serialises through a message converter, the other writes
     * a fixed byte array.
     *
     * <p><strong>Run twice, because one header of that block is conditional and the plain run is
     * blind to it.</strong> {@code HstsHeaderWriter} writes {@code Strict-Transport-Security} only
     * when {@link jakarta.servlet.http.HttpServletRequest#isSecure()} — RFC 6797 §7.2 — and over
     * MockMvc that is false by default, so HSTS was missing from <em>both</em> sides and a
     * comparison of whatever-the-advice-carries agreed about a header neither response had. It is
     * honest today ({@code server.forward-headers-strategy} is set nowhere, so {@code isSecure()} is
     * false in production too and no response in this product carries HSTS) and it stops being
     * honest the day an operator sets it — a natural companion to
     * {@code RATE_LIMIT_TRUST_FORWARDED_FOR}, which this product already has — at which point every
     * response would gain the header except this one, and a seal that cannot see a header cannot say
     * so. So the pair runs a second time over {@code .secure(true)}, with the conditional header
     * named in the tripwire rather than in the comparison.
     */
    @Test
    @Timeout(240)
    void theFiltersRefusalCarriesEverySecurityHeaderTheAdvicesDoes() throws Exception {
        var ctx = newProject();

        var responses = whileTheWholePoolIsHeld(() -> List.of(
                loginAnonymously(false), authenticated(ctx, false),
                loginAnonymously(true), authenticated(ctx, true)));

        assertTheTwoWritersCarryTheSameHeaderBlock(responses.get(0), responses.get(1), "plain",
                List.of("X-Content-Type-Options", "X-Frame-Options", "Cache-Control"));
        assertTheTwoWritersCarryTheSameHeaderBlock(responses.get(2), responses.get(3), "secure",
                List.of("X-Content-Type-Options", "X-Frame-Options", "Cache-Control",
                        "Strict-Transport-Security"));
    }

    /**
     * @param mustCarry the tripwire — the names whose presence on the ADVICE's response is what
     *                  makes the comparison below compare anything at all. A header absent from both
     *                  sides passes an equality of "whatever the advice carries" while saying
     *                  nothing, which is exactly how HSTS went unnoticed here.
     */
    private void assertTheTwoWritersCarryTheSameHeaderBlock(
            MockHttpServletResponse viaAdvice, MockHttpServletResponse viaFilter, String over,
            List<String> mustCarry) {
        assertThat(viaAdvice.getStatus()).as("the advice's half, over a %s request", over)
                .isEqualTo(503);
        assertThat(viaFilter.getStatus()).as("the filter's half, over a %s request", over)
                .isEqualTo(503);

        var expected = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (var name : viaAdvice.getHeaderNames()) {
            if (!CONTENT_FRAMING.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                expected.put(name, viaAdvice.getHeaderValues(name).stream().map(String::valueOf)
                        .toList());
            }
        }
        assertThat(expected.keySet())
                .as("the tripwire: if the advice's %s response stops carrying one of these, the "
                    + "comparison below starts passing over nothing at all", over)
                .containsAll(mustCarry);

        var actual = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (var name : expected.keySet()) {
            actual.put(name, viaFilter.getHeaderValues(name).stream().map(String::valueOf).toList());
        }
        assertThat(actual)
                .as("""
                    THE FILTER'S 503 MUST CARRY THE SAME SECURITY HEADERS AS THE ADVICE'S, OVER A \
                    %s REQUEST.

                    It is written outside Spring Security, and response.reset() removes the block \
                    HeaderWriterFilter had already written on the way out - so these are re-set by \
                    hand in DatabaseBusyRefusal.write. That hand copy is only safe because this \
                    comparison exists: if a Spring Security upgrade adds, drops or re-values a \
                    default header, it must fail HERE and not on one response in production. The \
                    secure run is the one that sees a CONDITIONAL header: HSTS is written only for \
                    an isSecure() request, so nothing carries it today, and the day \
                    server.forward-headers-strategy is set every response would gain it except \
                    this one.""".formatted(over.toUpperCase(java.util.Locale.ROOT)))
                .isEqualTo(expected);
    }

    /**
     * The two things the writers are allowed to decide for themselves: one renders through a
     * message converter and the other writes a fixed byte array, so the framing of the body is
     * theirs. Everything else on the response belongs to the application and must match.
     */
    private static final List<String> CONTENT_FRAMING =
            List.of("content-type", "content-length");

    /** The advice's document with the one member MVC adds removed, and nothing else touched. */
    private static String withoutInstance(String body) {
        return body.replaceFirst("\"instance\":\"[^\"]*\",", "");
    }

    /**
     * The hand-built body is JSON by concatenation, which is safe only while the one value inside it
     * needs no escaping. Asserted rather than assumed: a later edit that puts a quotation mark into
     * the sentence would otherwise ship a malformed document to whichever half of the traffic the
     * filter answers, and the byte-identity test above would report it as a mismatch rather than as
     * the reason.
     */
    @Test
    void theOneSentenceOnTheWireNeedsNoJsonEscaping() {
        assertThat(DatabaseBusyRefusal.DETAIL)
                .doesNotContain("\"")
                .doesNotContain("\\")
                .doesNotContain("\n");
    }

    /**
     * <strong>The cause-chain walk terminates on a cycle.</strong> A chain is a linked list only by
     * convention, and {@link Throwable#printStackTrace} carries an identity set precisely because it
     * is not one. The first version of this walk tested {@code t.getCause() == t}, which sees a
     * self-reference and spins forever on a cycle of two — inside an exception handler, on the path
     * that runs when the instance is already in trouble.
     */
    @Test
    @Timeout(5)
    void aCycleInTheCauseChainTerminatesInsteadOfHangingTheHandler() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);

        // Cast because SQLException is Iterable<Throwable>, which makes the bare
        // assertThat overload ambiguous — nothing about the assertion itself.
        assertThat((Object) DatabaseBusyRefusal.acquisitionTimeoutIn(first))
                .as("no Hikari exception in the chain, and a bounded walk must say so rather than "
                    + "loop; with an unbounded one this test does not fail, it hangs")
                .isNull();
    }

    /**
     * The tag is the <strong>mapped pattern</strong>, never the request URI — which on almost every
     * other endpoint carries workspace and project UUIDs, and would put tenant ids in a metrics
     * store as well as exploding the series count. The filter's half has no pattern to report, since
     * no handler had been matched when the acquisition failed, and reads {@code unmapped}: that is
     * also how an operator tells the two halves apart on one graph.
     */
    @Test
    @Timeout(180)
    void theCounterIsTaggedByMethodAndMappedPatternOrUnmappedBeforeTheDispatcher() throws Exception {
        var ctx = newProject();

        whileTheWholePoolIsHeld(() -> List.of(
                loginAnonymously(),
                perform(get(AUTHENTICATED_ENDPOINT)
                        .header("Authorization", "Bearer " + ctx.token()))));

        assertThat(counter("POST", "/api/auth/login"))
                .as("the advice knows the pattern the request matched")
                .isNotNull();
        assertThat(counter("GET", "unmapped"))
                .as("the filter does not, and must not invent one out of the URI")
                .isNotNull();
    }

    /**
     * <strong>The operator's copy of the refusal, which is the only place the numbers may
     * appear.</strong> The body says the instance is busy and nothing else; this line has to say
     * how long the request waited and which dial answers it — and it names the pool as <em>the
     * usual</em> remedy rather than the only other one, which is the sentence ADR-0030 already had
     * to correct once in this same class. One sentence for both writers, each under its own logger:
     * what an operator has to recognise at 3am does not depend on which half answered.
     */
    @Test
    @Timeout(180)
    void bothWritersLogTheSameOperatorSentenceNamingTheBoundAndTheDials() throws Exception {
        var ctx = newProject();
        var advice = new ListAppender<ILoggingEvent>();
        var filter = new ListAppender<ILoggingEvent>();
        attach(GlobalExceptionHandler.class, advice);
        attach(DatabaseBusyFilter.class, filter);
        try {
            whileTheWholePoolIsHeld(() -> List.of(
                    loginAnonymously(),
                    perform(get(AUTHENTICATED_ENDPOINT)
                            .header("Authorization", "Bearer " + ctx.token()))));
        } finally {
            detach(GlobalExceptionHandler.class, advice);
            detach(DatabaseBusyFilter.class, filter);
        }

        var fromAdvice = warnings(advice);
        var fromFilter = warnings(filter);
        assertThat(fromAdvice).hasSize(1);
        assertThat(fromFilter).hasSize(1);
        for (var warning : List.of(fromAdvice.getFirst(), fromFilter.getFirst())) {
            assertThat(warning)
                    .contains(ACQUISITION_MS)
                    .contains("DB_POOL_MAX_SIZE")
                    .contains("DB_CONNECTION_TIMEOUT_MS")
                    // Hikari's own message, which is where an operator sees the difference between
                    // a pool full of somebody else's work and a database that is simply gone.
                    .contains("Connection is not available");
        }
        assertThat(fromAdvice.getFirst()).contains("/api/auth/login");
        assertThat(fromFilter.getFirst()).contains(AUTHENTICATED_ENDPOINT);
    }

    /**
     * <strong>The discriminator, in the direction that must NOT answer 503.</strong> Both declared
     * types are also raised for failures that will not clear on their own, and telling that caller
     * "retry in a moment" is a wrong sentence with a wrong remedy — and, on the 5xx class that
     * intermediaries retry automatically, an invitation to repeat it. Anything without Hikari's own
     * exception in its cause chain keeps exactly today's outcome: ERROR, and a 500.
     */
    @Test
    void aFailureThatIsNotAnAcquisitionTimeoutStaysA500() {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/x");

        var response = handler.handleDatabaseBusy(
                new org.springframework.transaction.CannotCreateTransactionException(
                        "the driver is not there at all"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).isNull();
    }

    // ------------------------------------------------------------------ plumbing

    private void assertBodyDescribesNoPool(String body) {
        assertThat(body)
                .as("""
                    THE BODY MAY NOT DESCRIBE THE POOL.

                    This refusal is reachable UNAUTHENTICATED, so its body is read by anonymous \
                    callers and must be identical for every one of them - in particular on both \
                    branches of forgot-password. A pool size, a queue depth, a property name or an \
                    environment variable in here would be operator telemetry handed to whoever \
                    asks. The operator's copy of this refusal is the WARN line.""")
                .doesNotContain("connection-timeout")
                .doesNotContain("DB_CONNECTION_TIMEOUT_MS")
                .doesNotContain("DB_POOL_MAX_SIZE")
                .doesNotContain("HikariPool")
                .doesNotContain("total=");
    }

    private MockHttpServletResponse loginAnonymously() throws Exception {
        return loginAnonymously(false);
    }

    /** The advice's half: no token, so the acquisition fails inside the controller's transaction. */
    private MockHttpServletResponse loginAnonymously(boolean secure) throws Exception {
        return perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever\"}")
                .secure(secure));
    }

    /** The filter's half: the token is resolved in the security chain, before any handler exists. */
    private MockHttpServletResponse authenticated(Ctx ctx, boolean secure)
            throws Exception {
        return perform(get(AUTHENTICATED_ENDPOINT)
                .header("Authorization", "Bearer " + ctx.token())
                .secure(secure));
    }

    private MockHttpServletResponse perform(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private io.micrometer.core.instrument.Counter counter(String method, String route) {
        return meterRegistry.find("hamstrack.db.connection_acquisition_failed")
                .tag("method", method).tag("route", route).counter();
    }

    private static void attach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).addAppender(appender);
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
