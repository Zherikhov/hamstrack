package com.hamstrack.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>The policy STRING, on every kind of response, at the shipped defaults</strong> (HD-264).
 *
 * <p>Asserting that a header exists would pass on a policy with a typo in a directive name, and a
 * browser ignores a directive it does not recognise <em>silently</em> — so a misspelt
 * {@code img-src} is a policy that measures nothing while every test about its presence stays
 * green. That is this ticket's failure mode one level down, and it is why the expected value below
 * is written out in full here rather than read from the class under test. The two copies are the
 * point: changing the policy means changing both, deliberately.
 *
 * <p>It also pins the <strong>header name</strong>. {@code Content-Security-Policy} is one word
 * shorter than the name this ticket ships, enforces rather than reports, and is the single way this
 * change could break the product — so its absence is asserted, not assumed.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ContentSecurityPolicyHeaderTest {

    /**
     * The policy as it goes on the wire with the sink off — which is the base default, and what
     * every self-hosted install gets. Each directive's evidence is in
     * {@link ContentSecurityPolicy}'s javadoc; what is asserted here is that the string has not
     * moved.
     */
    private static final String EXPECTED_WITHOUT_SINK =
            "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
            + "form-action 'self'; script-src 'self'; "
            + "style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
            + "frame-src 'none'; worker-src 'none'";

    private static final String REPORT_ONLY = "Content-Security-Policy-Report-Only";
    private static final String ENFORCING = "Content-Security-Policy";

    @Autowired MockMvc mockMvc;

    /**
     * Four responses of four different kinds, because the header is written by the chain rather
     * than by a handler and the interesting failures are the responses <em>no handler produced</em>:
     * the SPA document (Spring's resource handler), an unauthenticated 200, a 401 decided by the
     * security chain, and a 404 that goes through an ERROR dispatch.
     */
    @Test
    void everyKindOfResponseCarriesTheExactPolicy() throws Exception {
        assertPolicyOn("the SPA document — served by Spring's resource handler, not by Caddy, "
                       + "which is the whole reason the policy can live in the application",
                mockMvc.perform(get("/")).andReturn().getResponse().getHeader(REPORT_ONLY));
        assertPolicyOn("an unauthenticated 200",
                mockMvc.perform(get("/api/meta")).andReturn().getResponse().getHeader(REPORT_ONLY));
        assertPolicyOn("a 401 decided by the security chain before any handler",
                mockMvc.perform(get("/api/workspaces")).andReturn().getResponse()
                        .getHeader(REPORT_ONLY));
        // A dotted path is excluded from the SPA fallback, so it reaches the resource handler,
        // finds nothing, and comes back through an ERROR dispatch — the one response shape written
        // by the container rather than by anything in this application.
        assertPolicyOn("a 404, which reaches the client through an ERROR dispatch",
                mockMvc.perform(get("/no-such-file.txt")).andReturn().getResponse()
                        .getHeader(REPORT_ONLY));
    }

    /**
     * <strong>Report-only, and nothing else.</strong> This ticket ships no enforcing header in any
     * mode, behind any flag: the acceptance criterion is that the enforcement decision cites
     * evidence, and evidence needs a window nobody can sit through in one release. If this fails,
     * the change has quietly become the enforcement ticket.
     */
    @Test
    void nothingEnforces() throws Exception {
        var response = mockMvc.perform(get("/")).andReturn().getResponse();

        assertThat(response.getHeader(ENFORCING))
                .as("""
                    NO ENFORCING Content-Security-Policy MAY SHIP FROM THIS TICKET.

                    Report-only is the whole design: the directive set deliberately omits every \
                    escape hatch so that the reports say whether one is needed, which is safe \
                    ONLY because nothing is blocked meanwhile. An enforcing header with this \
                    policy would break style, fonts and the docs page on the first page load.""")
                .isNull();
        assertThat(response.getHeader(REPORT_ONLY)).isNotNull();
    }

    /**
     * <strong>The two halves of "a {@code report-uri} that 404s", asserted TOGETHER</strong> —
     * because either one alone is exactly the failure. A header naming an endpoint the instance
     * does not serve turns every violation into a request {@code /api/**} answers 401 on: an
     * unauthenticated request storm generated by our own header. One property decides both, which
     * is what makes the pair impossible to break apart, and this is the test that says so.
     */
    @Test
    void withTheSinkOffTheHeaderNamesNoReportUriAndThePathIsNotServed() throws Exception {
        var policy = mockMvc.perform(get("/")).andReturn().getResponse().getHeader(REPORT_ONLY);
        assertThat(policy).doesNotContain("report-uri");

        var response = mockMvc.perform(post(ContentSecurityPolicy.REPORT_PATH)
                .contentType("application/csp-report")
                .content("{\"csp-report\":{}}")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                    WITH THE SINK OFF THE PATH MUST NOT BE SERVED.

                    Not 401 either: the path is on the permitAll list unconditionally, so a DC box \
                    answers "there is no such thing here" rather than "there is something here you \
                    may not have". 405 rather than the 404 the proposal predicted, because the SPA \
                    fallback maps every dotless path for GET, so the dispatcher finds the path and \
                    refuses the method - which is the same answer to the only question that \
                    matters. If this is 204 the handler is registered when the header says it is \
                    not, and a self-hoster has an unauthenticated public endpoint nobody told them \
                    about.""")
                .isEqualTo(405);
    }

    private static void assertPolicyOn(String what, String actual) {
        assertThat(actual)
                .as("""
                    THE POLICY STRING ON %s HAS MOVED.

                    Asserted as the whole string, because a browser ignores a directive whose \
                    NAME it does not recognise - silently, per directive - so a typo produces a \
                    policy that measures nothing and a header that looks perfect. Each directive's \
                    evidence is derived from the BUILT artefact and written on \
                    ContentSecurityPolicy; if you are changing one, change it there, re-derive the \
                    evidence against the release artefact, and update this literal in the same \
                    edit.""".formatted(what))
                .isEqualTo(EXPECTED_WITHOUT_SINK);
    }
}
