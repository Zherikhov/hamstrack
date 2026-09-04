package com.hamstrack.common.ratelimit;

import com.hamstrack.auth.entity.User;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
 *
 * <p><strong>Since HD-182 it spends TWO controls, and the type no longer answers which.</strong>
 * An optional {@link PerPrincipalInFlightLimit} bounds how many requests one principal — and every
 * principal together — may have <em>in flight</em> on this surface. The write budget's
 * registration uses this same type and deliberately carries no occupancy bound, so
 * {@code ThrottleCoverageTest} asks {@link #concurrencyBound()} beside {@link #budget()} rather
 * than asking whether an interceptor of this type is present.
 *
 * <h2>Which dispatch acquires, which releases, and why</h2>
 *
 * <p>This is the seam that turns an occupancy bound into a capacity leak, and this repository
 * already carries the scar: an SSE emitter timeout triggers an ASYNC dispatch that re-enters the
 * security filter chain, which is why {@code SecurityConfig} permits {@link DispatcherType#ASYNC}.
 * The MVC half of the same trap is that {@code afterCompletion} is <strong>not</strong> called when
 * a handler starts async processing — {@link #afterConcurrentHandlingStarted} is — and then the
 * whole interceptor chain runs AGAIN on the ASYNC dispatch. An interceptor that naively acquires in
 * {@code preHandle} and releases in {@code afterCompletion} would therefore acquire twice and
 * release once.
 *
 * <ul>
 *   <li><strong>{@link DispatcherType#REQUEST} acquires</strong>, and nothing else does. On an
 *       ASYNC or ERROR dispatch the request has already paid, and a second acquisition could refuse
 *       — with a 429 — a request that is already half-answered.</li>
 *   <li><strong>Both terminal callbacks release</strong>, whichever runs first, and the release is
 *       idempotent: {@code afterCompletion} for every ordinary outcome (normal completion, handler
 *       exception, any {@code @ExceptionHandler} response, and a refusal thrown by a LATER
 *       interceptor — {@code HandlerExecutionChain.triggerAfterCompletion} runs for every
 *       interceptor whose {@code preHandle} returned true), and
 *       {@link #afterConcurrentHandlingStarted} if a handler ever does start async processing.</li>
 *   <li><strong>A permit therefore never spans the async gap.</strong> Holding one across it would
 *       be more accurate — the request really is still in flight — and it would make the bound
 *       depend on a dispatch back that a broken or abandoned async request may never make, i.e. it
 *       would trade a bounded under-count for an unbounded leak. There is no async handler on this
 *       surface today and {@code ThrottleCoverageTest} has a category tripwire to keep it that way;
 *       the WARN below fires if one ever appears, and whoever adds it must decide what a permit
 *       means across the gap rather than inherit this choice by silence.</li>
 * </ul>
 */
@Slf4j
public class PrincipalThrottleInterceptor implements AsyncHandlerInterceptor {

    private final PerPrincipalMinuteBudget budget;

    /**
     * The occupancy bound, or {@code null} for a registration that has none (the write budget).
     * Never a second interceptor type: {@code ThrottleCoverageTest} asks its questions of one type,
     * and a second would make every one of them two questions.
     */
    private final PerPrincipalInFlightLimit concurrency;

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

    /** Every method, and no occupancy bound. */
    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget) {
        this(budget, Set.of(), null);
    }

    /** A method-conditioned registration with no occupancy bound — the write budget. */
    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget, Set<String> methods) {
        this(budget, methods, null);
    }

    /** Every method, plus the occupancy bound — the reports and search registrations. */
    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget,
                                        PerPrincipalInFlightLimit concurrency) {
        this(budget, Set.of(), concurrency);
    }

    public PrincipalThrottleInterceptor(PerPrincipalMinuteBudget budget, Set<String> methods,
                                        PerPrincipalInFlightLimit concurrency) {
        this.budget = budget;
        this.methods = Set.copyOf(methods);
        this.concurrency = concurrency;
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

    /**
     * The occupancy bound this interceptor spends, or {@code null} if it has none.
     *
     * <p>Exposed for {@code ThrottleCoverageTest.everyExpensiveReadHandlerIsAlsoConcurrencyBounded}
     * (HD-182). Before this method existed, "a {@link PrincipalThrottleInterceptor} is in front of
     * this handler" was the whole coverage assertion — and it stopped meaning
     * "concurrency-bounded" the moment a second registration of the same type deliberately did not
     * take one. Without the refinement the coverage test would keep passing while covering half of
     * what its name claims, which is the failure that file exists to have deleted.
     */
    public PerPrincipalInFlightLimit concurrencyBound() {
        return concurrency;
    }

    /**
     * Spend the rate budget FIRST, take the permit LAST. A request the rate budget will refuse must
     * never have held a permit, and the budget's throw must happen before anything is held.
     *
     * <p><strong>The permit is taken here, which is BEFORE the request body is read.</strong> An
     * interceptor runs ahead of argument resolution, so {@code @RequestBody} deserialisation — and
     * the socket reads under it — happen inside the permit's life, as does the response write
     * afterwards. Both are paced by the client. That is not fixable here: moving the acquisition
     * after the body would mean an unbudgeted read of an arbitrary body, which is a worse trade.
     * It is bounded instead by {@code TomcatUploadTimeoutCustomizer}, by the edge's
     * {@code read_body} timeout, and underneath both by
     * {@code PerPrincipalInFlightLimit.sweepStalePermits}. Anybody moving this line owes those
     * three a re-read.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!appliesTo(request.getMethod())) {
            return true;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            budget.require(user.getId());
            acquire(request, user.getId());
        }
        return true;
    }

    /** The ordinary end of every dispatch: normal, exceptional, refused by anybody. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        release(request);
    }

    /**
     * A handler started async processing, so {@code afterCompletion} will not run on this pass.
     *
     * <p>The permit is handed back here rather than carried across the gap — see the class javadoc
     * — and the WARN is deliberate rather than defensive noise: reaching this method means a
     * handler on a concurrency-bounded surface returned {@code StreamingResponseBody},
     * {@code DeferredResult}, {@code Callable} or an {@code SseEmitter}, which
     * {@code ThrottleCoverageTest}'s category tripwire forbids. The bound is then correct and
     * <em>weaker</em> than it reads: the async part of that request occupies nothing.
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                               HttpServletResponse response, Object handler) {
        if (concurrency != null && request.getAttribute(concurrency.requestAttribute()) != null) {
            log.warn("An async handler was reached on a concurrency-bounded surface ({}): the "
                     + "expensive-read permit is released at the async gap, so the asynchronous "
                     + "part of this request occupies nothing and the bound is weaker than it "
                     + "reads. ThrottleCoverageTest's async tripwire forbids this shape — whoever "
                     + "added the handler owes a decision about what a permit means across the "
                     + "gap.", handler);
        }
        release(request);
    }

    /**
     * <strong>One permit per REQUEST, never one per interceptor.</strong> {@code POST
     * …/search/insights} is behind both configurers and occupies one connection, so it takes one
     * permit: the first interceptor to acquire parks it on the request, the second sees it and
     * skips, and the one that parked it is the one that releases it.
     */
    private void acquire(HttpServletRequest request, UUID userId) {
        if (concurrency == null || request.getDispatcherType() != DispatcherType.REQUEST) {
            return;
        }
        var attribute = concurrency.requestAttribute();
        if (request.getAttribute(attribute) != null) {
            return;
        }
        var permit = concurrency.acquire(userId);
        if (permit != null) {
            request.setAttribute(attribute, new Held(this, permit));
        }
    }

    private void release(HttpServletRequest request) {
        if (concurrency == null) {
            return;
        }
        var attribute = concurrency.requestAttribute();
        if (request.getAttribute(attribute) instanceof Held held && held.owner() == this) {
            request.removeAttribute(attribute);
            concurrency.release(held.permit());
        }
    }

    /**
     * The parked permit and who parked it. The owner is what lets the OTHER interceptor in front of
     * the same handler run its own {@code afterCompletion} — which it does, first, because
     * completion runs in reverse chain order — without releasing a permit it never took.
     */
    private record Held(PrincipalThrottleInterceptor owner, PerPrincipalInFlightLimit.Permit permit) {}
}
