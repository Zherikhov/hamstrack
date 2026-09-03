package com.hamstrack.common.ratelimit;

import com.hamstrack.auth.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

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
public class PrincipalThrottleInterceptor implements HandlerInterceptor {

    private final PerPrincipalMinuteBudget budget;

    /**
     * The HTTP methods this interceptor spends on. <strong>Empty means every method</strong>,
     * which is what the reports and search registrations pass — their surfaces are read surfaces
     * and every verb on them is expensive.
     *
     * <p>Exists for the write budget (HD-191 §6.1), which is bound to
     * {@code /api/workspaces/*}{@code /projects/*}{@code /issues/**} — a path whose {@code GET}s
     * are ordinary reads that must stay unbudgeted while every {@code POST}/{@code PUT}/
     * {@code PATCH}/{@code DELETE} on it costs a unit. The condition lives here rather than in a
     * second interceptor type because {@code ThrottleCoverageTest} asks "is this handler
     * throttled?" as a <em>type</em> question against the real handler chain, and a second type
     * would make that question two questions.
     *
     * <p>Deliberately NOT expressed as {@code InterceptorRegistration}'s own method condition:
     * that would produce a {@code MappedInterceptor} whose include patterns the seal reads, but
     * the method set would be invisible to {@code WriteThrottleCoverageTest}, which has to ask
     * each mapped handler whether the interceptor in front of it applies to <em>its</em> verb.
     */
    private final Set<String> methods;

    /** Every method — the reports and search registrations. */
    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget) {
        this(budget, Set.of());
    }

    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget, Set<String> methods) {
        this.budget = budget;
        this.methods = Set.copyOf(methods);
    }

    /**
     * Whether this interceptor would spend anything on a request using {@code method}. Read by
     * {@code WriteThrottleCoverageTest}: a throttle in the chain that does not apply to the verb
     * the handler is mapped on is not coverage, and a path-shaped assertion cannot tell the
     * difference.
     */
    public boolean appliesTo(String method) {
        return methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ROOT));
    }

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
        if (!appliesTo(request.getMethod())) {
            return true;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            budget.require(user.getId());
        }
        return true;
    }
}
