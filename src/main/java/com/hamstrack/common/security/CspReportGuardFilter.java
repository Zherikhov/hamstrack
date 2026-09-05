package com.hamstrack.common.security;

import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.CspDropReason;
import com.hamstrack.common.ratelimit.ClientIp;
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
 * <strong>The two refusals the CSP report sink has to make before it reads anything</strong>
 * (HD-264): a body over the bound, and a sender over its budget.
 *
 * <p>Both live in a filter rather than in the handler because both are claims about a request the
 * application has decided not to spend work on. A 16 KB cap enforced after Spring has read and
 * parsed the body is a cap that has already paid the cost it exists to avoid — so the length is
 * read from the declared {@code Content-Length} and the chain is never entered. The budget is spent
 * in the same place for the same reason: an over-budget sender must not reach a parser.
 *
 * <p>Registered the way {@code AuthRateLimitFilter} is — constructed inside a
 * {@code FilterRegistrationBean}, never exposed as a {@code Filter} bean of its own, so Spring Boot
 * does not <em>also</em> auto-register it for every request in the application (the trap CLAUDE.md
 * documents). It is bound to the sink's single URL, and it exists only when the sink does.
 *
 * <p><strong>Only POST is filtered</strong>, and nothing is lost by that: the anonymous exemption in
 * {@code SecurityConfig} is itself scoped to {@code POST}, so every other method on this path stays
 * inside the {@code /api/**} authenticated set and is refused a chain earlier, spending no budget on
 * the way. A refusal here carries <strong>no body</strong>: there is nothing a report sender is
 * entitled to know, and a discriminating response is a free oracle about the instance's state.
 */
@RequiredArgsConstructor
public class CspReportGuardFilter extends OncePerRequestFilter {

    /**
     * <strong>16 KB, refused on the declared length.</strong> Derivation: a report body is a flat
     * object of about ten members whose only large values are three URLs; browsers cap
     * {@code script-sample} at 40 characters, and a URL that has survived Tomcat's own header and
     * URI limits is at most ~2 KB — so one report is comfortably under 8 KB, doubled to admit a
     * small Reporting-API batch.
     *
     * <p>A request that declares no length at all (chunked) is let through: it is not a browser's
     * shape, and {@code CspReportController} reads at most this many bytes anyway, so the bound is
     * enforced either way and only the <em>cheapness</em> of the refusal differs.
     */
    static final int MAX_BODY_BYTES = 16 * 1024;

    /**
     * The budget key this filter charged, handed to the handler on the request rather than
     * recomputed there. {@link CspReportSink} spends the same pot once per <em>line</em> it writes,
     * so the two halves of one budget must key identically — and the trust decision that produces
     * the key ({@code app.rate-limit.trust-forwarded-for}) is read in exactly one place because a
     * second copy of it is a second thing to get wrong.
     */
    static final String CLIENT_IP_ATTRIBUTE = CspReportGuardFilter.class.getName() + ".clientIp";

    private final CspReportBudget budget;
    private final ProductMetrics metrics;
    /** See {@link ClientIp} — the same decision the auth budget keys on, not a second copy. */
    private final boolean trustForwardedFor;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            metrics.cspReportDropped(CspDropReason.TOO_LARGE);
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        var clientIp = ClientIp.of(request, trustForwardedFor);
        request.setAttribute(CLIENT_IP_ATTRIBUTE, clientIp);
        long retryAfter = budget.spend(clientIp);
        if (retryAfter > 0) {
            metrics.cspReportDropped(CspDropReason.BUDGET);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Anything but a POST was refused by the security chain already, and costs no budget. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }
}
