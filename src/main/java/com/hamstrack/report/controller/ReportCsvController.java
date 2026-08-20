package com.hamstrack.report.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.report.csv.ReportCsvDocument;
import com.hamstrack.report.dto.CycleTimeQuery;
import com.hamstrack.report.dto.FlowQuery;
import com.hamstrack.report.dto.ReportInterval;
import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.service.ReportCsvService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code GET …/reports/{report}.csv} — the CSV export of each report (HD-141 R7, section 4.4).
 *
 * <p><strong>What is exported is the plotted series, one row per data point</strong>, with a
 * comment header carrying project, window, measure, {@code computedAt} and {@code basedOnIssues}.
 * It is deliberately <em>not</em> "the issues behind the chart": section 1.6 found that a flat
 * issue list is what every product with this button actually ships, and that it is the documented
 * disappointment — people ask for the chart and get a spreadsheet of issues. "Download matching
 * issues" is a separate, separately-labelled thing that hands off to the search export. If one
 * endpoint here can ever satisfy both, the cheap one wins and the chart export is gone.
 *
 * <p>Every parameter, default, refusal and status code is the JSON endpoint's, because every
 * handler here delegates to the same service — see {@link ReportController} for the full list and
 * for the bind-versus-range distinction that decides whether a bad request is a 400 or a 404.
 * Nothing about being a download changes any of them.
 *
 * <p><strong>A CSV endpoint is the shape a return-type guard cannot see, and this class is
 * arranged around that.</strong> {@code VelocityRefusalTest} finds handlers that serve velocity by
 * resolving their return type to a velocity record; these return {@code ResponseEntity<String>},
 * which mentions none. That test therefore carries a second, path-based net — a handler whose path
 * contains "velocity" must sit under {@code /projects/{projectId}} — and the paths below are
 * spelled out one per report <strong>so that net can see them</strong>. A single generic
 * {@code @GetMapping("/{report}.csv")} would have been shorter and would have been invisible to
 * both nets at once: its path says nothing and its return type says nothing, so a workspace-level
 * copy of it could union two projects in silence. Six literals is the price of staying inside a
 * guard that already exists. (It also keeps each handler's parameters exactly its report's, rather
 * than a union that accepts {@code interval} on {@code velocity.csv}.)
 *
 * <p><strong>Throttled by the same path binding as the JSON reports</strong>
 * ({@code ReportRateLimitConfig.REPORTS_PATH}, {@code /api/workspaces/*}{@code /projects/*}{@code
 * /reports/**}), which already matches {@code …/reports/velocity.csv} — so no new pattern is
 * registered and the sealed-set assertion in {@code ThrottleCoverageTest} stays true. The coverage
 * half of that test probes every handler in {@code com.hamstrack.report.controller} against the
 * real handler mapping, so these six are checked rather than assumed to have inherited anything.
 *
 * <p><strong>Content type and headers.</strong> {@code text/csv;charset=UTF-8} with
 * {@code Content-Disposition: attachment} and a server-built filename
 * ({@code DEMO-velocity-2026-08-20.csv}) — never a client-supplied one. The same
 * {@code Cache-Control: private, max-age=60} and {@code Vary: Authorization} as the JSON reports:
 * a download is still a live read over one tenant's data, and the {@code Vary} is what stops a
 * shared-profile browser replaying a cached file to a different bearer token. Spring Security's
 * default {@code X-Content-Type-Options: nosniff} already covers the sniffing angle, so it is not
 * repeated here.
 *
 * <p><strong>{@code produces} is stated, and that has one consequence worth knowing.</strong> A
 * client that sends {@code Accept: application/json} gets a 406 rather than a CSV body labelled as
 * JSON. Error responses are unaffected: Spring clears the producible-media-type attribute before
 * the exception resolver runs, so a 404 or a 429 on one of these paths still comes back as a
 * problem detail — pinned by {@code ReportCsvApiTest} rather than trusted.
 *
 * <p><strong>That holds even for a caller that pins {@code Accept: text/csv}</strong>, which is
 * what a {@code curl} download script naturally sends and is the one case the clearing above
 * looks like it should break: with the producible types gone, the refusal would be negotiated
 * against the request's {@code Accept}, and a {@code ProblemDetail} is only written as JSON.
 * It does not, because the problem-detail response sets {@code Content-Type:
 * application/problem+json} explicitly and {@code AbstractMessageConverterMethodProcessor} takes a
 * concrete content type already on the response as the selected media type rather than
 * negotiating. So a curl user gets the same stated reason the SPA does — which is a happy accident
 * of that ordering rather than something this class asks for, hence
 * {@code ReportCsvApiTest.aRefusalReachesAClientThatPinsAcceptTextCsvToo}: an empty 406 is the
 * blank-banner failure in a different client, and it would be silent.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/reports")
@RequiredArgsConstructor
public class ReportCsvController {

    /** Matches {@link ReportController}'s; a download is cached exactly as its JSON is. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private static final MediaType TEXT_CSV =
            new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportCsvService reportCsvService;

    /** The flow series: one row per bucket. Parameters and defaults are {@link FlowQuery}'s. */
    @GetMapping(value = "/flow.csv", produces = "text/csv")
    public ResponseEntity<String> flow(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ReportInterval interval,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID componentId,
            @RequestParam(required = false) UUID labelId) {
        return csv(reportCsvService.flow(actor, workspaceId, projectId,
                new FlowQuery(from, to, interval, typeId, componentId, labelId)));
    }

    /**
     * The cycle-time scatter: one row per plotted point, which is one row per completed issue —
     * with {@code cycleDays} empty wherever no start exists, never backfilled from
     * {@code created_at}.
     */
    @GetMapping(value = "/cycle-time.csv", produces = "text/csv")
    public ResponseEntity<String> cycleTime(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) UUID componentId,
            @RequestParam(required = false) UUID labelId) {
        return csv(reportCsvService.cycleTime(actor, workspaceId, projectId,
                new CycleTimeQuery(from, to, typeId, componentId, labelId)));
    }

    /**
     * The aging columns, flattened: one row per open issue, carrying the column it sits in. Takes
     * no parameters, exactly as the JSON does — a current-state report has no window to state.
     */
    @GetMapping(value = "/aging.csv", produces = "text/csv")
    public ResponseEntity<String> aging(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {
        return csv(reportCsvService.aging(actor, workspaceId, projectId));
    }

    /**
     * The burn-up series: one row per day, {@code scope} and {@code completed}. The scope log is a
     * different shape and is not folded in — the header counts it and points at the JSON endpoint,
     * because two tables in one CSV is a file no spreadsheet opens correctly.
     */
    @GetMapping(value = "/sprint-burnup.csv", produces = "text/csv")
    public ResponseEntity<String> sprintBurnup(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID sprintId,
            @RequestParam(required = false) ReportMeasure measure) {
        return csv(reportCsvService.sprintBurnup(actor, workspaceId, projectId, sprintId, measure));
    }

    /**
     * The review record: one row per issue per list, with a {@code list} column. The same key
     * appears in more than one list, which is what the record shows on screen.
     */
    @GetMapping(value = "/sprint-review.csv", produces = "text/csv")
    public ResponseEntity<String> sprintReview(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID sprintId) {
        return csv(reportCsvService.sprintReview(actor, workspaceId, projectId, sprintId));
    }

    /**
     * The velocity bars: one row per completed sprint, and the band in the header (or the reason
     * it is suppressed).
     *
     * <p><strong>Its columns are exactly {@code VelocitySprint}'s and may not grow a per-person
     * one.</strong> The refusals of section 1.4 are the report, and the tests that pin them are
     * keyed on the JSON record — which this handler does not return. Two things put it back inside
     * that net: the path says "velocity", so the path-based half of {@code VelocityRefusalTest}
     * catches this handler and asserts it is served from below a project; and
     * {@code ReportCsv.VELOCITY_COLUMNS} is asserted to be a subset of the record's components, so
     * a column the JSON refuses cannot appear here either. Neither is decoration — a
     * workspace-level {@code velocity.csv} is named in section 2.5 as the exact artefact that
     * would delete the mitigation and keep the metric.
     */
    @GetMapping(value = "/velocity.csv", produces = "text/csv")
    public ResponseEntity<String> velocity(
            @AuthenticationPrincipal User actor,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer sprints,
            @RequestParam(required = false) ReportMeasure measure) {
        return csv(reportCsvService.velocity(actor, workspaceId, projectId, sprints, measure));
    }

    private static ResponseEntity<String> csv(ReportCsvDocument document) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.filename())
                                .build()
                                .toString())
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION)
                .body(document.body());
    }
}
