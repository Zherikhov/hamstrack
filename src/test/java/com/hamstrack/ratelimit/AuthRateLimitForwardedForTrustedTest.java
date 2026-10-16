package com.hamstrack.ratelimit;

import com.hamstrack.common.testsupport.FixedWindow;
import com.hamstrack.common.testsupport.Unique;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-75 companion: behind a trusted proxy ({@code trust-forwarded-for=true})
 * keying uses the rightmost X-Forwarded-For entry (the real peer, since Caddy
 * strips client-supplied XFF). A fixed rightmost value trips the per-IP limit
 * even as the socket varies, and a client-supplied left entry is ignored.
 *
 * <p><strong>HD-199: this class had ONE test, and it could not fail under the wrong
 * keying.</strong> Every rate-limit test in this suite asserted that requests
 * <em>share</em> a budget — true under per-address keying and equally true under one
 * global bucket, which is why a 2026-07-14 production burst that tripped at the 16th
 * request was read as proof of per-IP keying and proved nothing. A test that passes under
 * both configurations distinguishes neither. The property that separates them is about
 * <em>two</em> clients: two distinct addresses must receive two budgets. That is the
 * second test below, added here; the first is the pre-existing one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=5",
        "app.rate-limit.login-failure-threshold=100",
        "app.rate-limit.trust-forwarded-for=true",
        "seed.admin.email=",
        "app.demo.seed-on-first-login=false"
})
@AutoConfigureMockMvc
class AuthRateLimitForwardedForTrustedTest {

    @Autowired MockMvc mockMvc;

    @Test
    void fixedRightmostXffTripsLimitEvenAsSocketVaries() throws Exception {
        // RateLimitService's per-IP window is a fixed epoch minute, and the key here is the
        // rightmost XFF entry — so the retry varies that, in a /24 of its own. Each member of this
        // class owns a /24, because RateLimitService.ipWindows is an instance field of a bean and
        // therefore per-CONTEXT state that no test resets: an address one member has spent is spent
        // for every member sharing the context, whatever order JUnit runs them in.
        FixedWindow.inOneMinute(attempt -> {
            // Trusted proxy: the rightmost XFF entry is the real peer. Keep it fixed
            // while the socket varies — all share one bucket, 6th trips. The
            // client-supplied left entry must be ignored (rightmost wins).
            var realPeer = "198.51.101." + attempt.suffix();
            for (int i = 0; i < 5; i++) {
                loginTrusted("10.0.0." + i, "1.2.3.4, " + realPeer)
                        .andExpect(status().isUnauthorized());
            }
            loginTrusted("10.0.0.250", "5.6.7.8, " + realPeer)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        });
    }

    /**
     * The discriminating one. Exhaust the budget for one rightmost address, then send a
     * single request whose rightmost address is a different one — from the SAME socket, so
     * the only thing that can distinguish the two requests is the header the filter is
     * supposed to key on. It must answer 401 (bad credentials, budget intact), never 429.
     *
     * <p><strong>The mutation that discriminates is ONE SHARED KEY.</strong> Make
     * {@code AuthRateLimitFilter.clientIp} return the same constant for every request and
     * this test goes red — at whichever assertion first finds the shared bucket already
     * empty, which is decided by the order JUnit happens to run the two methods in and is
     * not always the last line. (Today the sibling runs first, drains the one bucket for
     * everybody and stays green, so this test reds at the FIRST request of its warm-up;
     * run this one first instead and it reds on the last line while the sibling reds too.)
     * Either way the property being destroyed is the last line's — two rightmost addresses,
     * two budgets — and that is the whole point of this test existing, and the experiment
     * to run before concluding it is redundant.
     *
     * <p>Do <em>not</em> use {@code getRemoteAddr()} for that experiment, which an earlier
     * version of this javadoc prescribed: under it <em>both</em> tests fail, and two reds
     * read as "the new one adds nothing". The sibling varies the socket across its burst on
     * purpose, so per-socket keying hands each of those requests its own budget and the
     * sixth answers 401 instead of 429. Production behind Caddy never varies the socket —
     * that freedom is MockMvc's, not a client's — so per-socket keying is not the failure
     * being modelled here. One key for everybody is.
     *
     * <p>The addresses are not the {@code .4}/{@code .9} of the spec, and the property is
     * worth knowing before you "restore" them: every member of this class keys its burst in a
     * /24 of its own, because {@code RateLimitService.ipWindows} is an instance field of a bean
     * and so per-context state that no test resets. An address another member has already spent
     * would make this test pass or fail on JUnit's method order — and a sentence naming which
     * addresses those are goes stale the next time one of them is re-keyed, which is exactly what
     * happened to the two sentences this one replaces (HD-334).
     */
    @Test
    void distinctRightmostXffGetIndependentBudgets() throws Exception {
        FixedWindow.inOneMinute(attempt -> {
            // Two addresses per attempt, in two /24s of their own — see the sibling above for why
            // the retry may not reuse an address this context has already spent.
            var exhausted = "198.51.102." + attempt.suffix();
            var fresh = "198.51.103." + attempt.suffix();
            var socket = "10.0.0.7";

            for (int i = 0; i < 5; i++) {
                loginTrusted(socket, "1.2.3.4, " + exhausted).andExpect(status().isUnauthorized());
            }
            loginTrusted(socket, "1.2.3.4, " + exhausted)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            // Same socket, same left entry, different real peer: a budget of its own.
            loginTrusted(socket, "1.2.3.4, " + fresh).andExpect(status().isUnauthorized());
        });
    }

    private ResultActions loginTrusted(String socket, String xff) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(r -> { r.setRemoteAddr(socket); return r; })
                .header("X-Forwarded-For", xff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + Unique.email("nobody") + "\",\"password\":\"x-wrong-pass\"}"));
    }
}
