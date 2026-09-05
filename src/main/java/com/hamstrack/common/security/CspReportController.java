package com.hamstrack.common.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * <strong>The collection half of the report-only Content-Security-Policy</strong> (HD-264):
 * {@code POST /api/security/csp-report}, unauthenticated, bounded, budgeted, persisting nothing,
 * answering {@code 204} always.
 *
 * <h2>Unauthenticated by necessity, and safe because it can only ingest</h2>
 * A browser sends a violation report with no credentials, so requiring any is requiring a report
 * nobody can send. It is not workspace-scoped and cannot be: a report is emitted by a browser
 * rather than by a session, and it carries no workspace identity that could be trusted if it did.
 * What keeps that safe is that the endpoint <strong>accepts and returns nothing tenant-derived</strong>
 * — there is no read path here at all, so this product's top bug class has nothing to leak through
 * it. Every bound on it is therefore about volume rather than about authorisation, and they live
 * one layer out in {@link CspReportGuardFilter}.
 *
 * <h2>204 always, including for a body that was thrown away</h2>
 * A filtered report, an unparseable body and a perfectly good violation are answered identically.
 * There is no information a report sender is entitled to, and a discriminating response is a free
 * oracle about the instance's state — including, on a foreign-origin probe, an answer to "is this
 * sink watching?". The {@code 4xx}s the contract does declare all happen <em>before</em> this
 * method, in the security chain, the mapping or the filter, and carry no body either:
 * {@code 401} for any method but {@code POST} (the anonymous exemption is method-scoped, so
 * everything else on this path stays inside the {@code /api/**} authenticated set — the proposal
 * predicted {@code 405} and this is the tighter answer), {@code 415} for another content type,
 * {@code 413} over the <em>declared</em> length, {@code 429} over the budget. With the sink off the
 * dispatcher answers {@code 405}, because the SPA fallback maps every dotless path for {@code GET}.
 * The {@code 413} and the {@code 429} carry no body at all; the {@code 415} carries Boot's problem
 * document, because it is the mapping's refusal rather than the filter's.
 *
 * <h2>The body is read here rather than bound</h2>
 * Two reasons, and both are about the contract above. Binding it (an {@code @RequestBody} of any
 * shape) hands a malformed body to the message converters, which answer {@code 400} — an outcome
 * this endpoint may not have, and one that would be a needless oracle besides. And this endpoint's
 * accepted envelopes are three ({@code {"csp-report": …}}, a bare report, an array), so what it
 * needs is a tree it can inspect, not a type. {@link CspReportSink} owns everything past the bytes.
 *
 * <p><strong>No {@code @Validated} on this class</strong>, per ADR-0018: it is a bean Spring MVC
 * dispatches to, and that annotation would suppress MVC's own method validation. There is nothing
 * declared here for it to validate in any case — the bounds are the filter's and the properties
 * class's.
 *
 * <p><strong>It exists only where the sink does.</strong> On DC the property defaults to false and
 * no handler is registered — while the header, by the same property, names no {@code report-uri}.
 * The two halves are one condition on purpose: that is what makes the proposal's "a
 * {@code report-uri} that 404s" unreachable by configuration rather than by care, and the one way
 * an operator could still have written them apart — {@code CSP_POLICY} naming this path with the
 * sink off — is refused at startup by {@code ContentSecurityPolicy}.
 *
 * <p>What the unserved path actually answers is <strong>{@code 405}, not {@code 404}</strong>, and
 * the distinction is worth a clause because {@code 404} is the intuitive answer and the next reader
 * will otherwise "correct" this back: {@code SpaController} maps
 * {@code /}{@code {path:^(?!assets$|actuator$)[^\.]*}/**} for {@code GET}, so a dotless path that
 * does not begin {@code /assets} or {@code /actuator} is always <em>mapped</em> and a {@code POST}
 * to it is a method refusal. It is the same answer to the only question that matters — no handler of ours runs, and
 * nothing is collected.
 */
@RestController
@ConditionalOnProperty(name = "app.csp.sink-enabled", havingValue = "true")
@RequiredArgsConstructor
public class CspReportController {

    private final CspReportSink sink;

    /**
     * @param request read for its input stream and its {@code User-Agent} only. Nothing else about
     *                the request reaches the log, and in particular the caller's IP does not: it is
     *                the budget's key and not a field, because a log line pairing an address with
     *                the page it was on is a browsing record.
     */
    @PostMapping(
            path = ContentSecurityPolicy.REPORT_PATH,
            consumes = {"application/csp-report", "application/json", "application/reports+json"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(HttpServletRequest request) throws IOException {
        // Bounded here as well as in the filter: the filter refuses on the DECLARED length, which a
        // chunked request does not have. Reading one byte past the bound is what distinguishes "at
        // the bound" from "over it" without a second pass.
        byte[] body = request.getInputStream()
                .readNBytes(CspReportGuardFilter.MAX_BODY_BYTES + 1);
        if (body.length > CspReportGuardFilter.MAX_BODY_BYTES) {
            sink.tooLarge();
            return;
        }
        // The key the guard filter already charged, so the per-request admission token and the
        // per-line charges the sink makes are spent on one and the same pot. Falling back to the
        // peer address keeps this handler honest if it is ever reached without the filter: the
        // budget is then keyed slightly differently, which is better than not being spent.
        var clientIp = request.getAttribute(CspReportGuardFilter.CLIENT_IP_ATTRIBUTE);
        sink.accept(body, request.getHeader(HttpHeaders.USER_AGENT),
                clientIp == null ? request.getRemoteAddr() : String.valueOf(clientIp));
    }
}
