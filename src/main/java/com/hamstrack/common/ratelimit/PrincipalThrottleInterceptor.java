package com.hamstrack.common.ratelimit;

import com.hamstrack.auth.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spends one unit of the caller's budget before the controller runs — the MVC half of every
 * per-principal throttle in the app.
 *
 * <p>Reads the principal from the {@code SecurityContext} rather than taking it as a handler
 * argument, because an interceptor has no argument resolvers — and an anonymous request never
 * reaches here anyway ({@code /api/**} is {@code authenticated()}). It runs after the security
 * filter chain, so the principal is present; an exception thrown from {@code preHandle} is
 * dispatched through the normal {@code HandlerExceptionResolver} chain, which is what turns
 * {@link RateLimitedException} into the shared 429 + {@code Retry-After} body.
 *
 * <p><strong>One class, so "is this handler throttled?" is a type question.</strong> Both budgets
 * used to have a private copy of this method; sharing it means {@code ThrottleCoverageTest}
 * can ask the real handler mapping whether a given endpoint's interceptor chain contains one of
 * these, instead of matching path strings against a config file by hand (HD-140 R6 round 2,
 * security item 15). A new expensive surface is then throttled-or-not as a structural fact a test
 * can read, not as a line somebody remembered.
 */
@RequiredArgsConstructor
public class PrincipalThrottleInterceptor implements HandlerInterceptor {

    private final PerPrincipalMinuteBudget budget;

    /**
     * Which budget this interceptor spends. Exposed for {@code ThrottleCoverageTest}, which asserts
     * not only that a throttle is in front of a handler but — on the one path covered by two — that
     * they are in the documented order (reports before search, so a report-refused request never
     * spends search budget).
     */
    public PerPrincipalMinuteBudget budget() {
        return budget;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            budget.require(user.getId());
        }
        return true;
    }
}
