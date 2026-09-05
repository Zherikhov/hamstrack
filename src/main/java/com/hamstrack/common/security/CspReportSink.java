package com.hamstrack.common.security;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.CspDirective;
import com.hamstrack.common.observability.ProductMetrics.CspDropReason;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <strong>What happens to a Content-Security-Policy violation report once it has been accepted:
 * it is parsed, filtered, logged once and counted once — and then forgotten</strong> (HD-264).
 *
 * <h2>Nothing is persisted, and that is a decision</h2>
 * No table, no column, no migration. An unauthenticated door that writes rows is a disk-fill vector
 * on an instance whose disk also holds the attachments and the database; the questions the
 * enforcement ticket has to answer are aggregate questions over days, which Loki's existing
 * retention already covers; and the day that ticket is done the historical reports are worth
 * nothing, while a table nobody reads is a table somebody has to migrate. The durable artefacts are
 * a log line and two counters.
 *
 * <h2>What is stripped, what is bounded, and why neither is optional</h2>
 * A {@code document-uri} in this product can be a search URL carrying an HQL query — i.e. the text
 * of somebody's issue titles — and the same field carries workspace and project ids in its path.
 * These lines travel to Loki and are read by operators, and this project's rule for its own ops
 * scripts is <em>names and counts, never contents</em>. So the query is dropped; every URL in the
 * line has its identifier-shaped segments replaced by placeholders, so it is logged as the
 * <strong>route pattern</strong> it matches ({@code /w/{id}/p/{id}/issues/{n}}) rather than as one
 * instance of it; the document keeps its path alone, the blocked URI and the source file keep their
 * origin (scheme, host and port) as well, because <em>which engine or extension</em> and <em>which
 * origin</em> are the questions those two fields exist to answer; and the sample is cut at 40 bytes
 * against the browsers' own 40 characters — restated here so a non-conforming sender cannot widen
 * it, and re-denominated because a log budget is in bytes. The user agent is kept
 * (truncated) because the enforcement decision has to be readable per engine, and report field
 * names, directive support and delivery all differ between them.
 *
 * <h2>Every value in the line is bounded, and the budget is spent per LINE</h2>
 * The door needs no account, so the whole body is caller-authored text of the caller's own length,
 * and the disk this log lands on also holds the database and the attachments — the same disk-fill
 * argument this class makes against persisting reports in a table, aimed at the log instead. Two
 * separate things therefore have to be bounded, and bounding one is not bounding the other:
 *
 * <ul>
 *   <li><strong>How long a line can be.</strong> A bound is applied at the write site to
 *       <em>every</em> value that reaches {@code log}, counted in <strong>UTF-8 bytes</strong>
 *       rather than in {@code String.length()}: what is being defended is a disk, and one BMP
 *       non-ASCII character is three bytes where {@code length()} counts one — so a char-counted
 *       "~700 bytes" was a ~2.1 KB line in CJK, reachable because {@code URI.getPath()} decodes
 *       {@code %E4%B8%AD} and {@code script-sample} is a raw JSON string. It is the {@code @Size}
 *       versus BCrypt lesson (CLAUDE.md) applied to a log budget. Sum of the bounds:
 *       <strong>~700 bytes of field values</strong>, so ~1.2 KB on the wire once the field names
 *       and the logstash envelope are counted.</li>
 *   <li><strong>How many lines a sender can buy.</strong> {@link CspReportBudget} is spent
 *       <em>once per written line</em>, not once per request. One accepted request may deliver a
 *       batch, so a per-request budget of 600 was a bound of 600 × the batch size in lines — with
 *       every field bounded and no field unbounded, ~19 minimal reports fit one 16 KB body and the
 *       default ceiling still bought ~12,000 lines a minute. Charged per line, {@code 600/min}
 *       means 600 lines a minute — <strong>~700 KB a minute, about 1 GB a day</strong> at the
 *       instance ceiling, and ~70 KB a minute (~100 MB a day) from any one address at the per-IP
 *       default.</li>
 * </ul>
 *
 * <p>A new field here is a new bound, not a new {@code addKeyValue}; and a delivery that batches —
 * which is what {@code Reporting-Endpoints} does, in the enforcement ticket — costs the sender one
 * token per line without anybody revisiting this.
 *
 * <h2>The foreign-document filter, and the two things it does not establish</h2>
 * An unauthenticated report endpoint is a public log sink: anybody can point <em>their</em> site's
 * {@code report-uri} at ours and have every one of their visitors fill our journal with reports
 * about a page we do not serve. One host comparison closes that, and closes nothing else.
 *
 * <p>The earlier reading of it — that past the filter every URL in the line came from our own
 * origin — was false twice over, and both halves are why the bounds above are load-bearing rather
 * than belt-and-braces. The comparison is made against {@code document-uri} alone:
 * {@code source-file} is never checked against anything. And <strong>a host is not a
 * credential</strong> — it is satisfied by anybody who can type this installation's public address,
 * from anywhere, with {@code curl}. So the whole line is <strong>sender-asserted</strong>: bounded
 * and normalised rather than trusted, and it is evidence about what some browser reported, never
 * about which tenant was affected.
 */
@Slf4j
public class CspReportSink {

    /**
     * <strong>Every bound in this class is in UTF-8 BYTES</strong>, and each one below is the same
     * number its char-counted predecessor was, so nothing changes for the ASCII these fields
     * almost always carry — while a CJK, Cyrillic or emoji value is now cut at the size it
     * actually occupies rather than at three or four times it.
     *
     * <p>The browsers' own cap on {@code script-sample} is 40 <em>characters</em>; ours is 40
     * <em>bytes</em>, restated here because this value arrives from a sender that is only
     * <em>usually</em> a browser, and denominated in bytes because a log budget measured in a unit
     * other than the one the disk uses is not a budget. A non-ASCII sample is therefore cut
     * shorter than a browser would cut it, which is the deliberate half of the trade.
     */
    private static final int MAX_SAMPLE = 40;

    /** Enough to tell Chromium from Firefox from WebKit, which is the whole reason it is kept. */
    private static final int MAX_USER_AGENT = 120;

    /**
     * The bound on {@code csp.blocked}, applied to <strong>every</strong> value that field can take
     * and not only to the keyword ones ({@code inline}, {@code eval}, {@code data}, {@code self}).
     * Reducing a URL to scheme, host and path is not a bound: it drops the query and it reduces the
     * <em>host</em>, while the <em>path</em> is still written by the sender to whatever length the
     * sender likes. What a real blocked URI looks like is a cross-origin redaction to an origin
     * (~30 characters) or a same-origin asset path.
     */
    private static final int MAX_BLOCKED = 120;

    /**
     * The bound on {@code csp.document} and {@code csp.source}, applied after normalisation. The
     * longest route this SPA has is under 80 characters and no built asset path approaches this
     * even with an origin in front of it, so it is a ceiling on a sender rather than a limit
     * anything real meets.
     */
    private static final int MAX_PATH = 200;

    /**
     * The bound on {@code csp.line} — which is a line number, so a value that is not digits is
     * dropped entirely rather than truncated. An absent line number is a case every reader of these
     * lines already handles, and a 15 KB {@code "line-number"} is not a near-miss worth salvaging.
     * Digits are one byte each, so this is the one bound whose two units cannot disagree.
     */
    private static final int MAX_LINE = 12;

    /**
     * Everything a log writer might treat as a line, dropped from every value before it is bounded.
     * {@code URI.getPath()} <em>decodes</em>, so {@code %0D%0A} in a reported URL is a real CRLF by
     * the time it reaches {@code addKeyValue}, and {@code script-sample} is a raw JSON string that
     * can carry one directly. Both deployed profiles set
     * {@code logging.structured.format.console=logstash}, whose writer escapes these — but that
     * property is set nowhere else and is env-overridable, so a {@code local} run with the sink on,
     * or a Cloud box whose {@code LOGGING_STRUCTURED_FORMAT_CONSOLE} was cleared, would let an
     * anonymous sender forge whole log lines. This class's own doctrine is <em>bounded and
     * normalised rather than trusted</em>: the safety may not depend on which encoder is
     * configured. {@code \p{Cc}} is C0 and C1; U+2028/U+2029 are line separators no JSON writer has
     * to escape.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

    /** The shape of an id this product puts in a URL path: a UUID, canonically spelled. */
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** An issue number, a page index — anything positional. */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("[0-9]+");

    /**
     * The hosts for which the foreign-document filter drops every report a real browser could send.
     * {@code [::1]} is the spelling {@link URI#getHost()} returns for an IPv6 literal.
     */
    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /**
     * How many reports one request may deliver — a bound on the <em>work</em> one request can ask
     * for, and deliberately no longer the bound on log volume.
     *
     * <p>It used to be both, and it was never able to be: 20 lines per request against a budget
     * counted in requests is 20 × the budget in lines, so every volume figure this feature wrote
     * was a 20× understatement. Volume is now bounded where volume happens — {@link #budget} is
     * spent per written line — and what is left here is the cheap upper bound on how much parsing
     * and how many budget consultations one admitted request can cost. Twenty is past any real
     * {@code report-uri} delivery (which sends exactly one) and past a plausible
     * {@code Reporting-Endpoints} batch. The overflow is counted as {@code too_large}, which is
     * the same fact the body bound states: the sender sent more than this door accepts.
     */
    private static final int MAX_REPORTS_PER_REQUEST = 20;

    private final JsonMapper jsonMapper;
    private final ProductMetrics metrics;

    /**
     * Spent per <strong>written line</strong> rather than per request — see {@link #accept}. The
     * request's admission token, spent by {@link CspReportGuardFilter} before this class is
     * reached, pays for the first line; every further line costs one more.
     */
    private final CspReportBudget budget;

    /**
     * This instance's own host, from {@code app.base-url} — a property every install already sets,
     * and the only deployment-specific value anywhere in this feature (the policy itself has none).
     * <strong>Never empty</strong>: a base URL with no readable host fails the boot below, so
     * {@link #isOurs} always has something to compare against.
     */
    private final String ownHost;

    /**
     * <strong>A base URL with no host refuses the boot rather than switching the filter off.</strong>
     * This class only exists when {@code app.csp.sink-enabled} is true, so reaching the constructor
     * is an operator saying they want to collect — and the filter is the one thing standing between
     * an unauthenticated endpoint and reports about <em>any page on the internet, from anyone</em>.
     * Failing open there is a decision nobody made, announced in a warning nobody reads on the boot
     * where it matters. Both realistic spellings produce it: a missing scheme
     * ({@code tracker.example.com}, the likeliest typo because {@code .env.prod.example} is the only
     * thing that tells anyone to include one) and an underscore in the host
     * ({@code http://my_host:8080}), which {@link java.net.URI} rejects as a reg-name.
     *
     * <p>The loopback case below stays a <em>warning</em>, and the asymmetry is the point: that one
     * already fails closed — every real report is dropped as {@code foreign_document}, which is
     * useless but not unsafe — so it earns a line rather than a refusal. The neighbouring class does
     * the same thing for the same reason: {@code ContentSecurityPolicy} refuses a boot over a
     * report-uri aimed at a path this instance does not serve.
     */
    public CspReportSink(JsonMapper jsonMapper, ProductMetrics metrics, AppProperties appProperties,
                         CspReportBudget budget) {
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
        this.budget = budget;
        this.ownHost = ContentSecurityPolicy.hostOf(appProperties.baseUrl());
        if (ownHost.isEmpty()) {
            throw new IllegalStateException(
                    "app.base-url (" + appProperties.baseUrl() + ") has no host, so CSP reports "
                    + "cannot be checked against this instance's own origin and every report about "
                    + "every page on the internet would be accepted from anyone. Set APP_BASE_URL "
                    + "to this installation's public address, including the scheme "
                    + "(https://tracker.example.com) and without an underscore in the host, or set "
                    + "CSP_REPORT_SINK_ENABLED=false if this instance should not collect reports.");
        }
        if (LOOPBACK_HOSTS.contains(ownHost)) {
            log.warn("app.base-url ({}) is a loopback address and the CSP report sink is on, so "
                     + "every report a real browser sends will be dropped as foreign_document and "
                     + "this instance will collect nothing at all. Set APP_BASE_URL to this "
                     + "installation's public address.", appProperties.baseUrl());
        }
    }

    /**
     * A body that ran past the bound on a request that declared no length — the chunked case
     * {@link CspReportGuardFilter} cannot refuse cheaply. Counted identically, because it is the
     * same fact about the sender.
     */
    public void tooLarge() {
        metrics.cspReportDropped(CspDropReason.TOO_LARGE);
    }

    /**
     * Consumes one request body. Never throws: every failure is a counted drop, because the
     * endpoint answers {@code 204} either way and a sink that discards silently is a sink whose
     * silence cannot be read.
     *
     * <p><strong>The budget is charged per line written, not per body consumed.</strong> The
     * request already spent one token in {@link CspReportGuardFilter} to get here, and that token
     * pays for the first line; the second and every further line asks the budget again, so a batch
     * costs what it writes. Charged at the point of writing rather than per report, so a body full
     * of reports about somebody else's page costs its sender the admission token and nothing more —
     * the pot is a log-volume pot, and those produce no log volume.
     *
     * @param clientIp the key the guard filter already charged, so the two halves of one budget
     *                 cannot key differently.
     */
    public void accept(byte[] body, String userAgent, String clientIp) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            metrics.cspReportDropped(CspDropReason.UNPARSEABLE);
            return;
        }
        var reports = reportsIn(root);
        if (reports.isEmpty()) {
            metrics.cspReportDropped(CspDropReason.UNPARSEABLE);
            return;
        }
        int seen = 0;
        int written = 0;
        boolean outOfBudget = false;
        for (var report : reports) {
            if (seen++ >= MAX_REPORTS_PER_REQUEST) {
                metrics.cspReportDropped(CspDropReason.TOO_LARGE);
                continue;
            }
            var documentUri = text(report, "document-uri", "documentURL");
            if (!isOurs(documentUri)) {
                metrics.cspReportDropped(CspDropReason.FOREIGN_DOCUMENT);
                continue;
            }
            if (written > 0 && (outOfBudget || budget.spend(clientIp) > 0)) {
                // No Retry-After to carry: the request itself was admitted and is answered 204,
                // so the only witness of a batch cut short is this counter — which is why the
                // reason is the same `budget` an outright refusal raises.
                outOfBudget = true;
                metrics.cspReportDropped(CspDropReason.BUDGET);
                continue;
            }
            record(report, documentUri, userAgent);
            written++;
        }
    }

    /**
     * The report objects inside a body, in every envelope this endpoint accepts from day one: the
     * {@code report-uri} shape ({@code {"csp-report": {…}}}), a bare report object, a Reporting-API
     * entry ({@code {"type":"csp-violation","body":{…}}}), and an array of any of those. Written
     * this way now rather than later so that adding {@code Reporting-Endpoints} in the enforcement
     * ticket is a header change and not a rewrite of this class.
     */
    private static List<JsonNode> reportsIn(JsonNode root) {
        var found = new ArrayList<JsonNode>();
        if (root.isArray()) {
            for (var element : root) {
                unwrap(element).ifPresent(found::add);
            }
        } else {
            unwrap(root).ifPresent(found::add);
        }
        return found;
    }

    private static java.util.Optional<JsonNode> unwrap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return java.util.Optional.empty();
        }
        var wrapped = node.get("csp-report");
        if (wrapped == null) {
            wrapped = node.get("body");
        }
        var report = wrapped != null && wrapped.isObject() ? wrapped : node;
        return looksLikeAReport(report) ? java.util.Optional.of(report) : java.util.Optional.empty();
    }

    /**
     * A body is a report if it names a document or a directive. Deliberately generous — the point
     * of the check is to separate a report from an empty object or somebody else's JSON, not to
     * validate a schema the browsers themselves disagree about.
     */
    private static boolean looksLikeAReport(JsonNode report) {
        return text(report, "document-uri", "documentURL") != null
               || text(report, "effective-directive", "effectiveDirective") != null
               || text(report, "violated-directive", "violatedDirective") != null;
    }

    /** Writes the one line, having been told the document is ours and the budget allows it. */
    private void record(JsonNode report, String documentUri, String userAgent) {
        var reported = text(report, "effective-directive", "effectiveDirective");
        if (reported == null) {
            // Safari and older engines send only violated-directive, and it carries the source
            // list too ("img-src 'self'"), so the first token is the directive name.
            var violated = text(report, "violated-directive", "violatedDirective");
            reported = violated == null ? null : violated.strip().split("\\s+")[0];
        }
        var directive = CspDirective.of(reported);
        metrics.cspViolation(directive);

        log.atInfo()
                .addKeyValue("csp.directive", directive.tag())
                .addKeyValue("csp.blocked", blockedUri(text(report, "blocked-uri", "blockedURL")))
                .addKeyValue("csp.document", routeOf(documentUri))
                .addKeyValue("csp.source", sourceOf(text(report, "source-file", "sourceFile")))
                .addKeyValue("csp.line", lineNumber(text(report, "line-number", "lineNumber")))
                .addKeyValue("csp.sample", truncate(text(report, "script-sample", "sample"),
                        MAX_SAMPLE))
                .addKeyValue("csp.ua", truncate(userAgent, MAX_USER_AGENT))
                .log("Content-Security-Policy (report-only) violation reported by a browser");
    }

    /**
     * Whether the page that reported belongs to this instance. A report we cannot attribute to a
     * page we serve — a foreign host, an absent document, a value that is not a URL — is not a
     * report about us, and is dropped rather than logged.
     *
     * <p>Compared on <strong>host only</strong>, deliberately: scheme and port differ legitimately
     * between what an operator configured and what a browser saw (TLS terminated at a proxy, a
     * published port, {@code APP_BASE_URL} written without one), and a comparison that fired on
     * those would drop every real report while looking strict.
     *
     * <p>There is <strong>no branch for an unknown own host</strong>, and there used to be: it
     * returned {@code true}, so a base URL {@link java.net.URI} could not read a host from turned
     * this into an endpoint that accepted reports about any page on the internet, from anyone. The
     * constructor refuses that boot instead — the guarantee an unauthenticated filter needs is one
     * it cannot be configured out of.
     */
    private boolean isOurs(String documentUri) {
        return ownHost.equals(ContentSecurityPolicy.hostOf(documentUri));
    }

    /**
     * The blocked URI with its query and fragment removed, its identifier-shaped path segments
     * patterned, and bounded — on <em>every</em> branch. Browsers also send bare keywords here
     * ({@code inline}, {@code eval}, {@code data}, {@code self}) and redact cross-origin details to
     * an origin; those keep their shape, because they are not URLs and are exactly the value an
     * operator has to read.
     *
     * <p><strong>Scheme and host are kept, unlike the document</strong>: this field answers "which
     * resource was refused", and the answer to that names an origin. The <em>path</em> is patterned
     * like every other URL in the line, and that is not symmetry for its own sake — this is the one
     * field the foreign-document filter does not cover (it compares {@code document-uri} alone), so
     * a forged report, or an HD-176-shaped exfiltration URL that encodes ids in its path rather
     * than in its query, could otherwise write a tenant's identifiers into this line. Patterning
     * costs nothing real: only UUID-shaped and all-digit segments are replaced, so a CDN asset path
     * arrives intact.
     *
     * <p>Both fallbacks strip from the first {@code ?} or {@code #} by hand rather than returning
     * the raw value. A same-origin refusal is reported by several engines as a path-relative URI
     * ({@code /w/…/search?q=…}), which has neither scheme nor host and therefore lands here — with
     * a query this product may not log; a scheme-relative value ({@code //host/x?y}) does the same.
     */
    private static String blockedUri(String blocked) {
        return patternedUrl(blocked, MAX_BLOCKED);
    }

    /**
     * The source file, keeping <strong>scheme and host</strong> and patterning the path.
     *
     * <p><strong>The scheme is the attribution this field exists for.</strong> Reduced to its path,
     * {@code chrome-extension://<id>/content.js} logs as {@code /content.js} — indistinguishable
     * from our own bundle, and {@code moz-extension:} and {@code safari-web-extension:} the same.
     * Browser-extension injection is the dominant noise source in any real report-only stream and
     * the primary confounder of the {@code style-src} count, which this design calls its
     * highest-risk assumption and proposes to settle by <em>counting</em>. Nothing else in the line
     * can rescue that attribution: for an inline violation {@code blocked-uri} is the keyword
     * {@code inline}, so {@code blocked=inline, source=/content.js} reads exactly like our own code.
     *
     * <p>The host is patterned too, not merely the path: a UUID is a legal hostname, so a host kept
     * verbatim is the same minting vector as a path kept verbatim, one component to the left. That
     * also costs nothing real — a Firefox extension's host is a per-install random UUID which
     * correlates with nothing, and {@code moz-extension://{id}/content.js} still says <em>a
     * Firefox extension</em>, which is the whole question.
     */
    private static String sourceOf(String sourceFile) {
        return patternedUrl(sourceFile, MAX_PATH);
    }

    /**
     * A URL reduced to the <strong>route pattern</strong> its path matches — no host, no query, no
     * fragment, no identifier — and then bounded. The document alone drops its host, because the
     * host is the one thing about this field already known: it passed {@link #isOurs}.
     *
     * <p><strong>Why the pattern rather than the path.</strong> Every other line in this product
     * that names a workspace names one <em>the server resolved for an authenticated actor</em>.
     * This one would name one <em>the caller typed</em>, on a door that needs no account: anybody
     * who knows a workspace UUID could mint INFO lines attributed to that tenant at the per-IP
     * budget, and the field would stop being evidence about which tenant was affected — which is
     * the only thing it could have been for. Nothing diagnostic is lost, because the question these
     * lines answer is "which page", and which page is the pattern and not the instance.
     * {@code ProductMetrics} reduces a request URI to its mapped pattern for the same reason on the
     * same kind of value; this does it by <em>shape</em> rather than against a route table, so it
     * does not go stale the next time the SPA gains a route.
     *
     * <p>Applied to {@code source-file} as well as to {@code document-uri}, and not as symmetry for
     * its own sake: for an inline violation the engines report the <em>document</em> as the source
     * file, so that field carries the same identifiers exactly when it matters.
     */
    private static String routeOf(String uri) {
        return truncate(routePattern(pathOf(uri)), MAX_PATH);
    }

    /**
     * {@code scheme://host} — each part patterned — plus the patterned path, for a value that has
     * both; the patterned path alone for one that does not; and, for a value {@link URI} cannot
     * read at all, everything before the first {@code ?} or {@code #}, patterned. Bounded on every
     * branch: reducing a URL to an origin plus a path is not a bound, because the path is written
     * by the sender to whatever length the sender likes, and a well-formed URL was once the way
     * past a truncation that sat on the fallbacks only.
     */
    private static String patternedUrl(String value, int maxBytes) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var raw = value.strip();
        try {
            var uri = new URI(raw);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return truncate(routePattern(withoutQuery(raw)), maxBytes);
            }
            var path = uri.getPath() == null ? "" : uri.getPath();
            // The port belongs to the origin: an operator reading these lines on a self-hosted box
            // that publishes 8080 alongside a proxy needs to see which one the browser was on. It
            // is an int by the time URI has parsed it, so it carries no sender text.
            var port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return truncate(uri.getScheme() + "://" + routePattern(uri.getHost()) + port
                            + routePattern(path), maxBytes);
        } catch (URISyntaxException e) {
            return truncate(routePattern(withoutQuery(raw)), maxBytes);
        }
    }

    /** The path of a URL and nothing else — no host, no query, no fragment. */
    private static String pathOf(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            var path = new URI(uri.strip()).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Every identifier-shaped segment replaced by its placeholder; every literal left alone.
     *
     * <p><strong>A value with no {@code /} in it is patterned too</strong>, and the early return
     * that used to skip one was the guard's own hole: {@code source-file} is never host-checked, so
     * a bare UUID posted there parsed as a relative reference whose path is the whole string, took
     * the early return, and reached the log verbatim — and with an unparseable {@code app.base-url}
     * the same held for {@code document-uri}. The loop below handles a one-element split correctly,
     * so deleting the early return is strictly a widening.
     */
    private static String routePattern(String path) {
        if (path == null) {
            return null;
        }
        var segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (UUID_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = "{id}";
            } else if (!segments[i].isEmpty() && NUMERIC_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = "{n}";
            }
        }
        return String.join("/", segments);
    }

    /**
     * A line number, or nothing. Every engine sends this as a JSON number, but the sender is only
     * <em>usually</em> a browser — so anything that is not a short run of digits is dropped rather
     * than logged, which is what stops {@code "line-number"} from being a 16 KB field wearing a
     * numeric name. An absent line number is a case every reader of these lines already handles.
     */
    private static String lineNumber(String value) {
        if (value == null || value.length() > MAX_LINE) {
            return null;
        }
        return NUMERIC_SEGMENT.matcher(value).matches() ? value : null;
    }

    /** Everything before the first {@code ?} or {@code #}, for a value {@link URI} could not read. */
    private static String withoutQuery(String value) {
        int cut = value.length();
        for (var mark : new char[]{'?', '#'}) {
            int at = value.indexOf(mark);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return value.substring(0, cut);
    }

    /** The first present, non-blank value among the report's several spellings of one field. */
    private static String text(JsonNode report, String... names) {
        for (var name : names) {
            var value = report.get(name);
            // isValueNode, not merely non-null: a sender may put an object or an array where a
            // string belongs, and asString() on a container throws in Jackson 3 — which would turn
            // a malformed report into a 500 on an unauthenticated endpoint.
            if (value != null && value.isValueNode() && !value.isNull()) {
                var text = value.asString();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * <strong>Normalises and bounds one value: control characters dropped, then cut to
     * {@code maxBytes} UTF-8 bytes on a character boundary.</strong>
     *
     * <p>Counted in bytes because the budget it enforces is a disk. {@code String.length()} counts
     * UTF-16 units, so a 200-"character" bound admitted a ~600-byte path in CJK and a 40-character
     * sample was 160 bytes in emoji — the {@code @Size}-versus-encoder mismatch CLAUDE.md records
     * for BCrypt, arriving here as a log budget that was ~3× the figure written beside it.
     *
     * <p>Cutting on a byte index would split a multi-byte sequence, so the cut steps back off any
     * continuation byte. That also disposes of the surrogate half of the same problem for free: a
     * pair is one four-byte UTF-8 sequence, so this can never emit a lone surrogate for a strict
     * downstream JSON writer to reject.
     */
    private static String truncate(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        var clean = CONTROL_CHARACTERS.matcher(value).replaceAll("");
        var bytes = clean.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return clean;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
