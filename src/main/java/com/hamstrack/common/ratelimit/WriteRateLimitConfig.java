package com.hamstrack.common.ratelimit;

import com.hamstrack.report.ratelimit.ReportRateLimitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Registers the write budget across the mutating content surface (HD-191 §9.1).
 *
 * <p>The reports and search configurers' argument, applied to writes: bind the budget to the
 * path so the next endpoint under it inherits the bound by construction rather than by somebody
 * remembering. What is different here — and it is the whole reason
 * {@code WriteThrottleCoverageTest} exists as a second seal — is that the binding is
 * <strong>method-conditioned</strong>. The path it covers is full of deliberately unbudgeted
 * reads, so "this handler is behind a {@link PrincipalThrottleInterceptor}" is no longer the
 * whole question; "…that applies to its verb" is.
 *
 * <p><strong>Ordered after the search budget</strong>, whose own order is after the reports one.
 * The three patterns overlap nowhere today; the order is declared so that a future overlap is
 * deterministic rather than dependent on bean-registration order — the failure
 * {@link ReportRateLimitConfig#ORDER} spells out at length, where a request refused by one budget
 * has already spent a unit of another and the message and the metric are whichever configurer
 * happened to be scanned first.
 */
@Configuration
@Order(WriteRateLimitConfig.ORDER)
@RequiredArgsConstructor
public class WriteRateLimitConfig implements WebMvcConfigurer {

    /** After reports (0) and search (1). See the class javadoc for why this is declared at all. */
    public static final int ORDER = ReportRateLimitConfig.ORDER + 2;

    /**
     * <strong>The mutating content surface, as a category rather than as a list of today's
     * endpoints.</strong> One pattern covers issue create/update/delete, comment
     * create/update/delete, attachment upload/delete and the backlog rank writes — everything
     * that hangs off an issue — so the next mutation added under it is budgeted the moment its
     * mapping exists.
     *
     * <p>It is NOT {@code /api/workspaces/**}. That would sweep in administrative and taxonomy
     * writes (permission-gated, low frequency, bounded by the catalog they edit), membership and
     * invitation writes (already bounded by the recipient-keyed mail ceilings on a different
     * axis) and saved-filter writes (already on the search budget) — charging one pot for four
     * unrelated behaviours, and starving an administrator's bulk edit to protect the issue
     * surface. Those categories are named, one reason each, in {@code WriteThrottleCoverageTest}'s
     * exemption set, which is where the decision is reviewable rather than implied by a pattern.
     */
    static final String WRITE_PATH = "/api/workspaces/*/projects/*/issues/**";

    /**
     * <strong>Every mutating method, not only creation.</strong> A budget covering {@code POST}
     * alone leaves the surface half-bounded, which is the shape {@code PlanningThrottleParityTest}
     * already names as worse than either whole answer: a client refused on the create simply
     * retries with the patch. And an update is not the cheap half — it writes history rows, bumps
     * {@code @Version} and fans out SSE and notifications.
     *
     * <p>{@code GET} is absent on purpose. Reads under this path are ordinary, bounded by what
     * they return, and one of them ({@code …/backlog/**} is a sibling path, HD-174) is the subject
     * of an open ticket that must not be closed by accident here.
     */
    static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final WriteRateLimiter writeRateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PrincipalThrottleInterceptor(writeRateLimiter, WRITE_METHODS))
                .addPathPatterns(WRITE_PATH);
    }
}
