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
        try {
            rateLimitService.checkAuthRequestAllowed(clientIp(request));
        } catch (RateLimitedException e) {
            writeTooManyRequests(response, e);
            return;
        }
        filterChain.doFilter(request, response);
    }

    // Only POSTs consume budget — the GET /verify-email legacy redirect and
    // CORS preflights shouldn't burn it
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    /**
     * When {@code trustForwardedFor} is enabled the request is assumed to come
     * through a trusted proxy that strips client-supplied X-Forwarded-For (prod
     * Caddy ≥ 2.5 does), so the rightmost entry is the real peer address. When
     * disabled (default — DC self-host on a directly-reachable app port) the
     * header is ignored entirely, because a client could otherwise spoof it to
     * dodge the per-IP budget; the socket address is used instead.
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        var parts = forwarded.split(",");
        return parts[parts.length - 1].trim();
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
