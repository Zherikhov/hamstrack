package com.hamstrack.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * <strong>Which address a per-IP budget is keyed on, decided in ONE place</strong> — because it is
 * a security decision and two copies of it are two chances to get it wrong in opposite directions.
 *
 * <p>When {@code app.rate-limit.trust-forwarded-for} is enabled the request is assumed to come
 * through a proxy that strips client-supplied {@code X-Forwarded-For} (prod Caddy ≥ 2.5 does), so
 * the rightmost entry is the real peer. When it is disabled — the DC-safe default, where the app
 * port may be directly reachable — the header is ignored entirely, because a client could otherwise
 * choose a fresh value per request and dodge every per-IP bound in the product.
 *
 * <p>Extracted from {@code AuthRateLimitFilter} when the CSP report sink (HD-264) became the second
 * per-IP budget. The two doors are unrelated and must key identically: an operator reading
 * {@code RATE_LIMIT_TRUST_FORWARDED_FOR} in {@code docs/self-hosting.md} is told one thing about
 * their deployment, and a second implementation of it would make that sentence true of one budget
 * and false of the other.
 */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * The address to key a per-IP budget on. <strong>Never null, and never throws</strong>: it falls
     * back to the socket peer for every header that carries no usable entry.
     *
     * <p>That guarantee is the security-relevant half, not a nicety. This runs two lines before
     * {@code budget.spend} on both doors, on a value the sender writes — so anything that can make
     * it throw is a request that costs no token, repeats without limit, and pays a container stack
     * trace and an {@code /error} dispatch per attempt. The header that did it was
     * {@code X-Forwarded-For: ,}: {@code String.split} drops trailing empty strings, so {@code ","}
     * and {@code ",,"} both yield a <em>zero-length</em> array while {@code isBlank()} reports
     * content, and the old {@code parts[parts.length - 1]} read index {@code -1}. Sealed by
     * {@code ClientIpTest} over the shape (rightmost entries empty) rather than that one spelling.
     *
     * <p>So the scan walks left from the rightmost entry to the first non-empty one. An empty tail
     * does not hide the address in front of it — the rightmost entry a proxy actually wrote is
     * still the peer — and a header with nothing in it at all is worth exactly as much as no header.
     */
    public static String of(HttpServletRequest request, boolean trustForwardedFor) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        var parts = forwarded.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            var candidate = parts[i].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return request.getRemoteAddr();
    }
}
