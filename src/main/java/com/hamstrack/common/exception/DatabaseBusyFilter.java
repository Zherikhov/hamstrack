package com.hamstrack.common.exception;

import com.hamstrack.common.config.DatabaseTimeoutConsistency;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.security.ContentSecurityPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;

/**
 * <strong>The half of {@code 503 DATABASE_BUSY} an exception handler cannot reach: an acquisition
 * that fails inside the filter chain, before any handler exists to raise it from</strong>
 * (HD-233).
 *
 * <p>{@link GlobalExceptionHandler#handleDatabaseBusy} is a {@code @RestControllerAdvice}, so it
 * sees what a <em>handler method</em> raises. On an authenticated request the first thing to touch
 * the database is {@code JwtAuthenticationFilter}'s user lookup, whose failure escapes the filter
 * chain and reaches no advice at all — so before this filter existed, the named refusal covered the
 * unauthenticated minority and the traffic that matters answered a container-rendered
 * <strong>500</strong>.
 *
 * <p><strong>Why that 500 was not merely untidy.</strong> The SPA's retry policy
 * ({@code queryClient.ts}) declines to retry 422/429/502/503/504 and <em>deliberately does</em>
 * retry a 500, on the reasoning that a 500 is a bug on one request where a fresh read may well
 * succeed. Under pool starvation that reasoning inverts: every authenticated tab re-issues each
 * failed query at the moment the pool has least to give, so the shipped acquisition bound turned a
 * 30-second park into a 3-second failure and doubled it. The 503 exists to be recognised, and until
 * this filter it was unreachable on the requests that could amplify.
 *
 * <p><strong>And it makes the counter the total rather than a floor.</strong>
 * {@code hamstrack_db_connection_acquisition_failed_total} is written by whoever answers, so while
 * only the advice answered, the alert built on it was quietest during a real incident — it counted
 * the anonymous minority. The tag differs and says which half: a refusal from here carries
 * {@code route="unmapped"}, because no handler had been matched when the acquisition failed, and
 * the mapped pattern is the only route label allowed near this counter (a URI carries workspace and
 * project ids).
 *
 * <h2>Where it sits: TWO notches in from outermost, and each notch is a separate relation</h2>
 * Registered exactly the way {@code AuthRateLimitFilter} is: constructed inside a
 * {@code FilterRegistrationBean} and never exposed as a {@code Filter} bean of its own, which is
 * what keeps Spring Boot from <em>also</em> auto-registering it (the trap CLAUDE.md documents, and
 * the reason {@code SecurityConfig} has to disable a registration for {@code JwtAuthenticationFilter}
 * — that one is a bean because the security chain needs it by type). Its order is
 * {@code Ordered.HIGHEST_PRECEDENCE + 2}, and both notches are load-bearing:
 *
 * <ul>
 *   <li><strong>Inside {@code AuthRateLimitFilter}</strong> ({@code HIGHEST_PRECEDENCE}), which is
 *       what lets that filter refund the token a 503 would otherwise cost: it reads
 *       {@code response.getStatus()} after its own {@code chain.doFilter} returns, and an exception
 *       caught <em>above</em> it would unwind past that line instead.</li>
 *   <li><strong>Inside Boot's {@code ServerHttpObservationFilter}</strong>, and this notch was won
 *       by a coin toss until it was measured. {@code WebMvcObservationAutoConfiguration} registers
 *       that filter at {@code Ordered.HIGHEST_PRECEDENCE + 1} — the constant this one was first
 *       given. Two {@code FilterRegistrationBean}s with equal order are sorted by
 *       {@code AnnotationAwareOrderComparator} over a stable sort, so the winner was bean-definition
 *       registration order and nothing in the tree pinned it. In the losing direction the exception
 *       unwinds <em>through</em> the observation filter, which stops the observation while
 *       {@code response.getStatus()} is still 200 — so every refusal this filter writes is recorded
 *       as {@code status="200", outcome="SUCCESS"}, the {@code HighErrorRate} rule
 *       ({@code status=~"5.."}) never sees it, and the latency histogram counts a starved instance
 *       as fast successes. That is this ticket's own failure mode moved from the product counter
 *       onto the framework one, on precisely the authenticated half this filter exists to cover.</li>
 * </ul>
 *
 * <p>Nothing ahead of it touches a database — the rate limiter's state is in memory, the
 * observation filter's is a registry — so neither notch costs coverage.
 * {@code DatabaseBusyFilterOrderTest} fails if either relation inverts.
 *
 * <h2>What it does not cover, stated as the property rather than as a list</h2>
 * It answers a failure that unwinds to this frame while the response can still be given a status.
 * A failure that cannot reach that point gets no 503 and cannot: one raised on a dispatch this
 * filter is not run for ({@code ASYNC} and {@code ERROR}, per {@link OncePerRequestFilter}'s
 * defaults, where the original response has already been decided or already begun), one raised
 * after the response has been committed (a streaming download, an SSE stream), and one raised
 * outside a request altogether (a scheduled job, the shutdown residue write) — none of which has a
 * status left to set. Those are the same conditions under which no other refusal in this product
 * can change an answer either.
 */
@RequiredArgsConstructor
@Slf4j
public class DatabaseBusyFilter extends OncePerRequestFilter {

    /**
     * Read for one thing only: so the WARN can say how long the request was allowed to wait. The
     * number reaches the log; nothing about the pool reaches the caller. Injected from the class
     * that validates and resolves the acquisition bound rather than re-read, because two readings
     * of one value is how they come to disagree.
     */
    private final DatabaseTimeoutConsistency databaseTimeouts;

    /** Makes a refused request alertable without anybody reading a log. */
    private final ProductMetrics productMetrics;

    /**
     * The seventh header of the block {@code response.reset()} removes (HD-264). Injected rather
     * than restated as a constant beside the other six: the policy is suppressible and its
     * {@code report-uri} clause is conditional on the sink, so the only copy that cannot disagree
     * with the rest of the product's responses is the one the instance actually emits.
     */
    private final ContentSecurityPolicy contentSecurityPolicy;

    /**
     * Catches both unchecked throwables a filter may propagate. {@link RuntimeException} is how the
     * condition actually arrives today — Spring's {@code CannotCreateTransactionException} out of a
     * repository call in {@code JwtAuthenticationFilter} — and {@link ServletException} is included
     * because a filter between here and there is entitled to wrap what it caught, and a coverage
     * claim that holds only while nobody does that is a claim about today's filter list rather than
     * about the mechanism. Anything whose chain does not contain Hikari's own exception is
     * rethrown untouched, so every other failure keeps exactly the outcome it has now.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            var timeout = DatabaseBusyRefusal.acquisitionTimeoutIn(e);
            // isAsyncStarted() as well as isCommitted(): the commit check is a synchronous read of
            // a value another thread owns once startAsync() has been called, so on that one shape
            // of request a container thread can commit between the check and the reset() below and
            // turn the original exception into an IllegalStateException — the caller would then
            // lose both the 503 AND the diagnosis. Not reachable today (nothing that startAsync()s
            // reaches a database before the dispatch), which is why it is one condition rather than
            // a mechanism.
            if (timeout == null || response.isCommitted() || request.isAsyncStarted()) {
                throw e;
            }
            refuse(request, response, timeout);
        }
    }

    /**
     * The response is reset before the refusal is written: a handler that had begun buffering (a
     * partially serialized body, a header it set on the way) must not have its fragment prepended
     * to a problem document. {@code reset()} is safe here precisely because
     * {@link HttpServletResponse#isCommitted()} was checked first — past that point the bytes are
     * already on the wire and this filter rethrows instead.
     *
     * <p>What it also resets is Spring Security's header block, which {@code HeaderWriterFilter}
     * had already written in its own {@code finally} on the way out. So the security headers are
     * re-set by {@link DatabaseBusyRefusal#write}, not because this filter is outside that one
     * (it is, but that alone would leave them absent rather than removed) — because the reset
     * takes them off.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response,
                        SQLTransientConnectionException timeout) throws IOException {
        DatabaseBusyRefusal.logRefusal(log, databaseTimeouts.acquisitionBoundMs(),
                request.getMethod(), request.getRequestURI(), timeout);
        // Null, never the URI: no handler was matched, and a request URI carries workspace and
        // project ids into a metrics store as well as exploding the series count. The counter
        // renders it as route="unmapped", which is also how an operator tells this half from the
        // advice's.
        productMetrics.connectionAcquisitionFailed(request.getMethod(), null);
        response.reset();
        DatabaseBusyRefusal.write(request, response, contentSecurityPolicy);
    }
}
