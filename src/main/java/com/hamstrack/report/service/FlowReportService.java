package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.report.dto.FlowBucket;
import com.hamstrack.report.dto.FlowQuery;
import com.hamstrack.report.dto.FlowReportResponse;
import com.hamstrack.report.dto.FlowTotals;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.repository.FlowReportRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The flow report — created vs resolved over a window (reports-proposal §2.1), the first
 * slice of the reports epic and the place its conventions are defined.
 *
 * <h2>The conventions this class establishes</h2>
 * <ol>
 *   <li><strong>Tenancy first, always.</strong> {@code resolveProject} runs before the
 *       window is even looked at, so a non-member with a nonsensical window gets the same
 *       404 as a non-member with a perfect one. Validating first would turn the 400/404
 *       difference into an oracle for "does this project exist?" — the project's top bug
 *       class wearing a very ordinary hat.</li>
 *   <li><strong>Refuse, never clamp.</strong> A window wider than
 *       {@code app.reports.max-window-days} is a 400 that NAMES the cap and the window it
 *       measured. Quietly narrowing it would produce a chart that is correct about a
 *       question nobody asked, which is how a reporting feature earns
 *       <em>"these numbers don't match what I expected"</em> (§1.5). Same for
 *       {@code from > to} and for a date outside {@code FlowQuery.MIN_DATE..MAX_DATE}.
 *       <br>Note the one thing this rule does <em>not</em> forbid: choosing a
 *       <strong>default</strong> window the server can serve, when the caller asked for no
 *       window at all ({@link FlowQuery#withDefaultWindow(int)}).</li>
 *   <li><strong>Every response discloses its own provenance</strong> ({@link ReportMeta}) —
 *       when it was computed, how many issues it saw, how far its history goes, and whether
 *       a filter it was given matched nothing.</li>
 * </ol>
 *
 * <h2>No permission check, on purpose</h2>
 * Reports need project membership and nothing else — §4.2 argues it at length and the
 * decision is settled: this product has no read permissions at all ({@code Permission}'s
 * javadoc: "shipping a permission every role must have is dead code that lies"), and every
 * number here is derivable by any member from the search API they already hold, so a gate
 * would be theatre. The trigger for revisiting is recorded there: the day a per-assignee
 * breakdown, a workspace rollup or an export of somebody else's activity is proposed,
 * {@code report.view.people} lands in the same change. Nothing in this class is
 * per-person, and nothing in it may become per-person without that.
 *
 * <h2>Cost</h2>
 * Exactly three statements after resolution, whatever the project holds: two grouped
 * queries and one combined-counts query, all aggregated in PostgreSQL. Nothing here loops
 * over issues. {@code FlowReportQueryCountTest} pins that — and pins it across the
 * filtered/unfiltered split, which doubles the number of <em>methods</em> and not the number
 * of round trips. The rate at which those three statements may be asked for is bounded
 * separately, per principal, by {@code ReportRateLimiter}.
 */
@Service
@RequiredArgsConstructor
public class FlowReportService {

    private final WorkspaceAccessService workspaceAccess;
    private final FlowReportRepository flowReportRepository;
    private final ReportProperties reportProperties;

    @Transactional(readOnly = true)
    public FlowReportResponse flow(User actor, UUID workspaceId, UUID projectId, FlowQuery raw) {
        // 1. Tenancy before anything else. 404 for a missing workspace, a missing project
        //    and a non-member alike — never a 403, and never a 400 that would confirm the
        //    project exists to somebody who may not know that.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);

        // 2. Then the dates, BEFORE any arithmetic touches them: `to.plusDays(1)` on a
        //    parseable-but-absurd year is a DateTimeException, i.e. a 500 where the contract
        //    promises a 400.
        validateDateBand(raw);

        // 3. Only now default the window — the default depends on the configured cap, so it
        //    is applied here and not in the controller.
        var query = raw.withDefaultWindow(reportProperties.maxWindowDays());
        validateWindow(query);

        var scopedProjectId = ctx.project().getId();
        var unit = query.interval().sqlUnit();
        var start = query.startInclusive();
        var end = query.endExclusive();

        List<Object[]> createdRows;
        List<Object[]> resolvedRows;
        List<Object[]> countRows;
        if (query.hasFilters()) {
            var typeId = text(query.typeId());
            var componentId = text(query.componentId());
            var labelId = text(query.labelId());
            createdRows = flowReportRepository.createdPerBucketFiltered(
                    scopedProjectId, unit, start, end, typeId, componentId, labelId);
            resolvedRows = flowReportRepository.resolvedPerBucketFiltered(
                    scopedProjectId, unit, start, end, typeId, componentId, labelId);
            countRows = flowReportRepository.windowCountsFiltered(
                    scopedProjectId, start, end, typeId, componentId, labelId);
        } else {
            // The same three questions without the (CAST(:x AS uuid) IS NULL OR …) filters,
            // which a server-prepared generic plan cannot fold away — see FlowReportRepository.
            createdRows = flowReportRepository.createdPerBucket(scopedProjectId, unit, start, end);
            resolvedRows = flowReportRepository.resolvedPerBucket(scopedProjectId, unit, start, end);
            countRows = flowReportRepository.windowCounts(scopedProjectId, start, end);
        }

        var created = bucketCounts(createdRows);
        var resolved = bucketCounts(resolvedRows);
        var counts = countRows.isEmpty() ? null : countRows.get(0);

        long openBalance = counts == null ? 0 : number(counts[0]);
        long basedOnIssues = counts == null ? 0 : number(counts[1]);
        var firstIssueAt = counts == null ? null : instant(counts[2]);

        var buckets = new ArrayList<FlowBucket>();
        long totalCreated = 0;
        long totalResolved = 0;
        long open = openBalance;
        // Walk the window bucket by bucket rather than iterating what the database
        // returned: a bucket in which nothing happened has no row, and a series with holes
        // in it draws a chart that lies about the shape of the work. Bounded by
        // max-window-days (worst case 3651 buckets at interval=DAY).
        for (var bucket = query.interval().truncate(query.from());
             !bucket.isAfter(query.to());
             bucket = query.interval().next(bucket)) {
            long c = created.getOrDefault(bucket, 0L);
            long r = resolved.getOrDefault(bucket, 0L);
            totalCreated += c;
            totalResolved += r;
            open += c - r;
            buckets.add(new FlowBucket(bucket, c, r, open, isPartial(query, bucket)));
        }

        return new FlowReportResponse(
                query.from(), query.to(), query.interval(),
                List.copyOf(buckets),
                new FlowTotals(totalCreated, totalResolved, totalCreated - totalResolved),
                meta(basedOnIssues, firstIssueAt, unmatchedFilters(query, counts)));
    }

    // ------------------------------------------------------------------ window

    /**
     * The date band of {@link FlowQuery#MIN_DATE}..{@link FlowQuery#MAX_DATE}, refused in the
     * same refuse-and-name-it wording as the cap (round 2, item 3).
     *
     * <p>This runs <strong>before</strong> {@link #validateWindow} and before the default
     * window is derived, because both do date arithmetic: at
     * {@code to=+999999999-12-31} — which {@code ISO_DATE} parses happily — the half-open
     * window's own {@code plusDays(1)} overflows and throws, and a year merely outside
     * PostgreSQL's {@code timestamptz} fails later in the driver. Both used to be 500s.
     * {@code GlobalExceptionHandler} now maps {@code DateTimeException} to a 400 as a
     * backstop, but a backstop is not a message: the refusal a caller can act on is this one.
     */
    private void validateDateBand(FlowQuery query) {
        requireInBand(query.from(), "from");
        requireInBand(query.to(), "to");
    }

    private void requireInBand(LocalDate date, String name) {
        if (date == null || (!date.isBefore(FlowQuery.MIN_DATE) && !date.isAfter(FlowQuery.MAX_DATE))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid window: " + name + " (" + date + ") is outside the supported range "
                        + FlowQuery.MIN_DATE + " to " + FlowQuery.MAX_DATE + ". Reports cover "
                        + "issue history, which cannot predate the epoch, and a date past 2200 "
                        + "is a typo rather than a question.");
    }

    /**
     * The two 400s of §6, and the single most important behavioural rule in the spec: a
     * window this server will not serve is <strong>refused with the cap named</strong>, not
     * silently reduced to one it likes better.
     *
     * <p>Both messages state the numbers they measured, because the caller of a report API
     * is usually a chart that did the date arithmetic itself and needs to know which of the
     * two ends it got wrong.
     */
    private void validateWindow(FlowQuery query) {
        if (query.from().isAfter(query.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid window: from (" + query.from() + ") is after to (" + query.to() + ")");
        }
        long days = query.windowDays();
        int max = reportProperties.maxWindowDays();
        if (days > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Window is " + days + " days (" + query.from() + " to " + query.to()
                            + "); the maximum is " + max + " days (app.reports.max-window-days). "
                            + "Narrow the range — it is not clamped for you, because a report of a "
                            + "different window than the one you asked for is worse than no report.");
        }
    }

    // ------------------------------------------------------------------ disclosure

    /**
     * Whether a bucket covers less calendar than a full interval, because the requested
     * window opens or closes inside it (§2.1, round 2 item 7).
     *
     * <p>Computed from the same {@link com.hamstrack.report.dto.ReportInterval} that produced
     * the boundaries, so it cannot drift from them — which is the whole reason it is not left
     * to the client, where it would be a second implementation of Monday truncation.
     */
    private static boolean isPartial(FlowQuery query, LocalDate bucketStart) {
        var lastDayOfBucket = query.interval().next(bucketStart).minusDays(1);
        return bucketStart.isBefore(query.from()) || lastDayOfBucket.isAfter(query.to());
    }

    /**
     * Which of the supplied filters match no issue in this project at all — the difference
     * between "nothing happened in this window" and "your filter is broken", which look
     * identical on a chart of zeros.
     *
     * <p>Only ever names parameters the caller sent, and only ever reports on <em>this</em>
     * project's issues, so it discloses nothing about anything the caller cannot already see.
     * The names are the query parameters themselves, so a client can map an entry straight
     * back to the control the user touched.
     */
    private static List<String> unmatchedFilters(FlowQuery query, Object[] counts) {
        if (!query.hasFilters() || counts == null || counts.length < 6) {
            return List.of();
        }
        var unmatched = new ArrayList<String>(3);
        if (query.typeId() != null && !flag(counts[3])) {
            unmatched.add("typeId");
        }
        if (query.componentId() != null && !flag(counts[4])) {
            unmatched.add("componentId");
        }
        if (query.labelId() != null && !flag(counts[5])) {
            unmatched.add("labelId");
        }
        return List.copyOf(unmatched);
    }

    // ------------------------------------------------------------------ meta

    /**
     * The shared provenance block (§4.3).
     *
     * <p>{@code truncated} is <strong>false and honest</strong> here rather than
     * unimplemented. The flow report aggregates inside PostgreSQL and materialises one row
     * per bucket — at most {@code max-window-days} of them, three orders of magnitude under
     * {@code max-rows} — so the row cap physically cannot bite on this endpoint and there
     * is nothing it could be hiding. {@code cap} is still reported, because a client must
     * never have to guess which budget a report was measured against, and because the
     * row-level reports of R3 (cycle time, aging WIP) return the same block with the same
     * meaning and DO truncate.
     */
    private ReportMeta meta(long basedOnIssues, OffsetDateTime firstIssueAt,
                            List<String> unmatchedFilters) {
        return new ReportMeta(OffsetDateTime.now(ZoneOffset.UTC), basedOnIssues,
                false, reportProperties.maxRows(), firstIssueAt, unmatchedFilters);
    }

    // ------------------------------------------------------------------ plumbing

    private static Map<LocalDate, Long> bucketCounts(List<Object[]> rows) {
        var out = HashMap.<LocalDate, Long>newHashMap(rows.size());
        for (var row : rows) {
            out.put(date(row[0]), number(row[1]));
        }
        return out;
    }

    /**
     * A native {@code DATE} column comes back from Hibernate 7 as a {@link LocalDate}
     * (verified, not assumed — the legacy {@code java.sql.Date} mapping would fail this
     * cast loudly rather than quietly, which is the behaviour worth having if it ever
     * changes).
     */
    private static LocalDate date(Object raw) {
        return (LocalDate) raw;
    }

    /** {@code count(*)} is a {@code Long} in Hibernate 6/7 and a {@code BigInteger} before it. */
    private static long number(Object raw) {
        return ((Number) raw).longValue();
    }

    /**
     * A native {@code timestamptz} column, normalised to UTC. Accepts the two shapes a
     * driver/ORM combination can hand back — the modern {@link OffsetDateTime} and the legacy
     * {@link Timestamp} — rather than casting to one and turning a mapping change into a 500
     * on a disclosure field. Null (an empty project) stays null.
     */
    private static OffsetDateTime instant(Object raw) {
        return switch (raw) {
            case null -> null;
            case OffsetDateTime odt -> odt.withOffsetSameInstant(ZoneOffset.UTC);
            case Instant i -> i.atOffset(ZoneOffset.UTC);
            case Timestamp ts -> ts.toInstant().atOffset(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "unexpected timestamp type from the report query: " + raw.getClass());
        };
    }

    /** A native {@code boolean} column. */
    private static boolean flag(Object raw) {
        return raw instanceof Boolean b && b;
    }

    /**
     * Nullable filter ids travel to the native queries as text — see
     * {@link FlowReportRepository}'s note on typing a NULL bind parameter for PostgreSQL.
     */
    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
