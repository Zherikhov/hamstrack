package com.hamstrack.common.persistence;

import com.hamstrack.common.ratelimit.RateLimitService;
import com.hamstrack.common.ratelimit.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>A refusal may only prescribe an action its reader can perform</strong> (HD-233, and the
 * rule CLAUDE.md states in those words).
 *
 * <p>{@code 503 DATABASE_BUSY} carries {@code Retry-After: 1}. {@code AuthRateLimitFilter} consumes
 * a token from {@code app.rate-limit.auth-ip-requests-per-minute} <em>before</em>
 * {@code chain.doFilter}, so before this fix a 503 cost the caller exactly what a real login attempt
 * cost — and a client obeying the header spent one token per second until the budget was gone and
 * the door answered 429 for the rest of the minute. On {@code /api/auth/login}, during an incident,
 * which is when a locked-out person most needs it. The fix is a refund, not a longer
 * {@code Retry-After}: a header that varies by surface is a second thing to keep true.
 *
 * <p>The budget here is {@value #BUDGET} and each test sends {@value #ATTEMPTS} requests from one
 * IP, so <strong>without the refund the last one is a 429</strong> — that is the shape of the
 * failure, and it is why the count is one past the budget rather than comfortably inside it.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + PoolStarvedBase.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=" + PoolStarvedBase.ACQUISITION_MS,
        "spring.datasource.hikari.validation-timeout=" + PoolStarvedBase.ACQUISITION_MS,
        "app.locking.lock-timeout-ms=" + PoolStarvedBase.ACQUISITION_MS,
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=" + DatabaseBusyAuthBudgetTest.BUDGET,
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DatabaseBusyAuthBudgetTest extends PoolStarvedBase {

    static final String BUDGET = "2";
    private static final int ATTEMPTS = 3;

    @Autowired private RateLimitService rateLimitService;

    /**
     * The advice's half: no token, so the acquisition fails inside the controller's transaction and
     * {@code GlobalExceptionHandler} answers.
     */
    @Test
    @Timeout(180)
    void a503AnsweredByTheHandlerDoesNotSpendTheCallersAuthBudget() throws Exception {
        assertEveryAttemptIsRefusedByThePoolAndNotByTheBudget("10.233.0.1", null);
    }

    /**
     * The filter's half, on the same endpoint: a request that carries a token — an already signed-in
     * tab re-authenticating is exactly this — has that token resolved to a user inside the security
     * filter chain, so the acquisition fails before the dispatcher and {@code DatabaseBusyFilter}
     * answers. The refund has to cover both, which is why it reads the response status rather than
     * catching an exception, and why that filter is registered one notch <em>inside</em> the rate
     * limiter: an exception caught outside it would unwind past the line that gives the token back.
     */
    @Test
    @Timeout(180)
    void a503AnsweredByTheFilterDoesNotSpendTheCallersAuthBudgetEither() throws Exception {
        var ctx = newProject();
        assertEveryAttemptIsRefusedByThePoolAndNotByTheBudget("10.233.0.2", ctx.token());
    }

    /**
     * <strong>And the budget still bites when the instance is healthy</strong>, which is the half a
     * refund could quietly destroy. Same endpoint, same IP, no starvation: the {@value #BUDGET}
     * attempts are answered 401 and the next is a 429. A refund keyed on a status the instance
     * issues about itself must not become a way to spend nothing.
     */
    @Test
    @Timeout(120)
    void theBudgetStillRefusesARealSequenceOfAttempts() throws Exception {
        var ip = "10.233.0.3";
        for (int i = 0; i < Integer.parseInt(BUDGET); i++) {
            assertThat(login(ip, null).getStatus())
                    .as("a wrong password is an attempt, and an attempt spends budget")
                    .isEqualTo(401);
        }

        assertThat(login(ip, null).getStatus())
                .as("""
                    THE PER-IP BUDGET MUST STILL BE ENFORCED.

                    The refund exists for a status the INSTANCE produced about its own state. If it \
                    ever widens to a refusal the CALLER earned, the brute-force bound on the most \
                    attacked endpoint in the product is gone and nothing else replaces it.""")
                .isEqualTo(429);
    }

    /**
     * <strong>The refund is capped at one budget per window, because an uncapped one is the auth
     * budget switching itself off in the condition a flood can create and then sustain.</strong>
     *
     * <p>The premise the first version rested on — "a 503 refunds a token that bought nothing" — is
     * false. The token bought a <em>park</em>: the request reaches a repository call, enters
     * {@code HikariPool.getConnection()} and holds a Tomcat worker for the whole acquisition bound —
     * and for up to twice it, since a borrow that ends on a dead-connection probe overruns the hard
     * timeout by as much as {@code validation-timeout}, which this product holds equal to the bound.
     * That is ~3 to ~6 thread-seconds at the shipped 3000 ms, more worker time than a successful
     * login. So with an unbounded refund, one IP that keeps the pool starved has NO per-IP bound at all
     * across the six auth endpoints, and {@code errorType: DATABASE_BUSY} is a precise
     * unauthenticated oracle for "saturated right now, and your budget is currently off".
     *
     * <p>Driven through the service rather than over HTTP on purpose: this is arithmetic about a
     * fixed window, and {@value #BUDGET}-plus-refunds worth of starved HTTP requests would take
     * seconds and could straddle a minute boundary. The loop below re-runs on a fresh key if the
     * window rolls under it, so nothing here is decided by the clock.
     */
    @Test
    void theRefundIsCappedAtOneBudgetPerWindowSoAFloodStillMeetsACeiling() {
        int budget = Integer.parseInt(BUDGET);

        withinOneWindow("10.233.1.", ip -> {
            // Twice the budget, every one of them refunded on the way out. Only the first BUDGET
            // refunds are honoured, so this leaves the window with BUDGET tokens spent.
            for (int i = 0; i < 2 * budget; i++) {
                rateLimitService.refundAuthRequest(ip, rateLimitService.checkAuthRequestAllowed(ip));
            }
            assertThatThrownBy(() -> rateLimitService.checkAuthRequestAllowed(ip))
                    .as("""
                        THE REFUND MUST NOT BE UNBOUNDED.

                        With no cap this call succeeds and every call after it does too: an IP \
                        that keeps the pool starved is refunded every token it spends, so the \
                        per-IP bound on the six auth endpoints is gone for as long as the flood \
                        lasts - and the flood is what sustains the starvation, because threads \
                        that never reach the front of the queue cannot drain the pool. A \
                        compliant client obeying Retry-After: 1 still gets its whole budget back \
                        each minute, which is the case the refund exists for.""")
                    .isInstanceOf(RateLimitedException.class);
        });
    }

    /**
     * <strong>A refund lands in the window the token was charged to, or nowhere.</strong> A request
     * that enters at {@code M:59.x}, parks out the acquisition bound and answers 503 in
     * {@code M+1} would otherwise decrement a window it never paid into — driving that one toward
     * its zero floor and handing out tokens nobody spent. The stale minute below stands in for
     * exactly that: it is the value {@code checkAuthRequestAllowed} would have returned one minute
     * earlier.
     */
    @Test
    void aRefundIntoAWindowTheTokenWasNeverChargedToIsIgnored() {
        int budget = Integer.parseInt(BUDGET);

        withinOneWindow("10.233.2.", ip -> {
            long charged = rateLimitService.checkAuthRequestAllowed(ip);
            rateLimitService.refundAuthRequest(ip, charged - 1);

            for (int i = 0; i < budget - 1; i++) {
                assertThatCode(() -> rateLimitService.checkAuthRequestAllowed(ip))
                        .as("the rest of the budget is still there — the stale refund neither gave "
                            + "anything back nor took anything away")
                        .doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> rateLimitService.checkAuthRequestAllowed(ip))
                    .as("""
                        A REFUND MAY ONLY BE APPLIED TO THE WINDOW THAT WAS CHARGED.

                        If this call is allowed, the refund credited whichever window happens to \
                        be current rather than the one the token was spent in - so a request that \
                        spans a minute boundary gives a token back to a window it never took one \
                        from, and the budget quietly widens by one per straddling request.""")
                    .isInstanceOf(RateLimitedException.class);
        });
    }

    /**
     * Runs {@code body} against a key nothing else has touched, retrying on a fresh key if the
     * epoch minute rolled while it ran. The window is wall-clock and the body is microseconds of
     * arithmetic, so this is a formality — but a formality is cheaper than a test that fails once a
     * month at the top of a minute and is then believed to be flaky rather than right.
     */
    private void withinOneWindow(String ipPrefix, java.util.function.Consumer<String> body) {
        for (int attempt = 1; ; attempt++) {
            long before = java.time.Instant.now().getEpochSecond() / 60;
            var ip = ipPrefix + attempt;
            try {
                body.accept(ip);
            } catch (AssertionError e) {
                if (java.time.Instant.now().getEpochSecond() / 60 != before && attempt < 5) {
                    continue;
                }
                throw e;
            }
            return;
        }
    }

    private void assertEveryAttemptIsRefusedByThePoolAndNotByTheBudget(String ip, String token)
            throws Exception {
        var statuses = whileTheWholePoolIsHeld(() -> {
            var seen = new ArrayList<Integer>();
            for (int i = 0; i < ATTEMPTS; i++) {
                seen.add(login(ip, token).getStatus());
            }
            return List.copyOf(seen);
        });

        assertThat(statuses)
                .as("""
                    A 503 MUST NOT SPEND THE CALLER'S AUTH BUDGET.

                    The budget is %s per minute and this sent %d requests from one IP. A 429 in \
                    here means the token was charged for a refusal the instance issued about \
                    itself - so a client obeying the Retry-After: 1 that same refusal prescribes \
                    is locked out of the login door in seconds, in the middle of the incident that \
                    caused it. The refund lives in AuthRateLimitFilter and reads the response \
                    status after the chain.""", BUDGET, ATTEMPTS)
                .containsOnly(503);
    }

    private MockHttpServletResponse login(String ip, String token) throws Exception {
        var request = post("/api/auth/login")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever\"}");
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
