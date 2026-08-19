package com.hamstrack.report.ratelimit;

import com.hamstrack.auth.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ReportRateLimiter} across the <strong>whole reports base path</strong>
 * (HD-28 R1 round 2, item 1).
 *
 * <p><strong>An interceptor on a path, not a check inside a service.</strong> Five more
 * report slices are specified to land on this base path, plus the {@code .csv} variants of
 * R7. A per-service guard would be a line each of them has to remember; the one that forgets
 * is unbounded, and nothing fails until it is being abused. A path binding is inherited by
 * construction: a new report is throttled the moment its {@code @GetMapping} exists.
 *
 * <p>Not a servlet {@code Filter} (the {@code AuthRateLimitFilter} shape) because servlet URL
 * patterns cannot express {@code /api/workspaces/*}{@code /projects/*}{@code /reports/**} —
 * they allow a prefix or an extension and nothing else, so a filter would have to be mapped
 * to {@code /api/*} and re-implement the path test by hand. A {@code PathPattern} says it
 * exactly. The interceptor runs after the security filter chain, so the principal is present;
 * an exception thrown from {@code preHandle} is dispatched through the normal
 * {@code HandlerExceptionResolver} chain, which is what turns {@link
 * com.hamstrack.common.ratelimit.RateLimitedException} into the shared 429 +
 * {@code Retry-After} body.
 *
 * <p>Adding a {@link WebMvcConfigurer} is additive — it does not switch off Boot's MVC
 * auto-configuration the way {@code @EnableWebMvc} would — so static-resource handling and
 * the SPA fallback are unaffected.
 */
@Configuration
@RequiredArgsConstructor
public class ReportRateLimitConfig implements WebMvcConfigurer {

    /**
     * Every report of every project of every workspace. Deliberately the base path rather
     * than {@code …/reports/flow}: the budget belongs to the surface, not to R1's one
     * endpoint.
     */
    static final String REPORTS_PATH = "/api/workspaces/*/projects/*/reports/**";

    private final ReportRateLimiter reportRateLimiter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ReportRateLimitInterceptor(reportRateLimiter))
                .addPathPatterns(REPORTS_PATH);
    }

    /**
     * Spends one unit of the caller's report budget before the controller runs. Reads the
     * principal from the {@code SecurityContext} rather than taking it as a handler argument,
     * because an interceptor has no argument resolvers — and an anonymous request never
     * reaches here anyway ({@code /api/**} is {@code authenticated()}).
     */
    @RequiredArgsConstructor
    static class ReportRateLimitInterceptor implements HandlerInterceptor {

        private final ReportRateLimiter reportRateLimiter;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof User user) {
                reportRateLimiter.require(user.getId());
            }
            return true;
        }
    }
}
