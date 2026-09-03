package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.config.WriteProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The per-principal budget on uploaded BYTES (HD-191 §6.2) — the same window as every other
 * budget, denominated in bytes instead of requests.
 *
 * <h2>Why a request count is not enough</h2>
 * {@link WriteRateLimiter} bounds how often somebody may write. It says nothing about how much
 * they may move, and the two are not proportional: at any legal request rate, one upload of the
 * maximum permitted file is a different quantity of work — and on Cloud a different amount of
 * money — from one comment. The storage quota does not close that gap either, because
 * <strong>the quota never sees churn</strong>: upload → delete → upload leaves
 * {@code bytes_used} exactly where it started and bills every PUT and every stored byte in
 * between. This is the only control that costs that loop anything.
 *
 * <h2>Why it is not an interceptor</h2>
 * Its cost is {@code MultipartFile.getSize()}, and an interceptor runs before argument
 * resolution — it has neither the parsed part nor a reason to look at one. So it is spent inside
 * {@code AttachmentService.upload}, in the cheap pre-check phase: after the empty/size/extension
 * checks and <em>before</em> any DB work, so a refused upload takes no lock and touches no row.
 *
 * <p>That places it textually before tenancy resolution, which is safe for exactly the reason
 * {@link PerPrincipalMinuteBudget} gives: <strong>the key is the caller</strong>, so the 429 is
 * identical for a real workspace, a nonexistent one and somebody else's, and the 404-for-all-three
 * contract is untouched. The same sentence is what makes the recipient-keyed mail ceilings the
 * opposite case — theirs is keyed on a victim, so theirs must run after membership.
 *
 * <p>Because it is not a path binding it is invisible to {@code ThrottleCoverageTest}, whose seal
 * is the set of registered PATTERNS. Its seal is {@code AttachmentDoorsTest}: every call site of
 * {@code FileStorage.store} is preceded, in the same method, by a byte spend and a quota
 * reservation.
 *
 * <p>The refusal discloses nothing — the budget is the caller's own — so the detail is the shared
 * "Too many uploaded bytes — retry in Ns" with a {@code Retry-After}. Unlike the quota's 409, the
 * wait here is real: the window does empty.
 */
@Service
public class UploadByteBudget extends PerPrincipalMinuteBudget {

    private final WriteProperties writeProperties;
    private final RateLimitProperties rateLimitProperties;

    public UploadByteBudget(WriteProperties writeProperties,
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
        return writeProperties.uploadBytesPerMinute().toBytes();
    }

    @Override
    protected RateLimitKind kind() {
        return RateLimitKind.UPLOAD_BYTES;
    }

    @Override
    protected String surface() {
        return "uploaded bytes";
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void sweep() {
        evictStaleEntries();
    }
}
