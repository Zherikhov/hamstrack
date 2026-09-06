package com.hamstrack.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The security headers on the responses a browser actually receives</strong> (HD-266).
 *
 * <h2>Why a second class, over a real container</h2>
 * {@code ContentSecurityPolicyHeaderTest} asks MockMvc for {@code GET /} and asserts the policy on
 * the result. {@code SpaController} answers that path with {@code "forward:/index.html"}, and
 * <strong>MockMvc does not perform a forward</strong>: {@code MockRequestDispatcher} records
 * {@code forwardedUrl} and returns, so the response inspected is the one the filter chain wrapped,
 * still inside the chain. That assertion therefore proves the header is <em>set by the chain</em>
 * and cannot, even in principle, prove it <em>reaches a client</em>. Every page in this product is
 * served through that forward, so the entire user-facing half of the policy was covered only by a
 * harness structurally unable to express its failure. Nothing in this class may be rewritten onto
 * MockMvc, whatever it costs in start-up time.
 *
 * <h2>What the coverage actually rests on — measured, not assumed</h2>
 * The forward does <strong>not</strong> lose the block, and the reason is worth writing down
 * because two plausible readings of the filter chain say it should. {@code HeaderWriterFilter}
 * wraps the response in an {@code OnCommittedResponseWrapper} on the {@code REQUEST} dispatch and
 * writes the block when that response commits, or in a {@code finally} if it never did. Tomcat's
 * {@code ApplicationDispatcher} does not re-wrap the response for a forward — it hands the same
 * object to the forwarded resource — so the wrapper spans the dispatch and the bytes
 * {@code index.html} goes out with carry the block either way.
 *
 * <p>It therefore does <em>not</em> depend on {@code spring.security.filter.dispatcher-types}
 * including {@code FORWARD}, which Boot 4 does by default and Boot 3 did not: this class was run
 * against the narrowed Boot 3 set and stayed green. That was checked rather than reasoned about,
 * and the property is deliberately NOT pinned in {@code application.properties} — a line whose
 * stated justification does not survive the experiment is worse than no line.
 *
 * <h2>What it takes to make this class fail, and why that matters</h2>
 * Nothing that separates a forwarded document from a direct one: the same wrapper writes both, so
 * no configuration of this application produces "the policy is on {@code /index.html} and absent
 * from {@code /}". The only way to strip the SPA documents is to take the chain off those paths
 * entirely (a {@code securityMatcher} narrowed to {@code /api/**} and {@code /index.html} with
 * {@code FORWARD} removed) — verified: this class goes red on {@code /} with the exact production
 * symptom while {@code /api/**} and {@code /index.html} stay clean. <strong>If that table is ever
 * observed on a deployment and this class is green, the header is being removed downstream of the
 * JVM</strong> — the edge, not the application — and the discriminating measurement is a request
 * made against the app container directly rather than through the proxy.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.enabled=false",
                "app.demo.seed-on-first-login=false",
                "seed.admin.email="
        })
class SecurityHeadersReachTheBrowserTest {

    /** The shipped default, with the sink off — the same literal asserted by the MockMvc class. */
    private static final String EXPECTED_POLICY =
            "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
            + "form-action 'self'; script-src 'self'; "
            + "style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
            + "frame-src 'none'; worker-src 'none'";

    /**
     * The paths a browser opens, chosen so that <strong>both</strong> ways of being served are
     * present: {@code /index.html} is answered on the {@code REQUEST} dispatch and would keep the
     * block through any narrowing, while the other four are answered through the SPA forward and
     * are the ones that go dark. A list of only the forwarded paths would miss a fix that broke the
     * direct one; a list of only {@code /index.html} is the blind spot this class exists to close.
     * {@code /docs} is named individually because it is where the canary lives — the Swagger
     * validator badge violates {@code img-src} on any real hostname, and a page carrying no policy
     * reports nothing, which is indistinguishable from a policy nothing violates.
     */
    private static final List<String> DOCUMENT_PATHS =
            List.of("/", "/login", "/w/00000000-0000-0000-0000-000000000000/p/x/board", "/docs",
                    "/index.html");

    @LocalServerPort
    int port;

    /**
     * A real HTTP client against a real Tomcat, with the error handler removed so a non-200 is
     * asserted on rather than thrown: the whole point of this class is to read what went over the
     * wire, including on a response nobody meant to produce.
     */
    private final RestClient http = RestClient.builder()
            .defaultStatusHandler(status -> true, (request, response) -> { })
            .build();

    /**
     * <strong>The policy, on every path a user can open.</strong> A CSP applies to the document that
     * carries it, so a policy present on {@code /index.html} and absent from {@code /} protects
     * nothing anybody visits and measures nothing anybody does.
     */
    @Test
    void everyPathAUserOpensCarriesTheExactPolicy() {
        for (var path : DOCUMENT_PATHS) {
            assertThat(get(path).getHeaders().getFirst(ContentSecurityPolicy.HEADER))
                    .as("""
                        %s CARRIES NO CONTENT-SECURITY-POLICY OVER A REAL CONTAINER.

                        SpaController answers every dotless path with forward:/index.html, so this \
                        document is written on a FORWARD dispatch. It carries the block because \
                        HeaderWriterFilter's response wrapper spans that dispatch, so if the \
                        forwarded paths are bare while /index.html and /api/** are clean, the \
                        chain has stopped covering these paths at all - look at securityMatcher \
                        and at spring.security.filter.dispatcher-types, in that order. Do not \
                        "fix" this by asserting less: MockMvc records a forward instead of \
                        performing one, so nothing else in the suite reads bytes that left the \
                        container.""", path)
                    .isEqualTo(EXPECTED_POLICY);
        }
    }

    /**
     * <strong>And the rest of the block, which travels with it.</strong> The CSP is not a special
     * case here: {@code X-Content-Type-Options}, {@code X-Frame-Options} and the {@code no-store}
     * {@code Cache-Control} are written by the same filter on the same dispatch, so they are lost
     * and restored together. Asserted separately so a restoration is total rather than CSP-shaped —
     * a partial one that reads as complete is worse than a known gap, and {@code frame-ancestors}
     * in the policy is <em>report-only</em>, so {@code X-Frame-Options} is the only thing in this
     * product that actually refuses to be framed.
     */
    @Test
    void everyPathAUserOpensCarriesTheRestOfTheBlockToo() {
        for (var path : DOCUMENT_PATHS) {
            var headers = get(path).getHeaders();
            assertThat(headers.getFirst("X-Content-Type-Options"))
                    .as("nosniff on %s — these are the only HTML documents this product serves, "
                        + "which is the response shape where content sniffing matters most", path)
                    .isEqualTo("nosniff");
            assertThat(headers.getFirst("X-Frame-Options"))
                    .as("the frame refusal on %s — frame-ancestors 'none' is REPORT-ONLY and "
                        + "blocks nothing, so this header is the whole clickjacking defence and "
                        + "these pages are the whole clickjackable surface", path)
                    .isEqualTo("DENY");
            assertThat(headers.getFirst("Cache-Control"))
                    .as("the no-store block on %s", path)
                    .contains("no-store");
        }
    }

    /**
     * The API surface is unchanged by whatever keeps the documents covered — asserted because every
     * candidate mechanism for this touches the chain that writes {@code /api/**}'s headers, and
     * because this is the half that was never in doubt and therefore the half a fix can quietly
     * break.
     */
    @Test
    void theApiStillCarriesTheWholeBlock() {
        var response = get("/api/meta");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(ContentSecurityPolicy.HEADER))
                .isEqualTo(EXPECTED_POLICY);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    }

    /**
     * <strong>Nothing enforces, over a real container either.</strong> The MockMvc class asserts
     * this on a response that never left the chain. Repeated here because a mechanism that put the
     * header back onto forwarded responses by hand could write the enforcing name, which is the one
     * way this feature breaks the product rather than merely failing to measure it.
     */
    @Test
    void nothingEnforcesOnAnythingAUserOpens() {
        for (var path : DOCUMENT_PATHS) {
            assertThat(get(path).getHeaders().getFirst("Content-Security-Policy"))
                    .as("an ENFORCING policy on %s would break style, fonts and the docs page on "
                        + "the first page load", path)
                    .isNull();
        }
    }

    /**
     * <strong>The tripwire: the SPA document has to actually be a document.</strong> Without it
     * every assertion above is satisfiable by a 404. {@code src/main/resources/static/} is a build
     * product and is gitignored, so on a tree only ever built with {@code -Dfrontend.skip=true} the
     * forward finds nothing and the response comes back through an {@code ERROR} dispatch — which
     * was never the broken one. The class would then pass green while measuring the only dispatch
     * type that was never at risk: zero violations and a page that cannot violate are the same
     * observation, which is the sentence this whole feature exists to make false.
     */
    @Test
    void theSpaDocumentIsServedRatherThan404edThroughTheErrorDispatch() {
        for (var path : List.of("/", "/docs")) {
            var response = get(path);
            assertThat(response.getStatusCode().value())
                    .as("""
                        %s DID NOT RETURN THE SPA DOCUMENT, SO EVERY OTHER ASSERTION IN THIS CLASS \
                        PROVED NOTHING.

                        src/main/resources/static/index.html is a build product and is gitignored. \
                        Without it the forward 404s and the response comes back through an ERROR \
                        dispatch, which IS in the chain - so the headers are all present and the \
                        class goes green over exactly the path that was never broken. Build the \
                        frontend once (mvnw compile, WITHOUT -Dfrontend.skip=true).""", path)
                    .isEqualTo(200);
            assertThat(response.getBody())
                    .as("and it is the SPA shell, not some other 200")
                    .contains("<div id=\"root\"");
        }
    }

    private ResponseEntity<String> get(String path) {
        return http.get().uri("http://localhost:" + port + path).retrieve().toEntity(String.class);
    }
}
