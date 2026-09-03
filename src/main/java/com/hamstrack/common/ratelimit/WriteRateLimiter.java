package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.config.WriteProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The per-principal budget for the mutating content surface (HD-191 §6.1).
 *
 * <h2>Why the writes needed one when three limiters already existed</h2>
 * They covered reads and authentication. A per-IP window guards the auth endpoints, a
 * per-principal budget guards reports, another guards search — and <strong>every mutating
 * endpoint in the product was unbudgeted</strong>. That is the same finding
 * {@code SearchRateLimiter} records about its own surface, one layer over: the expensive door
 * was the one with nothing in front of it. An issue update writes history rows, bumps
 * {@code @Version}, fans out SSE to every watcher and writes notifications; an attachment
 * upload hands bytes to {@code FileStorage}, which on Cloud is billed per request as well as per
 * byte. A search is throttled and does less than either.
 *
 * <p><strong>It is not the storage quota and does not stand in for one.</strong> This is keyed
 * on the actor, lives in memory per node, and resets every minute — so N replicas allow N× and a
 * restart clears it, which is acceptable for a bound on abuse and is not acceptable for a bound
 * on a bill. The quota is keyed on the tenant, lives in PostgreSQL and never resets. Removing
 * either leaves a real hole: one member can exhaust a workspace quota entirely within their own
 * budget (~40 minutes at the shipped numbers against a 10 GB Cloud quota), and the quota never
 * sees churn at all, because upload → delete → upload moves the total nowhere while billing
 * every PUT in between.
 *
 * <p>Everything mechanical — the fixed window, the per-principal key, the 429 +
 * {@code Retry-After}, the metric-not-a-log-line refusal, the master switch — is
 * {@link PerPrincipalMinuteBudget}'s.
 *
 * <p>Lives in {@code common.ratelimit} rather than in a feature package because its surface is a
 * category rather than a feature: it covers issue, comment, attachment and rank writes, which
 * are three different packages, and putting it in one of them would make the other two look
 * exempt.
 */
@Service
public class WriteRateLimiter extends PerPrincipalMinuteBudget {

    private final WriteProperties writeProperties;
    private final RateLimitProperties rateLimitProperties;

    public WriteRateLimiter(WriteProperties writeProperties,
                            RateLimitProperties rateLimitProperties,
                            ProductMetrics metrics) {
        super(metrics);
        this.writeProperties = writeProperties;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected boolean enabled() {
        return rateLimitProperties.enabled();
    }

    @Override
    protected long limit() {
        return writeProperties.requestsPerMinute();
    }

    @Override
    protected RateLimitKind kind() {
        return RateLimitKind.WRITE_REQUESTS;
    }

    @Override
    protected String surface() {
        return "write requests";
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void sweep() {
        evictStaleEntries();
    }
}
