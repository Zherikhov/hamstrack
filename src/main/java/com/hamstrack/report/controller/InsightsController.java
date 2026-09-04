package com.hamstrack.report.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.report.dto.InsightsRequest;
import com.hamstrack.report.dto.InsightsResponse;
import com.hamstrack.report.service.InsightsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code POST /api/workspaces/{wsId}/search/insights} — the Insights panel (reports-proposal §2.6,
 * §4.3), the replacement for the configurable gadget dashboard this epic refuses (§1.5).
 *
 * <p><strong>The odd one out in this package, in three ways, each deliberate.</strong>
 *
 * <h2>1. It is workspace-scoped, not project-scoped</h2>
 * Every other report lives under {@code /projects/{projectId}} because it answers a question about
 * one project's process. This one answers a question about <em>a query somebody typed</em>, and
 * that query already spans whatever the caller can see. So it sits on the search base path,
 * membership is {@code requireMember} (404 for a missing workspace and for a non-member alike —
 * never 403), and the row boundary is {@code SearchScope}, reached through the same compiled
 * predicate the search page itself runs. This is <em>not</em> the workspace-level rollup §9 OQ 4
 * defers, and it is not a crack in §2.5's refusal of cross-team velocity: an ad-hoc breakdown a
 * person ran is a different object from a published metric with a stable URL. See
 * {@code InsightsService} and {@code InsightsDimension}.
 *
 * <h2>2. It is a POST, which changes what caching can mean</h2>
 * The dataset is an HQL query with a 2 000-character budget, so it is a body rather than a query
 * string. That has a consequence worth stating instead of leaving to be discovered: <strong>a
 * POST response is not served from an HTTP cache</strong>. Browsers do not cache POST responses,
 * and RFC 9111's narrow allowance for caching one requires an explicit {@code Content-Location}
 * that no client here sends. So {@code Cache-Control: private, max-age=60} and
 * {@code Vary: Authorization} are carried for <em>consistency and defence</em>, not for effect:
 * they match the rest of the reports surface, and they guarantee that if this ever becomes a GET
 * — or if an intermediary decides to cache it anyway — the response is already marked
 * uncacheable-by-anyone-else and keyed on the credential. The real 60-second de-duplication is the
 * SPA's TanStack Query {@code staleTime}, which is where it has to be for a POST, and matching the
 * two numbers keeps the panel's refresh behaviour identical to every other report.
 *
 * <p>The security half of {@code Vary} is not decoration even so: a browser HTTP cache keys on
 * (method, URL) and <em>ignores request headers unless {@code Vary} names them</em>, so on a
 * shared-profile browser a cached 200 could otherwise be replayed to a different bearer token.
 *
 * <h2>3. Its refusals are 422, not 400</h2>
 * {@code ReportController}'s parameters are bound by Spring from a query string, so a bad
 * {@code measure} there is a binding failure — a 400 raised before any handler runs. Here the
 * parameters arrive inside a JSON body and are resolved in the service, so an unknown
 * {@code measure}/{@code slice}/{@code segment} is a <strong>422 that names the parameter and
 * lists what it accepts</strong> — which is also what every other refusal under {@code /search}
 * looks like (parse errors carry a position, semantic errors carry a field), and what the SPA's
 * error surface is built to render.
 *
 * <p><strong>Status codes:</strong>
 * <ul>
 *   <li>{@code 200} — the breakdown, always carrying a {@code meta} block;</li>
 *   <li>{@code 400} — a malformed body, an oversized {@code query} (&gt; 2 000 chars) or an
 *       oversized {@code measure}/{@code slice}/{@code segment} (&gt; 32 chars — those three are
 *       echoed back inside their own 422, so they are bounded before anything can copy them), or
 *       a path id that is not a UUID: all raised while binding, before the handler runs, so they
 *       disclose nothing about the workspace;</li>
 *   <li>{@code 401} — anonymous;</li>
 *   <li>{@code 404} — workspace missing, or the caller is not a member. The two are
 *       indistinguishable on purpose;</li>
 *   <li>{@code 422} — an unparseable HQL query (with a highlight position), a semantic HQL error
 *       (with the field), an unknown measure/slice/segment, or a segment equal to the slice;</li>
 *   <li>{@code 429} — past a budget, with {@code Retry-After}. This endpoint spends TWO: the
 *       reports budget it shares with the project reports (one pot for the whole reports
 *       surface, so alternating between a chart and the panel does not double an allowance) and
 *       the search budget it shares with {@code POST …/search} by living on that path. The
 *       lower configured value binds — which of the two that is depends on how the deployment
 *       set them, so neither is named here. It is inside the occupancy bound as well, where
 *       sitting on two limiters costs it ONE place in flight rather than two: one request, one
 *       connection, one permit. That refusal carries an {@code errorType} and the budgets do
 *       not, which is what a client branches on.</li>
 * </ul>
 * <strong>No status code here depends on a delivery capability</strong> (Rule A). The
 * {@code SPRINT} slice and the {@code POINTS} measure are omitted from what
 * {@code /search/schema} <em>suggests</em> when no visible project has the capability on, and both
 * still answer 200 when asked for anyway.
 *
 * <p>A separate {@code @RestController} rather than a method on {@code SearchController} because
 * this is a report: it carries the reports family's {@code meta} block, its caps, its cache
 * headers and its throttle, and keeping those conventions in one package is what has kept them
 * consistent across six slices.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/search")
@RequiredArgsConstructor
public class InsightsController {

    /** Matches the SPA's report {@code staleTime}; see the class javadoc on what it does not do. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final InsightsService insightsService;

    @PostMapping("/insights")
    public ResponseEntity<InsightsResponse> insights(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody InsightsRequest request) {
        var report = insightsService.insights(actor, workspaceId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(report);
    }
}
