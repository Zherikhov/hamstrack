package com.hamstrack.common.ratelimit;

import com.hamstrack.common.security.ContentSecurityPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>A key the budget is spent on may not be a way to avoid spending it</strong> (HD-264).
 *
 * <p>{@link ClientIp} runs <em>before</em> the token is charged, on both doors that charge one, and
 * it runs on a value the sender writes. So its contract is not only "which address" — it is that it
 * <strong>always returns one</strong>. Anything it can be made to throw on is an unmetered door: the
 * request never reaches {@code spend}, costs nothing, repeats without limit, and leaves a container
 * stack trace plus an {@code /error} dispatch per attempt — a log-fill vector on the one endpoint
 * whose entire design is a log-volume budget, arriving in front of it.
 *
 * <p>The property under test is therefore about a <em>shape</em> of header rather than one spelling:
 * <strong>a header whose rightmost entries are empty must fall back, not throw.</strong>
 * {@code String.split} drops trailing empty strings, so {@code ","} and {@code ",,"} both yield a
 * zero-length array while {@code isBlank()} says the header has content — and reading
 * {@code parts[parts.length - 1]} on that is an {@code ArrayIndexOutOfBoundsException}. Note that
 * asserting on a returned <em>string</em> alone would have passed against the defect for the wrong
 * reason: the old code did not return a wrong address, it failed to return at all.
 *
 * <p>Both doors are sealed because the class exists to be the one copy of this decision: it was
 * extracted from {@code AuthRateLimitFilter} when the CSP sink became the second per-IP budget, so
 * a fix here has to be observable at the door that was there first and at the unauthenticated one
 * that inherited it. {@code app.rate-limit.trust-forwarded-for=true} is the bundled
 * {@code docker-compose.prod.yml} default and what {@code docs/self-hosting.md} tells an operator
 * behind a trusted proxy to set, so it is not an exotic configuration.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.trust-forwarded-for=true",
        "app.rate-limit.auth-ip-requests-per-minute=100",
        "app.rate-limit.login-failure-threshold=1000",
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=10000",
        "app.csp.reports-per-minute=10000",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ClientIpTest {

    /**
     * Every header shape whose rightmost entries carry nothing. The first two are the ones that
     * yield an <em>empty</em> array; the rest still have a usable entry further left, and the answer
     * there is that entry rather than the socket — the rightmost address the proxy chain actually
     * wrote is still the peer.
     */
    private static final String[] RIGHTMOST_ENTRIES_EMPTY = {",", ",,", " , ", "1.2.3.4,", "1.2.3.4, ,"};

    @Autowired MockMvc mockMvc;

    @Test
    void aHeaderWhoseRightmostEntriesAreEmptyFallsBackInsteadOfThrowing() {
        for (var header : RIGHTMOST_ENTRIES_EMPTY) {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100.7");
            request.addHeader("X-Forwarded-For", header);

            assertThat(ClientIp.of(request, true))
                    .as("""
                        A KEY THAT CAN THROW IS A BUDGET THAT CAN BE SKIPPED: X-Forwarded-For: %s.

                        This runs two lines before budget.spend on both doors, on a value the \
                        sender writes. String.split drops trailing empty strings, so "," and ",," \
                        yield a ZERO-length array while isBlank() reports content - and \
                        parts[parts.length - 1] on that throws. The request then costs no token, \
                        repeats without limit, and pays a stack trace and an /error dispatch each \
                        time. Return an address, always.""".formatted(header))
                    .isNotBlank()
                    .isNotEqualTo(",");
        }
    }

    /** Nothing usable in the header at all: the socket peer, which is the only address left. */
    @Test
    void aHeaderWithNothingUsableInItKeysOnTheSocketPeer() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Forwarded-For", ",,");

        assertThat(ClientIp.of(request, true)).isEqualTo("198.51.100.7");
    }

    /** And an empty tail does not lose the address in front of it. */
    @Test
    void anEmptyTailDoesNotHideTheRightmostRealEntry() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.5, ,");

        assertThat(ClientIp.of(request, true))
                .as("the rightmost entry a proxy actually wrote is still the peer")
                .isEqualTo("203.0.113.5");
    }

    /**
     * The unauthenticated door. It answers {@code 204} to almost everything on purpose, so the
     * assertion that matters is that it answers <em>at all</em>: under the defect this request left
     * the guard filter as a thrown exception before the budget was spent.
     */
    @Test
    void theCspSinkAnswersRatherThanThrowingOnSuchAHeader() throws Exception {
        mockMvc.perform(post(ContentSecurityPolicy.REPORT_PATH)
                        .with(request -> { request.setRemoteAddr("198.51.100.31"); return request; })
                        .header("X-Forwarded-For", ",")
                        .contentType("application/csp-report")
                        .content("{\"csp-report\":{\"document-uri\":\"http://elsewhere.example/\"}}"))
                .andExpect(status().isNoContent());
    }

    /** The door the extraction came from, which the same header 500s for the same reason. */
    @Test
    void theAuthDoorAnswersRatherThanThrowingOnSuchAHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.32"); return request; })
                        .header("X-Forwarded-For", ",")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + System.nanoTime()
                                 + "@example.com\",\"password\":\"x-wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
    }
}
