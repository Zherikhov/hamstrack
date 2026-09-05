package com.hamstrack.common.exception;

import com.hamstrack.common.security.ContentSecurityPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

/**
 * <strong>The single definition of "this instance could not get a database connection in time",
 * because it is answered from two places and they may not drift</strong> (HD-233).
 *
 * <p>Two writers, one refusal. {@link GlobalExceptionHandler#handleDatabaseBusy} answers a failure
 * that reached a handler method, and renders a {@link ProblemDetail} through the MVC message
 * converters; {@link DatabaseBusyFilter} answers one that unwound through the filter chain without
 * ever reaching the dispatcher, and has to write the bytes itself — Boot 4 exposes no
 * {@code ObjectMapper} bean out there, exactly as {@code AuthRateLimitFilter} records. Two
 * hand-written copies of one JSON body is the defect this ticket is otherwise about, so every value
 * that goes on the wire is a constant here, and {@code DatabaseBusyRefusalTest} pins the two
 * renderings against each other character-for-character.
 *
 * <p><strong>Everything this class puts in the body is a compile-time constant, and that is the
 * security property.</strong> This refusal is reachable unauthenticated, so an anonymous caller
 * reads it — no pool size, no queue depth, no property name, no environment variable, no SQL, and
 * in particular nothing that differs between two callers, since both branches of
 * {@code forgot-password} must look identical. The operator's copy of the refusal is
 * {@link #logRefusal}, which names the bound and the dials.
 *
 * <p>The claim is about what <em>this class</em> writes, not about every byte of the response, and
 * the distinction was measured rather than assumed: Spring MVC fills a null {@code instance} with
 * the request URI as it serialises, so the advice's document carries the caller's own path and the
 * filter's does not. That is the single permitted difference between the two; see
 * {@link #BODY_JSON}.
 */
public final class DatabaseBusyRefusal {

    /**
     * The discriminator a client branches on. It names the <em>condition</em> and not the pool:
     * how many connections exist, and how many callers are queued for one, is operator telemetry.
     */
    public static final String ERROR_TYPE = "DATABASE_BUSY";

    /** Long enough for a transaction ahead of this one to end, short enough to feel like a hiccup. */
    public static final int RETRY_AFTER_SECONDS = 1;

    /** The one sentence the caller is given, on both paths. */
    public static final String DETAIL =
            "The instance is at its database capacity right now. This is temporary — retry in a "
            + "moment.";

    /**
     * The body as bytes, for the writer that has no serializer: <strong>character-for-character what
     * MVC renders from {@link #problemDetail()}, minus one member — and the omission is the only
     * difference either writer is allowed to have</strong>. Field order, the absent {@code type}
     * (Spring omits {@code about:blank}) and the trailing {@code errorType} are all copied from what
     * the advice actually produces rather than from what a problem document usually looks like;
     * {@code DatabaseBusyRefusalTest} compares the two and fails if a Spring or Jackson upgrade
     * moves either.
     *
     * <p><strong>The member left out is {@code instance}, and leaving it out is deliberate.</strong>
     * Spring MVC fills a null {@code instance} with the request URI on its way out
     * ({@code HttpEntityMethodProcessor}), so the advice's document is <em>not</em> entirely
     * compile-time constants — it echoes the path the caller asked for. Reproducing that here would
     * mean escaping a caller-supplied string into a hand-built JSON document, and hand-written
     * escaping of attacker-controlled input is a worse thing to own than a missing optional member
     * that no client in this product reads. It discloses nothing either way: the value is the
     * caller's own request path.
     *
     * <p>No escaping is applied to what remains and none is needed: {@link #DETAIL} contains no
     * quote and no backslash, which is itself asserted, because a JSON body built by concatenation
     * is only safe while that stays true.
     */
    static final String BODY_JSON =
            "{\"detail\":\"" + DETAIL
            + "\",\"status\":" + HttpStatus.SERVICE_UNAVAILABLE.value()
            + ",\"title\":\"" + HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase()
            + "\",\"errorType\":\"" + ERROR_TYPE + "\"}";

    /**
     * How many links of a cause chain are walked before giving up. A chain is a linked list only by
     * convention: {@link Throwable#printStackTrace} carries an identity set precisely because a
     * cycle of two is constructible, and a {@code t.getCause() == t} test — which is what this walk
     * shipped first — sees a self-reference and loops forever on a 2-cycle. A bound is one line,
     * needs no allocation on the path that runs on every failed request, and is far past any real
     * chain (Hikari's is three). The same number as {@code GlobalExceptionHandler}'s
     * {@code MAX_CAUSE_DEPTH}, which is the other bounded walk over the same chains and had this
     * right first: two limits for one idea is how they come to disagree.
     */
    private static final int MAX_CAUSE_HOPS = 20;

    private DatabaseBusyRefusal() {
    }

    /**
     * The pool's own exception if this failure is an acquisition timeout, {@code null} if it is any
     * other reason a connection or a transaction could not be had.
     *
     * <p>It returns the exception rather than a boolean because the caller needs both answers from
     * one walk — whether to refuse with a 503, and the message to log. The message is two levels
     * down ({@code CannotCreateTransactionException} → {@code JDBCConnectionException} →
     * {@code SQLTransientConnectionException}) and the outer one says nothing an operator can act
     * on.
     *
     * <p>Hikari raises {@link SQLTransientConnectionException} from
     * {@code HikariPool.createTimeoutException} and from nowhere else in the borrow path, so the
     * type is the discriminator and no message is parsed. Note what it deliberately does
     * <em>not</em> distinguish: a pool full of somebody else's work and a database that is
     * unreachable both surface as this exception after the same wait, and both are honestly
     * described by "could not obtain a connection in time" with a retry invited. The WARN carries
     * Hikari's own message, where the two look nothing alike.
     */
    public static SQLTransientConnectionException acquisitionTimeoutIn(Throwable ex) {
        Throwable t = ex;
        for (int hop = 0; t != null && hop < MAX_CAUSE_HOPS; hop++, t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException timeout) {
                return timeout;
            }
        }
        return null;
    }

    /** The refusal as the advice returns it, so the status, the header and the body agree. */
    public static ProblemDetail problemDetail() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, DETAIL);
        problem.setProperty("errorType", ERROR_TYPE);
        return problem;
    }

    /**
     * <strong>The security headers every other response in this product carries, restated here
     * because this one is written outside the filter that owns them.</strong>
     *
     * <p>The mechanism is worth stating precisely, because "outside Spring Security" alone does not
     * predict it. {@code HeaderWriterFilter} writes its block in a {@code finally}, so the headers
     * <em>are</em> on the response when the acquisition failure unwinds past it — and then
     * {@code DatabaseBusyFilter} calls {@link HttpServletResponse#reset()}, which takes them off
     * again, correctly and for its own good reason. Without restoring them, one response in this
     * product ships with no {@code nosniff}, no frame refusal and no cache directive, and the
     * reverse proxy adds none either. The impact is small — the body is a constant
     * {@code application/problem+json} with nothing caller-derived in it, and a 503 is not
     * heuristically cacheable — but a product invariant that holds on every response except one is
     * not a small thing to leave unstated.
     *
     * <p>These values are Spring Security's own defaults, copied, which is a second copy of
     * somebody else's decision and is only acceptable because the copy is <em>sealed</em>:
     * {@code DatabaseBusyRefusalTest} compares this response's whole header set against the
     * advice's, which came through {@code HeaderWriterFilter}. A Spring Security upgrade that adds,
     * drops or re-values a default header fails there rather than reaching a caller — and the
     * comparison is against whatever the advice carries rather than against a list, because a list
     * would be a third copy going stale in the same direction.
     *
     * <p><strong>The seventh header this response owes is the Content-Security-Policy, and it is
     * NOT in this map</strong> (HD-264). It belongs to the same block — {@code SecurityConfig}
     * writes it on every other response and {@code response.reset()} takes it off this one — but it
     * is not a compile-time constant in the way the six above are: an operator suppresses the whole
     * header with {@code CSP_REPORT_ONLY_ENABLED=false}, and the {@code report-uri} clause is
     * present exactly when the sink is. A literal here would therefore ship a 503 whose policy
     * disagrees with every other response on the deployment where the sink is on — which is Cloud,
     * i.e. every deployment the reports come from — and it would do so silently, because the
     * comparison below runs at the test profile's settings. So the resolved value is handed to
     * {@link #write} instead, by the one caller that has it. Nothing about the seal changes: the
     * filter's response is compared against whatever the advice carries, so an absent CSP is red
     * whichever mechanism put it there.
     */
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "X-XSS-Protection", "0",
            HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate",
            HttpHeaders.PRAGMA, "no-cache",
            HttpHeaders.EXPIRES, "0");

    /**
     * <strong>The one member of that block Spring Security writes conditionally, so it is restored
     * conditionally on the same condition.</strong> {@code HstsHeaderWriter}'s default
     * {@code RequestMatcher} is {@code SecureRequestMatcher}, i.e. {@code request.isSecure()} —
     * RFC 6797 §7.2 forbids the header on a plain-HTTP response — so every other response in this
     * product carries it exactly when the request was secure, and this one now does too.
     *
     * <p>It carries no weight today and is written for the day it does: nothing sets
     * {@code server.forward-headers-strategy}, so behind the TLS-terminating proxy this product
     * expects {@code isSecure()} is false and <em>no</em> response carries HSTS. The moment an
     * operator sets it — a natural companion to {@code RATE_LIMIT_TRUST_FORWARDED_FOR}, which this
     * product already has — every response gains the header and this one would have been the single
     * exception, on a refusal reachable unauthenticated. The value is Spring Security's own default
     * ({@code max-age=31536000 ; includeSubDomains}, spaces included, from
     * {@code HstsHeaderWriter.updateHstsHeaderValue}), copied under the same terms as the six above:
     * the copy is safe only because {@code DatabaseBusyRefusalTest} runs the pair a second time over
     * a secure request and compares the whole block.
     */
    private static final String HSTS_HEADER = "Strict-Transport-Security";

    private static final String HSTS_VALUE = "max-age=31536000 ; includeSubDomains";

    /**
     * The refusal written straight onto the response, for the path that has no message converter
     * behind it. Bytes rather than {@link HttpServletResponse#getWriter()}: a {@code PrintWriter}
     * encodes with the response's charset, which defaults to ISO-8859-1 and would mangle the one
     * non-ASCII character in {@link #DETAIL} into something the other path does not send.
     *
     * <p>It takes the request for one reason: one header of the security block is conditional on
     * {@link HttpServletRequest#isSecure()} and cannot be restored without asking
     * ({@link #HSTS_HEADER}). It takes the policy for a different one: that header's value is
     * resolved from configuration at startup rather than being a constant — see
     * {@link #SECURITY_HEADERS} — so the only honest copy is the one the instance actually emits.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response,
                             ContentSecurityPolicy contentSecurityPolicy) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        SECURITY_HEADERS.forEach(response::setHeader);
        contentSecurityPolicy.applyTo(response);
        if (request.isSecure()) {
            response.setHeader(HSTS_HEADER, HSTS_VALUE);
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = BODY_JSON.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * <strong>The operator's copy of the refusal, and the only place any number appears.</strong>
     * Written under the caller's own logger so each path is attributable to the class that answered
     * it, while the sentence — and therefore what an operator has to recognise at 3am — is one.
     *
     * <p>It logs the POOL's message rather than the throwable, and rather than the outer
     * exception's {@code toString}: the outer one says only "Could not open JPA EntityManager for
     * transaction", which is true of every reason a transaction cannot begin, while Hikari's
     * carries the whole diagnosis — the elapsed wait and {@code (total=, active=, idle=, waiting=)},
     * which is what separates a pool full of somebody else's work from a database that is simply
     * gone. It is also what keeps the two numbers in this line honest against each other: the bound
     * is what the request was <em>allowed</em> to wait, and the elapsed time Hikari reports can
     * exceed it by up to one validation probe — {@code HikariPool.getConnection} re-derives its
     * remaining budget only after {@code isConnectionDead(...)} returns. Hence "within the bound"
     * rather than a claim about how long this particular request actually parked, which only the
     * pool's own sentence can say.
     *
     * <p>The frames are dropped for {@code handlePessimisticLock}'s reason: they are the
     * transaction manager's and name no application code, because the request had not begun doing
     * anything yet. WARN rather than ERROR — one refused acquisition is the bound doing its job,
     * and {@code ProductMetrics.connectionAcquisitionFailed} is what turns a rate of them into an
     * alert.
     */
    public static void logRefusal(Logger log, long acquisitionBoundMs, String method, String route,
                                  SQLTransientConnectionException timeout) {
        log.warn("Could not obtain a database connection within the {} ms acquisition bound on "
                 + "{} {} — answering 503 DATABASE_BUSY with Retry-After {}s. The pool said: {}. "
                 + "The usual remedy is a larger pool (DB_POOL_MAX_SIZE); raising "
                 + "spring.datasource.hikari.connection-timeout (DB_CONNECTION_TIMEOUT_MS) is "
                 + "another dial, and it buys waiting rather than capacity. "
                 + "EXPENSIVE_READ_MAX_IN_FLIGHT is what bounds how much of the pool the "
                 + "expensive-read surface may hold at once.",
                acquisitionBoundMs, method, route, RETRY_AFTER_SECONDS, timeout.getMessage());
    }
}
