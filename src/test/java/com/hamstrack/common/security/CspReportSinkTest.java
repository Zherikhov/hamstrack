package com.hamstrack.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.observability.ProductMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * <strong>The report sink, with the sink on</strong> (HD-264): what it accepts, what it silently
 * drops, what it counts, and what it puts in a log line.
 *
 * <p>The endpoint answers {@code 204} to almost everything, on purpose — a discriminating response
 * is a free oracle about the instance's state, including an answer to "is this sink watching?".
 * That makes the <strong>counters and the log fields</strong> the only observable difference
 * between an accepted report and a dropped one, so every assertion here is about those rather than
 * about the status.
 *
 * <p>{@code app.rate-limit.enabled=true}, deliberately: one of these tests exists to prove the sink
 * spends none of the <em>auth</em> budget, and with the master switch off it would prove nothing.
 * The sink's own budget is set high here and is exercised in {@code CspReportBudgetTest}.
 */
@SpringBootTest(properties = {
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=10000",
        "app.csp.reports-per-minute=10000",
        "app.rate-limit.enabled=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CspReportSinkTest {

    /**
     * A report body in the shape Chromium actually posts for the violation this design predicted
     * in advance — the docs page's Swagger validator badge, refused by {@code img-src 'self'
     * data:}. Kept as a captured body rather than a hand-written one because the field names are
     * the thing being tested: {@code document-uri}, not {@code documentUrl}, and a report the sink
     * cannot read is indistinguishable from a policy nobody violated.
     *
     * <p>The document URI deliberately carries a query string, because in this product that query
     * can be an HQL search — i.e. the text of somebody's issue titles — and these lines go to Loki.
     */
    private static final String CHROME_IMG_SRC_REPORT = """
            {"csp-report":{\
            "document-uri":"http://localhost:8080/w/ws-1/search?q=summary%20~%20%22PAYROLL%22",\
            "referrer":"",\
            "violated-directive":"img-src 'self' data:",\
            "effective-directive":"img-src",\
            "original-policy":"default-src 'self'; img-src 'self' data:",\
            "disposition":"report",\
            "blocked-uri":"https://validator.swagger.io/validator?url=http%3A%2F%2Flocalhost%3A8080%2Fopenapi.yaml",\
            "line-number":1,\
            "source-file":"http://localhost:8080/assets/DocsPage-C-1Dn6e2.js?v=2",\
            "status-code":200,\
            "script-sample":""}}""";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JsonMapper jsonMapper;
    @Autowired ProductMetrics productMetrics;
    @Autowired CspReportBudget reportBudget;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        logged.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CspReportSink.class))
                .addAppender(logged);
    }

    @AfterEach
    void detachAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CspReportSink.class))
                .detachAppender(logged);
        logged.stop();
    }

    /**
     * <strong>The canary, end to end</strong>: the exact violation the design predicts will be the
     * first one production sees, answered {@code 204}, counted under {@code img-src}, and logged
     * once with its query strings gone.
     */
    @Test
    void aRealBrowserReportIsAcceptedCountedAndLoggedWithoutItsQueryStrings() throws Exception {
        double before = violations("img-src");

        var response = send(CHROME_IMG_SRC_REPORT);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(violations("img-src") - before).isEqualTo(1.0);

        var fields = onlyLoggedFields();
        assertThat(fields.get("csp.directive")).isEqualTo("img-src");
        assertThat(fields.get("csp.blocked"))
                .as("""
                    THE BLOCKED URI IS LOGGED WITHOUT ITS QUERY.

                    This is the canary itself - the Swagger validator badge, whose query carries \
                    this instance's own spec URL - and the rule is the project's own rule for its \
                    ops scripts: names and counts, never contents. Scheme, host and path say WHAT \
                    was refused; the query says nothing an operator needs and travels to Loki.""")
                .isEqualTo("https://validator.swagger.io/validator");
        assertThat(fields.get("csp.document"))
                .as("""
                    THE DOCUMENT URI IS LOGGED AS A ROUTE, NEVER WHOLE.

                    In this product a document URI can be a search URL carrying an HQL query - the \
                    text of somebody's issue titles - and it carries workspace and project ids in \
                    its path besides. The query goes first (here), the identifiers go in the test \
                    below. If the query is here, every search anyone runs while violating the \
                    policy is now in Loki.""")
                .isEqualTo("/w/ws-1/search");
        assertThat(fields.get("csp.source"))
                .as("the source keeps its ORIGIN and loses its query: the scheme is what separates "
                    + "our own bundle from an injected chrome-extension:// script, and the origin "
                    + "costs nothing an operator has to redact")
                .isEqualTo("http://localhost:8080/assets/DocsPage-C-1Dn6e2.js");
        assertThat(fields.get("csp.line")).isEqualTo("1");
    }

    /**
     * <strong>An open report sink is a public log-fill primitive unless it checks whose page the
     * report is about.</strong> Anybody can point their own site's {@code report-uri} at ours and
     * have every one of their visitors write lines in our journal. The drop is silent — still
     * {@code 204}, because refusing differently would tell the prober that the filter exists — so
     * the counter is the only witness, which is exactly why there is one.
     */
    @Test
    void aReportAboutSomebodyElsesPageIsDroppedCountedAndStillAnswered204() throws Exception {
        double before = dropped("foreign_document");

        var response = send(CHROME_IMG_SRC_REPORT.replace(
                "http://localhost:8080/w/ws-1/search?q=summary%20~%20%22PAYROLL%22",
                "https://evil.example/x"));

        assertThat(response.getStatus())
                .as("identical to the accepted case: nothing about this endpoint's answer may "
                    + "depend on what it did with the body")
                .isEqualTo(204);
        assertThat(dropped("foreign_document") - before).isEqualTo(1.0);
        assertThat(logged.list)
                .as("a page we do not serve produces no log line, which is the whole point — "
                    + "otherwise the filter would cost exactly what it saves")
                .isEmpty();
    }

    /**
     * <strong>The unbounded-cardinality hole, closed.</strong> {@code effective-directive} is
     * attacker-supplied text on an endpoint that needs no account, so passing it through as a label
     * would let one {@code curl} loop create Prometheus series without limit. Everything the policy
     * does not ship lands on {@code other}.
     */
    @Test
    void anUnknownDirectiveLandsOnTheClosedSetsOtherLabelAndCreatesNoSeries() throws Exception {
        var seriesBefore = directiveTagsSeen();

        for (var reported : new String[]{"'; DROP", "media-src", "a".repeat(4096), "IMG-SRC"}) {
            send(CHROME_IMG_SRC_REPORT.replace("\"effective-directive\":\"img-src\"",
                    "\"effective-directive\":\"" + reported.replace("\"", "") + "\""));
        }

        assertThat(directiveTagsSeen())
                .as("""
                    THE directive LABEL MUST COME FROM A CLOSED SET.

                    Its raw value arrives in the body of an UNAUTHENTICATED post, so a pass-through \
                    is an unbounded-cardinality write into the metrics store from the public \
                    internet. Anything the policy does not ship is 'other'; the full directive text \
                    survives in the log line, where it costs nothing.""")
                .isSubsetOf("default-src", "base-uri", "object-src", "frame-ancestors",
                        "form-action", "script-src", "style-src", "font-src", "img-src",
                        "connect-src", "frame-src", "worker-src", "other")
                .containsAll(seriesBefore)
                .contains("other");
        assertThat(directiveTagsSeen())
                .as("case is folded rather than becoming a second series")
                .contains("img-src");
    }

    /**
     * A body that is not JSON, and one that is JSON but is not a report, are both counted rather
     * than merely ignored — and both still answer {@code 204}, because a 400 here would be a shape
     * of answer this endpoint does not have.
     */
    @Test
    void anUnreadableBodyIsCountedRatherThanIgnored() throws Exception {
        double before = dropped("unparseable");

        assertThat(send("this is not json").getStatus()).isEqualTo(204);
        assertThat(send("{\"something\":\"else\"}").getStatus()).isEqualTo(204);
        assertThat(send("").getStatus()).isEqualTo(204);

        assertThat(dropped("unparseable") - before).isEqualTo(3.0);
        assertThat(logged.list).isEmpty();
    }

    /**
     * <strong>The 16 KB bound, refused before the handler is entered.</strong> The body sent here is
     * a <em>valid</em> report padded past the bound, so the assertion that no violation was counted
     * is what proves the refusal happened in the filter: had the handler run, this body would have
     * been logged and counted like any other.
     */
    @Test
    void aBodyOverTheBoundIsRefusedWithoutTheHandlerBeingEntered() throws Exception {
        double violationsBefore = violations("img-src");
        double dropsBefore = dropped("too_large");
        var padded = CHROME_IMG_SRC_REPORT.replace("\"referrer\":\"\"",
                "\"referrer\":\"" + "x".repeat(17 * 1024) + "\"");

        var response = send(padded);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(dropped("too_large") - dropsBefore).isEqualTo(1.0);
        assertThat(violations("img-src") - violationsBefore)
                .as("""
                    THE 413 MUST HAPPEN BEFORE THE HANDLER.

                    The body sent here is a PERFECTLY VALID report with padding, so if the handler \
                    had been entered this counter would have moved. A cap enforced after the body \
                    has been read and parsed has already paid the cost it exists to avoid.""")
                .isEqualTo(0.0);
        assertThat(logged.list).isEmpty();
    }

    /**
     * The content-type refusal is the mapping's, and it is asserted because the accepted set is
     * three: {@code application/csp-report} is what {@code report-uri} sends,
     * {@code application/json} is what several tools send, and {@code application/reports+json} is
     * what the Reporting API will send when the enforcement ticket turns it on — written in now so
     * that becomes a header change rather than a rewrite.
     */
    @Test
    void onlyTheReportContentTypesAreAccepted() throws Exception {
        assertThat(send(CHROME_IMG_SRC_REPORT, "application/csp-report").getStatus()).isEqualTo(204);
        assertThat(send(CHROME_IMG_SRC_REPORT, "application/json").getStatus()).isEqualTo(204);
        assertThat(send(CHROME_IMG_SRC_REPORT, "application/reports+json").getStatus())
                .isEqualTo(204);
        assertThat(send(CHROME_IMG_SRC_REPORT, "text/plain").getStatus()).isEqualTo(415);
    }

    /** A batch, which is the Reporting API's shape and is accepted from day one. */
    @Test
    void anArrayOfReportsIsAccepted() throws Exception {
        double before = violations("img-src");

        var response = send("[" + CHROME_IMG_SRC_REPORT + "," + CHROME_IMG_SRC_REPORT + "]");

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(violations("img-src") - before).isEqualTo(2.0);
    }

    /**
     * <strong>One request may not ask for an unbounded amount of work.</strong> The body bound is
     * 16 KB and a minimal report is a few dozen bytes, so without this cap one request asks for
     * hundreds of parses, budget consultations and log writes.
     *
     * <p>It is <em>not</em> the volume bound and never could be, which is what
     * {@code CspReportBatchBudgetTest} exists to say: a cap of twenty against a budget counted in
     * requests is twenty times the budget in lines. Volume is bounded by charging the budget per
     * line written.
     */
    @Test
    void aBatchPastThePerRequestCapIsTruncatedAndCounted() throws Exception {
        double violationsBefore = violations("img-src");
        double dropsBefore = dropped("too_large");
        var minimal = "{\"csp-report\":{\"document-uri\":\"http://localhost:8080/\","
                      + "\"effective-directive\":\"img-src\"}}";
        var batch = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            batch.append(i == 0 ? "" : ",").append(minimal);
        }

        assertThat(send(batch.append("]").toString()).getStatus()).isEqualTo(204);

        assertThat(violations("img-src") - violationsBefore).isEqualTo(20.0);
        assertThat(dropped("too_large") - dropsBefore).isEqualTo(5.0);
    }

    /**
     * <strong>The assertion that would have caught the "shared budget" mistake, and it is
     * cheap.</strong> A report flood must never be able to lock a user out of {@code /login} — the
     * auth budget is per-IP, shared IPs are ordinary, and the caller filling it here is a browser
     * behaving correctly on a page we serve. That is HD-233's refund defect arriving from a new
     * direction: a refusal the instance provoked, charged to the caller.
     */
    @Test
    void aHundredReportsSpendNoneOfTheAuthBudget() throws Exception {
        var ip = "203.0.113.42";
        for (int i = 0; i < 100; i++) {
            assertThat(send(CHROME_IMG_SRC_REPORT, "application/csp-report", ip).getStatus())
                    .isEqualTo(204);
        }

        var login = mockMvc.perform(post("/api/auth/login")
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"whatever\"}"))
                .andReturn().getResponse();

        assertThat(login.getStatus())
                .as("""
                    THE SINK MAY NOT SPEND THE AUTH BUDGET.

                    100 reports is nearly seven times app.rate-limit.auth-ip-requests-per-minute. \
                    If this is 429, a browser violating the policy on a page WE serve has locked \
                    its own user out of the login door, from an address other people share - which \
                    is a denial of service this feature would have introduced by economising on a \
                    map. A throttle is earned by the work a handler does, and this one's work is a \
                    log line.""")
                .isEqualTo(401);
    }

    /**
     * <strong>Only POST reaches the sink, and the other methods are refused by
     * AUTHENTICATION rather than by the mapping.</strong>
     *
     * <p>The proposal predicted {@code 405} here. What actually ships is {@code 401}, and the
     * difference is the better outcome rather than a compromise: the anonymous exemption in
     * {@code SecurityConfig} is scoped to {@code POST}, so every other method on this path stays
     * inside the {@code /api/**} authenticated set and never reaches a handler at all. An exemption
     * as wide as the path would have taken {@code GET} out of that set too — where the SPA fallback
     * answers any dotless path with {@code index.html} — for no reason the endpoint has.
     */
    @Test
    void noMethodButPostReachesTheSink() throws Exception {
        double before = violations("img-src");

        assertThat(perform(put(ContentSecurityPolicy.REPORT_PATH)
                .contentType("application/csp-report").content(CHROME_IMG_SRC_REPORT))
                .getStatus())
                .as("the anonymous exemption is POST-scoped, so a PUT is an ordinary /api/** "
                    + "request and is refused before any mapping is consulted")
                .isEqualTo(401);
        assertThat(perform(get(ContentSecurityPolicy.REPORT_PATH)).getStatus())
                .as("and a GET does not fall through to the SPA document either")
                .isEqualTo(401);

        assertThat(violations("img-src") - before).isEqualTo(0.0);
        assertThat(logged.list).isEmpty();
    }

    /** With the sink on, the header names the endpoint the instance now serves. */
    @Test
    void theHeaderNamesTheReportUriExactlyWhenTheSinkIsServed() throws Exception {
        var policy = perform(get("/")).getHeader("Content-Security-Policy-Report-Only");

        assertThat(policy).endsWith("; report-uri /api/security/csp-report");
    }

    /**
     * <strong>The document and the source are logged as the route they match, never as one
     * instance of it.</strong>
     *
     * <p>Every other line in this product that names a workspace names one the SERVER resolved for
     * an authenticated actor. This one would name one the CALLER TYPED, on a door that needs no
     * account — so a retained id is not merely a privacy cost, it is a field that can be asserted
     * by anybody who knows a workspace UUID, at 60 lines a minute, which destroys the only thing
     * the field was for. The source file is normalised too because for an inline violation the
     * engines report the document AS the source file.
     */
    @Test
    void theDocumentAndTheSourceAreLoggedAsARouteRatherThanAsTheTenantsOwnIds() throws Exception {
        var workspace = "0197b0c1-4b8e-7c1a-9d3f-2a5e6f7c8b90";
        var project = "0197b0c1-4b8e-7c1a-9d3f-2a5e6f7c8b91";
        var page = "http://localhost:8080/w/" + workspace + "/p/" + project + "/issues/42";

        send(CHROME_IMG_SRC_REPORT
                .replace("http://localhost:8080/w/ws-1/search?q=summary%20~%20%22PAYROLL%22",
                        page + "?tab=history")
                .replace("http://localhost:8080/assets/DocsPage-C-1Dn6e2.js?v=2", page));

        var fields = onlyLoggedFields();
        assertThat(fields.get("csp.document"))
                .as("""
                    NO CALLER-ASSERTED IDENTIFIER MAY REACH THIS LINE.

                    A workspace id here is attributable to nobody: the endpoint is \
                    unauthenticated, so the value is whatever the sender typed, and anyone who \
                    knows a UUID could mint INFO lines against that tenant at the per-IP budget. \
                    ProductMetrics reduces a request URI to its mapped pattern for exactly this \
                    reason. Nothing diagnostic is lost - the question these lines answer is WHICH \
                    PAGE, and which page is the pattern.""")
                .isEqualTo("/w/{id}/p/{id}/issues/{n}")
                .doesNotContain(workspace, project);
        assertThat(fields.get("csp.source"))
                .as("an inline violation reports the DOCUMENT as its source file, so this field "
                    + "carries the same ids exactly when it matters — the origin survives, the "
                    + "identifiers do not")
                .isEqualTo("http://localhost:8080/w/{id}/p/{id}/issues/{n}")
                .doesNotContain(workspace, project);
    }

    /**
     * <strong>A value with no {@code /} in it is patterned too, and {@code source-file} is the
     * field where that matters.</strong>
     *
     * <p>The patterning had an early return for any value containing no slash, on the reading that
     * a path is a thing with slashes in it. But {@code source-file} is never host-checked — only
     * {@code document-uri} is — so a bare UUID posted there parses as a relative reference whose
     * whole string is its path, took the early return, and reached Loki verbatim. That is the
     * minting attack in the field the guard was extended <em>to</em>: anyone who knows a workspace
     * id could write INFO lines naming that tenant, on a door that needs no account.
     */
    @Test
    void aBareIdentifierWithNoSlashInItIsPatternedRatherThanLoggedVerbatim() throws Exception {
        var workspace = "0197b0c1-4b8e-7c1a-9d3f-2a5e6f7c8b90";

        send(CHROME_IMG_SRC_REPORT.replace(
                "http://localhost:8080/assets/DocsPage-C-1Dn6e2.js?v=2", workspace));

        assertThat(onlyLoggedFields().get("csp.source"))
                .as("""
                    THE EARLY RETURN FOR A VALUE WITH NO SLASH WAS THE HOLE.

                    source-file is not checked against this instance's host, so a bare workspace \
                    UUID posted here is a relative reference whose path is the whole string. With \
                    the early return it was logged as itself; the loop handles a one-element split \
                    correctly, so removing it is strictly a widening of the guard.""")
                .isEqualTo("{id}")
                .doesNotContain(workspace);
    }

    /**
     * <strong>An injected extension script must stay distinguishable from our own bundle.</strong>
     *
     * <p>Browser-extension injection is the dominant noise source in any real report-only stream
     * and the primary confounder of the {@code style-src} count — the design's own highest-risk
     * assumption, to be settled by counting. Reduced to a path, {@code chrome-extension://<id>/
     * content.js} logs as {@code /content.js}; and for an inline violation {@code blocked-uri} is
     * the keyword {@code inline}, so nothing else in the line can rescue the attribution.
     */
    @Test
    void anInjectedExtensionScriptKeepsTheSchemeThatAttributesIt() throws Exception {
        for (var pair : new String[][]{
                {"chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/content.js",
                 "chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/content.js"},
                // A Firefox extension's host is a per-install random UUID. It is patterned like
                // any other identifier — a UUID is a legal hostname, so a host kept verbatim is
                // the same minting vector one component to the left — and the scheme still says
                // "a Firefox extension", which is the whole question.
                {"moz-extension://0197b0c1-4b8e-7c1a-9d3f-2a5e6f7c8b90/inject.js",
                 "moz-extension://{id}/inject.js"},
                {"safari-web-extension://abcdef01/page.js",
                 "safari-web-extension://abcdef01/page.js"}}) {
            logged.list.clear();

            send(CHROME_IMG_SRC_REPORT
                    .replace("http://localhost:8080/assets/DocsPage-C-1Dn6e2.js?v=2", pair[0])
                    .replace("https://validator.swagger.io/validator?url=http%3A%2F%2F"
                             + "localhost%3A8080%2Fopenapi.yaml", "inline"));

            assertThat(onlyLoggedFields().get("csp.source"))
                    .as("""
                        blocked=inline, source=/content.js READS EXACTLY LIKE OUR OWN CODE.

                        Path-only patterning erased the one thing that separates an extension \
                        from the product: the scheme. The count that decides whether style-src \
                        needs an escape hatch is the count this confounds, and there is no second \
                        field to fall back on - for an inline violation the blocked URI is a \
                        keyword.""")
                    .isEqualTo(pair[1]);
        }
    }

    /**
     * <strong>Every field in the line is bounded, and the bound is what makes this endpoint's cost
     * knowable.</strong>
     *
     * <p>Nothing here is authenticated, so the whole body is text of the sender's own choosing and
     * length. Before this, four of the seven fields were unbounded — the blocked URI's PATH (only
     * its host was reduced), the document, the source file and the line number — so one request
     * inside the 16 KB body bound could write ~16 KB of attacker-authored text into one INFO line.
     * At the instance budget of 600 lines a minute that is about 13.8 GB a day of somebody else's
     * content into the log of a box that also holds the database and the attachments: the same
     * disk-fill argument this feature makes against persisting reports in a table, aimed at the log
     * instead.
     *
     * <p><strong>This bound is one of two, and neither is the other's substitute.</strong> A line's
     * length is bounded here; how many lines one sender may buy is bounded by the budget, which is
     * charged per line and sealed by {@code CspReportBatchBudgetTest}. Bounding only this one and
     * counting the budget in <em>requests</em> is how the feature's volume figures came to be 20×
     * understatements while both seals stayed green — each was right about its half, and nothing
     * multiplied them.
     */
    @Test
    void everyLoggedFieldIsBoundedWhateverTheSenderSends() throws Exception {
        var padding = "A".repeat(1500);
        var report = ("{\"csp-report\":{"
                      + "\"document-uri\":\"http://localhost:8080/PAD\","
                      + "\"effective-directive\":\"img-src\","
                      + "\"blocked-uri\":\"https://cdn.example/PAD.png\","
                      + "\"source-file\":\"http://localhost:8080/assets/PAD.js\","
                      + "\"line-number\":\"PAD\","
                      + "\"script-sample\":\"PAD\"}}").replace("PAD", padding);

        assertThat(send(report, "application/csp-report", "198.51.100.7", padding).getStatus())
                .isEqualTo(204);

        var fields = onlyLoggedFields();
        assertThat(fields.get("csp.blocked"))
                .as("""
                    THE BLOCKED URI'S PATH IS BOUNDED, NOT ONLY ITS HOST.

                    Reducing a URL to scheme://host+path drops the query and reduces the host, and \
                    leaves the PATH written by the sender to any length it likes. The truncation \
                    used to sit on the two FALLBACK branches only, so a WELL-FORMED URL was the \
                    way past it.""")
                .hasSizeLessThanOrEqualTo(120)
                .startsWith("https://cdn.example/AAA");
        assertThat(fields.get("csp.document")).hasSizeLessThanOrEqualTo(200);
        assertThat(fields.get("csp.source")).hasSizeLessThanOrEqualTo(200);
        assertThat(fields.get("csp.sample")).hasSizeLessThanOrEqualTo(40);
        assertThat(fields.get("csp.ua")).hasSizeLessThanOrEqualTo(120);
        assertThat(lineBytes(fields))
                .as("and the whole line's field values sum to the ~700 BYTES this feature "
                    + "promises an operator, which is the figure every volume number downstream "
                    + "of it is computed from")
                .isLessThanOrEqualTo(720);
        assertThat(fields.get("csp.line"))
                .as("""
                    A LINE NUMBER THAT IS NOT A NUMBER IS DROPPED.

                    This field went to the log through the same generic text reader as the URLs, \
                    with neither a numeric check nor a bound, so "line-number": "<15 KB>" was a \
                    log-fill primitive wearing a numeric name. An absent line number is a case \
                    every reader of these lines already handles.""")
                .isNull();
    }

    /**
     * <strong>The bound is in BYTES, because what it defends is a disk.</strong>
     *
     * <p>{@code String.length()} counts UTF-16 units and UTF-8 spends three bytes on most BMP
     * non-ASCII characters, so a "~700 byte" line counted in chars was ~2.1 KB in CJK — and it is
     * reachable rather than theoretical: {@code URI.getPath()} <em>decodes</em>, so
     * {@code %E4%B8%AD} in a reported URL arrives as a character, and {@code script-sample} is a
     * raw JSON string. This is CLAUDE.md's {@code @Size}-versus-BCrypt lesson (the annotation
     * counts UTF-16 units, the encoder counts bytes) applied to a log budget, and an assertion on
     * {@code hasSize} would have watched it happen.
     */
    @Test
    void theBoundIsCountedInUtf8BytesRatherThanInUtf16Units() throws Exception {
        // 300 characters is 900 UTF-8 bytes - past every bound below, and inside the 16 KB body
        // bound this sender is not the one testing.
        var padding = "中".repeat(300);
        var report = ("{\"csp-report\":{"
                      + "\"document-uri\":\"http://localhost:8080/PAD\","
                      + "\"effective-directive\":\"img-src\","
                      + "\"blocked-uri\":\"https://cdn.example/PAD.png\","
                      + "\"source-file\":\"http://localhost:8080/assets/PAD.js\","
                      + "\"script-sample\":\"PAD\"}}").replace("PAD", padding);

        assertThat(send(report, "application/csp-report", "198.51.100.7", padding).getStatus())
                .isEqualTo(204);

        var fields = onlyLoggedFields();
        assertThat(lineBytes(fields))
                .as("""
                    ~700 "BYTES" WAS A BOUND IN UTF-16 CHARACTERS.

                    Three bytes per CJK character makes the same line ~2.1 KB, and every volume \
                    figure in this feature - the per-minute rate, the MB/day an operator is \
                    promised - is computed from that number. Either bound the bytes or state the \
                    characters; do not state one and enforce the other.""")
                .isLessThanOrEqualTo(720);
        assertThat(utf8(fields.get("csp.sample")))
                .as("the sample is 40 BYTES, which for a non-ASCII sample is a shorter cut than "
                    + "the browsers' own 40-character one — the deliberate half of the trade")
                .isLessThanOrEqualTo(40);
        assertThat(fields.get("csp.document"))
                .as("and a cut never lands mid-character: the byte index steps back off a "
                    + "continuation byte, which also disposes of the lone-surrogate case for free")
                .doesNotContain("�");
    }

    /**
     * <strong>A control character may not reach a logged value, whatever encoder is configured.</strong>
     *
     * <p>{@code URI.getPath()} decodes, so {@code %0D%0A} in a reported URL is a real CRLF by the
     * time it is a field, and {@code script-sample} carries one directly. Both deployed profiles
     * set {@code logging.structured.format.console=logstash}, whose writer escapes them — but that
     * property is set <em>nowhere else</em> and is env-overridable, so a {@code local} run with the
     * sink on, or a Cloud box whose {@code LOGGING_STRUCTURED_FORMAT_CONSOLE} was cleared, would
     * let an anonymous sender forge whole log lines. The class's own doctrine is <em>bounded and
     * normalised rather than trusted</em>, and safety that depends on which encoder is configured
     * is neither.
     */
    @Test
    void controlCharactersAreStrippedAtTheWriteSiteRatherThanLeftToTheEncoder() throws Exception {
        send(CHROME_IMG_SRC_REPORT
                .replace("http://localhost:8080/w/ws-1/search?q=summary%20~%20%22PAYROLL%22",
                        "http://localhost:8080/board%0D%0AWARN%20forged")
                .replace("\"script-sample\":\"\"", "\"script-sample\":\"a\\r\\nfake line\""));

        var fields = onlyLoggedFields();
        assertThat(fields.get("csp.document"))
                .as("""
                    A DECODED %0D%0A IS A REAL CRLF IN A FIELD AN ANONYMOUS SENDER WROTE.

                    getPath() decodes, so this is not a value that has to survive an encoder - it \
                    is a value that has to not exist. The logstash writer escapes it on both \
                    deployed profiles, which is exactly the kind of safety that disappears when \
                    one environment variable is cleared.""")
                .isEqualTo("/boardWARN forged");
        assertThat(fields.get("csp.sample")).isEqualTo("afake line");
    }

    /**
     * <strong>The blocked URI's path is patterned too — the one field that could still name a
     * tenant.</strong>
     *
     * <p>The foreign-document filter compares {@code document-uri} alone, so this field is not
     * same-origin by construction and never was. Under the shipped policy no genuine same-origin
     * blocked URI carries a tenant id, but two routes remain: a forged report, and an HD-176-shaped
     * exfiltration URL that encodes ids in its <em>path</em> rather than in its query. Patterning
     * costs nothing an operator wanted: only UUID-shaped and all-digit segments are replaced, so a
     * CDN asset path arrives intact — as the canary test above asserts.
     */
    @Test
    void aBlockedUriKeepsItsOriginAndLosesItsIdentifiers() throws Exception {
        var workspace = "0197b0c1-4b8e-7c1a-9d3f-2a5e6f7c8b90";

        send(CHROME_IMG_SRC_REPORT.replace(
                "https://validator.swagger.io/validator?url=http%3A%2F%2Flocalhost%3A8080%2Fopenapi.yaml",
                "https://exfil.example/" + workspace + "/42/pixel.png"));

        assertThat(onlyLoggedFields().get("csp.blocked"))
                .as("scheme and host say WHICH ORIGIN was refused, which is what this field is "
                    + "for; the identifiers in the path say which tenant, which no unauthenticated "
                    + "sender is entitled to assert")
                .isEqualTo("https://exfil.example/{id}/{n}/pixel.png")
                .doesNotContain(workspace);
    }

    /**
     * <strong>The blocked URI's two fallback branches strip the query too.</strong> They are not
     * hypothetical: engines report a same-origin refusal as a PATH-RELATIVE URI, which has neither
     * scheme nor host and therefore lands on the fallback — and in this product that path can be a
     * search whose query is somebody's issue titles. A scheme-relative value takes the same branch.
     */
    @Test
    void aBlockedUriWithNoSchemeStillLosesItsQuery() throws Exception {
        for (var pair : new String[][]{
                {"/w/ws-1/search?q=summary%20~%20%22PAYROLL%22", "/w/ws-1/search"},
                {"//evil.example/x?token=abc", "//evil.example/x"},
                {"/w/ws-1/board#fragment", "/w/ws-1/board"}}) {
            logged.list.clear();

            send(CHROME_IMG_SRC_REPORT.replace(
                    "https://validator.swagger.io/validator?url=http%3A%2F%2Flocalhost%3A8080%2Fopenapi.yaml",
                    pair[0]));

            assertThat(onlyLoggedFields().get("csp.blocked"))
                    .as("a blocked URI with no scheme is still a URL with a query, and the query "
                        + "is the half this product may not log")
                    .isEqualTo(pair[1]);
        }
    }

    /**
     * <strong>The misconfiguration that actually happens is the silent one, so it gets the
     * warning.</strong>
     *
     * <p>{@code app.base-url} defaults to a <em>valid</em> URL, so the hostless case — which no
     * longer fails open, see the test below — cannot be reached by leaving a default alone; it needs
     * a deliberately broken value. What a self-hoster actually does is turn the sink on and
     * leave {@code APP_BASE_URL} at {@code http://localhost:8080}, and then <strong>100% of real
     * reports are dropped</strong> as {@code foreign_document} while every part of the feature looks
     * configured. One line at startup turns a silent zero into a labelled one.
     */
    @Test
    void aLoopbackBaseUrlWithTheSinkOnIsWarnedAboutRatherThanQuietlyCollectingNothing() {
        logged.list.clear();
        new CspReportSink(jsonMapper, productMetrics, baseUrl("http://localhost:8080"),
                reportBudget);

        assertThat(warnings())
                .as("""
                    A ZERO AN OPERATOR CANNOT TELL FROM A QUIET INSTANCE IS NOT AN INSTRUMENT.

                    With the sink on and this default left alone, the host comparison drops every \
                    report a browser could send. The endpoint answers 204, the header names it, \
                    the budget is spent, and the only witness is a counter nobody is looking at \
                    yet. Say it once, at startup, where somebody is still reading.""")
                .hasSize(1)
                .allSatisfy(message -> assertThat(message)
                        .contains("APP_BASE_URL")
                        .contains("foreign_document"));

        logged.list.clear();
        new CspReportSink(jsonMapper, productMetrics, baseUrl("https://tracker.example.com"),
                reportBudget);
        assertThat(warnings())
                .as("and a configured install is not nagged — a warning that fires on the correct "
                    + "configuration is a warning nobody reads on the incorrect one")
                .isEmpty();
    }

    /**
     * <strong>A base URL with no readable host refuses the boot; it does not accept everything.</strong>
     *
     * <p>The filter it feeds is the only thing between an unauthenticated endpoint and reports about
     * <em>any page on the internet, from anyone</em>, and it used to be switched off by a
     * misconfiguration with nothing but a {@code log.warn} to say so. The incremental harm is
     * genuinely small — a hostname is not a credential and the budget still bounds the volume — but
     * the fail-open is avoidable at zero cost, and this feature's other half already refuses a boot
     * over a related misconfiguration ({@code CSP_POLICY} naming a path this instance cannot serve).
     *
     * <p>This class is a bean only while {@code app.csp.sink-enabled} is true, so its constructor
     * <em>is</em> the boot: reaching it means an operator asked to collect. Both spellings that
     * produce a hostless URL are covered — a missing scheme, which is the likeliest typo, and an
     * underscore in the host, which {@code java.net.URI} rejects as a reg-name — because the class of
     * failure is "anything URI cannot read a host from" rather than either example.
     */
    @Test
    void aBaseUrlWithNoHostRefusesToStartRatherThanAcceptingEverybodysReports() {
        for (var broken : new String[]{"tracker.example.com", "http://my_host:8080", "", "not a url"}) {
            assertThatThrownBy(() -> new CspReportSink(
                    jsonMapper, productMetrics, baseUrl(broken), reportBudget))
                    .as("""
                        A FAIL-OPEN ON AN UNAUTHENTICATED ENDPOINT IS NOT A WARNING: %s.

                        With no own host to compare against, isOurs returned true for everything - \
                        so this sink accepted reports about any page on the internet, from anyone, \
                        and the only witness was one startup log line. Refuse the boot: the reader \
                        holds APP_BASE_URL and CSP_REPORT_SINK_ENABLED, and either one resolves \
                        it.""".formatted(broken.isEmpty() ? "(empty)" : broken))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_BASE_URL")
                    .hasMessageContaining("CSP_REPORT_SINK_ENABLED");
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Only {@code baseUrl} is read by the sink; the rest of this record is not its business. */
    private static AppProperties baseUrl(String value) {
        return new AppProperties(value, null, null, null, null, null, null);
    }

    private java.util.List<String> warnings() {
        return logged.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private MockHttpServletResponse send(String body) throws Exception {
        return send(body, "application/csp-report");
    }

    private MockHttpServletResponse send(String body, String contentType) throws Exception {
        return send(body, contentType, "198.51.100.7");
    }

    private MockHttpServletResponse send(String body, String contentType, String ip)
            throws Exception {
        return send(body, contentType, ip, "Mozilla/5.0 (test)");
    }

    private MockHttpServletResponse send(String body, String contentType, String ip,
                                         String userAgent) throws Exception {
        return perform(post(ContentSecurityPolicy.REPORT_PATH)
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .header("User-Agent", userAgent)
                .contentType(contentType)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /**
     * The size of one line's field values <strong>in UTF-8 bytes</strong> — the unit the feature's
     * volume figures are written in, and the one a disk uses. Counted over the values only: the
     * field names and the logstash envelope are a further ~400 bytes that no sender controls.
     */
    private static int lineBytes(Map<String, String> fields) {
        return fields.values().stream().mapToInt(CspReportSinkTest::utf8).sum();
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private double violations(String directive) {
        var counter = meterRegistry.find("hamstrack.csp.violations")
                .tag("directive", directive).counter();
        return counter == null ? 0 : counter.count();
    }

    private double dropped(String reason) {
        var counter = meterRegistry.find("hamstrack.csp.reports_dropped")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Every distinct {@code directive} tag value the registry has ever seen. */
    private java.util.Set<String> directiveTagsSeen() {
        return meterRegistry.find("hamstrack.csp.violations").counters().stream()
                .map(counter -> counter.getId().getTag("directive"))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The key/value fields of the one line the sink logged. Read as FIELDS rather than as text:
     * what the stripping rules promise is about the values a log pipeline stores, and a substring
     * assertion over a rendered message would pass on a value that merely happens not to appear.
     */
    private Map<String, String> onlyLoggedFields() {
        assertThat(logged.list).hasSize(1);
        var pairs = logged.list.getFirst().getKeyValuePairs();
        assertThat(pairs).as("the line must carry structured fields, not a formatted sentence")
                .isNotNull();
        var fields = new HashMap<String, String>();
        for (var pair : pairs) {
            fields.put(pair.key, pair.value == null ? null : String.valueOf(pair.value));
        }
        assertThat(fields.keySet())
                .as("the whole field set, so a new one has to be considered against the stripping "
                    + "rules rather than added quietly")
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(
                        "csp.directive", "csp.blocked", "csp.document", "csp.source", "csp.line",
                        "csp.sample", "csp.ua"));
        return fields;
    }
}
