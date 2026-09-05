package com.hamstrack.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP request budget for the sensitive auth endpoints. Registered as a
 * plain servlet filter with explicit URL patterns (see {@code RateLimitConfig})
 * — deliberately NOT a {@code @Component}, so Spring Boot doesn't also
 * auto-register it for every request (see CLAUDE.md gotchas).
 *
 * <p>Runs before Spring Security: rejected requests never reach the
 * authentication machinery. {@code /api/auth/refresh} and {@code /logout} are
 * not covered — they're driven by the 256-bit refresh cookie, are called on
 * every page load, and brute-forcing them is infeasible.
 */
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    // See RateLimitProperties#trustForwardedFor — default false is DC-safe.
    private final boolean trustForwardedFor;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var clientIp = clientIp(request);
        // The window the token is charged to, carried to the refund: a request can park out the
        // acquisition bound and finish in the NEXT minute, and a refund into that one gives back a
        // token it never took.
        long chargedToMinute;
        try {
            chargedToMinute = rateLimitService.checkAuthRequestAllowed(clientIp);
        } catch (RateLimitedException e) {
            writeTooManyRequests(response, e);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            refundIfTheInstanceRefusedItself(clientIp, chargedToMinute, response);
        }
    }

    /**
     * <strong>A refusal the instance issues about its own state is not charged to the caller</strong>
     * (HD-233). The budget is spent above, before the chain runs, so without this a
     * {@code 503 DATABASE_BUSY} costs a token exactly like a real login attempt — and the
     * {@code Retry-After: 1} that refusal carries then prescribes a rate this very filter answers
     * with a 429, on the endpoint a locked-out user needs most, in the middle of an incident.
     *
     * <p><strong>What the refunded token actually bought, since the refund is capped because of
     * it.</strong> Not nothing: a 503 says the instance could not <em>begin</em> the work — no
     * query, no bcrypt, no mail — but the request still reached {@code HikariPool.getConnection()}
     * and held a Tomcat worker until the pool gave up: the whole acquisition bound, and up to twice
     * it, because a borrow that ends on a dead-connection probe overruns the hard timeout by as much
     * as {@code validation-timeout} — which this product holds equal to the acquisition bound, which
     * is what makes the overshoot a figure it knows. So the shipped 3000 ms is <strong>3 to 6</strong>
     * thread-seconds, comfortably more than a successful login costs. Nothing about the cap follows
     * from the correction and that is worth saying: a more expensive park makes the refund
     * <em>dearer</em>, so the ceiling below is better justified at 6 s than it was at 3, not in need
     * of revisiting. That is why
     * {@link RateLimitService#refundAuthRequest} refunds at most a budget's worth per window: an
     * uncapped refund would take the per-IP bound off these endpoints entirely for as long as the
     * pool is starved, in the one condition a flood can create and then sustain.
     *
     * <p><strong>503, and deliberately the whole status rather than the one {@code errorType}
     * underneath it.</strong> The class is "the instance refusing on account of its own state", and
     * every member of it has the same claim on a refund; naming {@code DATABASE_BUSY} would be a
     * rule about today's only member, which is how a rule goes stale one entry before its list
     * does. It also has to read the response rather than catch an exception, which is what covers
     * both writers of the 503 at once: {@code GlobalExceptionHandler.handleDatabaseBusy} for a
     * failure that reached a handler, and {@code DatabaseBusyFilter} — registered inside this
     * filter for exactly this reason — for one that unwound through the chain.
     *
     * <p>5xx as a whole is NOT the rule, from the other side. A 500 is raised at whatever point the
     * handler broke, which can be after the expensive part: refunding those would hand an attacker
     * who finds one cheap crash an unbudgeted loop over the most expensive door in the product (a
     * login verifies a BCrypt hash at cost 12), which is the abuse this filter exists to bound. 4xx
     * is untouched for the same reason: a rejected password, a malformed body and a rate-limited
     * caller all spend budget, because those are the attempts the budget exists to count.
     */
    private void refundIfTheInstanceRefusedItself(String clientIp, long chargedToMinute,
                                                  HttpServletResponse response) {
        if (response.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            rateLimitService.refundAuthRequest(clientIp, chargedToMinute);
        }
    }

    // Only POSTs consume budget — the GET /verify-email legacy redirect and
    // CORS preflights shouldn't burn it
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    /**
     * Shared with the CSP report sink's budget since HD-264 — see {@link ClientIp} for the
     * trusted-proxy reasoning and for why there is exactly one copy of it.
     */
    private String clientIp(HttpServletRequest request) {
        return ClientIp.of(request, trustForwardedFor);
    }

    // Hand-built RFC 9457 body (fields are constants — no user input): Boot 4
    // doesn't expose an ObjectMapper bean with plain jackson-databind on the
    // classpath, and pulling in a serializer for this would be overkill
    private void writeTooManyRequests(HttpServletResponse response, RateLimitedException e) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"%s"}""".formatted(e.getMessage()));
    }
}
