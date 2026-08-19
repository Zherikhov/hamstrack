package com.hamstrack.report.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.report.dto.AgingReportResponse;
import com.hamstrack.report.dto.CycleTimeQuery;
import com.hamstrack.report.dto.CycleTimeReportResponse;
import com.hamstrack.report.dto.FlowQuery;
import com.hamstrack.report.dto.FlowReportResponse;
import com.hamstrack.report.dto.ReportInterval;
import com.hamstrack.report.service.AgingReportService;
import com.hamstrack.report.service.CycleTimeReportService;
import com.hamstrack.report.service.FlowReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Project reports (epic HD-5). Read-only, project-scoped, and — by the decision argued in
 * reports-proposal §4.2 — <strong>gated by project membership and nothing else</strong>.
 * There is no {@code report.view} permission and there is not meant to be one: this
 * product has no read permissions, and every number these endpoints return is derivable by
 * any member from the search API they already hold, so a gate here would protect nothing
 * while being the first read gate in the codebase. The recorded trigger for revisiting is
 * the day a per-assignee breakdown or a cross-project rollup is proposed.
 *
 * <p><strong>Tenancy:</strong> every endpoint resolves through
 * {@code WorkspaceAccessService.resolveProject} <em>before</em> it validates anything else,
 * so a missing workspace, a missing project and a non-member all answer <strong>404</strong>
 * — never a 403, and never a 400 that would confirm the project exists.
 *
 * <p><strong>Status codes</strong> (§4.3, and no status code here depends on a delivery
 * capability — Rule A):
 * <ul>
 *   <li>{@code 200} — the report, always carrying a {@code meta} block;</li>
 *   <li>{@code 400} — {@code from > to}, a window wider than
 *       {@code app.reports.max-window-days} (the detail names the cap; the window is
 *       <strong>never</strong> silently clamped), a date outside the supported band
 *       ({@code FlowQuery.MIN_DATE}..{@code MAX_DATE} — an extreme year parses but cannot be
 *       computed with), or an unparseable date/interval;</li>
 *   <li>{@code 400} — also, on <em>every</em> endpoint here including the parameterless one,
 *       a path id that is not a UUID: that is a {@code MethodArgumentTypeMismatchException}
 *       raised while binding, before any handler method runs. It discloses nothing (a
 *       malformed id names no resource, so there is no existence to confirm) and it is not
 *       specific to reports, but it belongs in a list the next reader will trust;</li>
 *   <li>{@code 401} — anonymous;</li>
 *   <li>{@code 404} — workspace or project not visible to the caller;</li>
 *   <li>{@code 429} — past this caller's report budget, with {@code Retry-After};</li>
 *   <li>{@code 500} — a broken install: a project whose effective workflow cannot be
 *       resolved (no system default workflow) fails the same way on every project endpoint
 *       in the product, not just here. Stated so the list is what can actually happen
 *       rather than what was designed to happen.</li>
 * </ul>
 *
 * <p>Responses are {@code Cache-Control: private, max-age=60} <strong>and
 * {@code Vary: Authorization}</strong>: a report is a live read over one tenant's data, so it
 * is cacheable by the browser that asked and by nothing else. The {@code Vary} is not
 * decoration — a browser's HTTP cache keys on (method, URL) and <em>ignores request headers
 * unless {@code Vary} names them</em>, so without it a shared-profile browser could replay a
 * cached 200 to a different bearer token inside the 60 seconds, one the server would have
 * answered 404. The SPA's query {@code staleTime} is expected to match the max-age.
 *
 * <p><strong>Subject ids resolve; filter ids do not.</strong> The rule for every report on
 * this base path, stated here because R1 and R4 sit on opposite sides of it:
 * <blockquote>A report's <strong>subject</strong> id resolves through its parent and 404s. A
 * report's <strong>narrowing filter</strong> id is never resolved: it is applied as an
 * equality inside an already project-scoped statement, where it can only subtract.</blockquote>
 * So {@code /flow}'s {@code typeId}/{@code componentId}/{@code labelId} — filters — narrow to
 * an empty series when they name nothing, and R4's {@code sprintId} — the report's subject —
 * resolves through the project and 404s for another project's sprint. The reason a filter is
 * not resolved is <em>not</em> "resolving it would leak another tenant's taxonomy": a lookup
 * scoped to this project answers identically for a foreign id and a nonexistent one, so it
 * would leak nothing. The reasons are better than that. A filter is a predicate, not an
 * addressed resource, so the 404-on-unknown-id convention is a category error applied to it;
 * and resolving three optional ids would add three statements to a report that spends three
 * in total. What the caller gets instead is a disclosure: {@code meta.unmatchedFilters} names
 * any filter that matched nothing in this project, so an empty chart is never ambiguous.
 *
 * <p><strong>Throttled per principal</strong> ({@code ReportRateLimiter},
 * {@code app.reports.requests-per-minute}), on this whole base path rather than per endpoint,
 * so every later report inherits the budget instead of remembering to ask for one. Past it
 * the answer is 429 + {@code Retry-After} — a report is never narrowed or approximated to fit
 * a budget.
 *
 * <p>{@code /flow} (R1), {@code /cycle-time} and {@code /aging} (R3) exist so far. The rest of
 * §4.3 — sprint burn-up, sprint review, velocity and the {@code .csv} variants — land in later
 * slices on this same base path and reuse this class's conventions.
 *
 * <p>R3 adds one distinction to the list above rather than a new rule: <strong>not every report
 * has a window</strong>. {@code /aging} answers a current-state question and therefore takes no
 * parameters at all, so it is the only endpoint here with <strong>no 400 of its own</strong> —
 * there is nothing for a caller to state wrongly, and an unknown query parameter is ignored
 * rather than refused. It is <em>not</em> "the endpoint that cannot return a 400", which is what
 * this note said until round 2 and is not true: a malformed path UUID 400s here as it does
 * everywhere, before the handler runs. That is a property of the question, not an omission — see
 * the method.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reports")
@RequiredArgsConstructor
public class ReportController {

    /** Matches the SPA's report {@code staleTime}; see {@code Cache-Control} above. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final FlowReportService flowReportService;
    private final CycleTimeReportService cycleTimeReportService;
    private final AgingReportService agingReportService;

    /**
     * {@code GET …/reports/flow} — created vs resolved, bucketed on UTC day/week
     * boundaries, plus the open-count line and the window totals (§2.1).
     *
     * <p>Every parameter is optional and every default lives in {@link FlowQuery}: no
     * parameters at all means "the last 90 days, weekly, unfiltered" — or the last
     * {@code app.reports.max-window-days} days if that is under 90, so the endpoint's own
     * default request can never be refused by the endpoint's own cap. {@code from} and
     * {@code to} are ISO dates and the window includes both endpoints.
     *
     * <p>The three filters are ordinary equality filters on the project's own data, and an
     * id that names nothing simply yields an empty series rather than a 404 or a 422 — see
     * the subject-vs-filter rule on this class. {@code meta.unmatchedFilters} says which
     * filter matched nothing, so a chart of zeros is never ambiguous.
     */
    @GetMapping("/flow")
    public ResponseEntity<FlowReportResponse> flow(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ReportInterval interval,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID componentId,
            @RequestParam(required = false) UUID labelId) {
        var report = flowReportService.flow(actor, workspaceId, projectId,
                new FlowQuery(from, to, interval, typeId, componentId, labelId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                // The credential is part of the cache key or `private` is a promise the
                // browser was never told how to keep — see the class javadoc.
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(report);
    }

    /**
     * {@code GET …/reports/cycle-time} — the finished-work half of the cycle time page (§2.2):
     * one entry per issue completed inside the window, plus the p50/p85 of both measures.
     *
     * <p>The window is a range on <strong>{@code closed_at}</strong> and follows the same rules as
     * {@code /flow} down to the wording of its refusals (they are one implementation —
     * {@code ReportWindow}). The three filters behave identically too: an id that names nothing
     * narrows the report to an empty series and is reported in {@code meta.unmatchedFilters},
     * rather than 404ing.
     *
     * <p>Two fields exist because the data is honest about a gap: {@code sampleSize} is the
     * completed issues in the window and {@code missingStartCount} is how many of them have no
     * {@code started_at} — they contribute to lead time and to nothing else, because
     * {@code created_at} is never substituted for a missing start. Below five samples the
     * matching percentile pair comes back as nulls instead of noise.
     */
    @GetMapping("/cycle-time")
    public ResponseEntity<CycleTimeReportResponse> cycleTime(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID componentId,
            @RequestParam(required = false) UUID labelId) {
        var report = cycleTimeReportService.cycleTime(actor, workspaceId, projectId,
                new CycleTimeQuery(from, to, typeId, componentId, labelId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(report);
    }

    /**
     * {@code GET …/reports/aging} — the unfinished-work half (§2.2): a column per non-DONE status
     * of the project's effective workflow, each holding its open issues oldest-first, with the
     * cycle-time p50/p85 of the project's completed work to draw across them.
     *
     * <p><strong>No parameters, and no window — deliberately.</strong> "Which open item is rotting
     * right now" is a question about current state; a window on it would either be meaningless
     * (every open issue is open today) or actively misleading (a window on {@code created_at}
     * hides exactly the oldest items, which are the entire point). This is therefore the one
     * endpoint on this base path with <strong>no 400 of its own</strong>: it validates nothing,
     * because there is nothing here for a caller to get wrong, and a stray query parameter is
     * ignored rather than refused. Its failures are 401, 404 and 429 — plus the two that belong
     * to every endpoint rather than to this one: a 400 from binding a malformed path UUID, and a
     * 500 on an install with no system default workflow. See the class note.
     *
     * <p>An issue whose status has left the workflow appears in a trailing "Not on this board"
     * column ({@code statusId: null}) rather than vanishing from the report.
     */
    @GetMapping("/aging")
    public ResponseEntity<AgingReportResponse> aging(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {
        var report = agingReportService.aging(actor, workspaceId, projectId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(report);
    }
}
