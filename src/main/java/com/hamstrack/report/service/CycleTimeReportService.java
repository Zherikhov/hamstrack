package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.report.dto.CycleTimeItem;
import com.hamstrack.report.dto.CycleTimePercentiles;
import com.hamstrack.report.dto.CycleTimeQuery;
import com.hamstrack.report.dto.CycleTimeReportResponse;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.dto.ReportPercentiles;
import com.hamstrack.report.dto.ReportWindow;
import com.hamstrack.report.repository.CycleTimeReportRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The finished-work half of the cycle time page (reports-proposal §2.2) — how long work takes,
 * as a scatter of individual issues plus two percentile lines.
 *
 * <p>Every convention here is R1's ({@code FlowReportService}) and is not re-derived: tenancy is
 * resolved <em>before</em> the window is looked at, so a non-member with a broken window gets the
 * same 404 as a non-member with a perfect one; an over-wide window is a <strong>400 naming the
 * cap</strong> and is never clamped; and the response discloses its own provenance in
 * {@link ReportMeta}. The window rules themselves live in {@link ReportWindow}, shared with R1.
 *
 * <h2>The honesty rule</h2>
 * <strong>Cycle time is defined only for issues that have a {@code started_at}, and this class
 * never substitutes {@code created_at} for a missing one.</strong> R2's backfill was best-effort
 * by construction (it matches {@code issue_history} to statuses by display <em>name</em>, so a
 * renamed status is invisible to it, and an issue filed directly into an in-progress status wrote
 * no history row at all), so on any project with history there will be completed issues with no
 * start. Those contribute to lead time, to {@code sampleSize} and to
 * {@code missingStartCount} — and to nothing else.
 *
 * <p>The substitution is worth naming as the specific failure it would be: it does not produce a
 * wrong number in one place, it silently converts the entire report into a lead-time report
 * wearing the label "cycle time", moves the p85 that the aging report draws across its columns,
 * and is invisible in every response. What the client gets instead is the gap, counted, so the UI
 * can print <em>"cycle time available for 812 of 940 completed issues"</em>.
 *
 * <h2>Suppression below five samples</h2>
 * A p85 computed from three issues is arithmetically defined and informationally worthless. Below
 * {@link #MIN_PERCENTILE_SAMPLES} the pair is suppressed to nulls and the sample size is returned
 * beside it, so the client says <em>"not enough completed work to compute percentiles (need 5,
 * have 3)"</em> rather than drawing a line (§2.2, §6). The two pairs are gated
 * <strong>independently</strong>, because they are computed over different samples — see
 * {@link CycleTimePercentiles}.
 *
 * <h2>Cost</h2>
 * Two statements after resolution: one bounded row query and one all-aggregate query. Percentiles
 * are computed by PostgreSQL ({@code percentile_cont … WITHIN GROUP}), never in Java — the row
 * cap bounds what is <em>shipped</em>, and nothing here sorts, counts or averages a list.
 */
@Service
@RequiredArgsConstructor
public class CycleTimeReportService {

    /**
     * The smallest sample this report will quote a percentile from (§2.2). Not configurable, and
     * deliberately so: it is a statement about what is meaningful, not about what an operator's
     * hardware can afford, and the one thing worse than a suppressed line is a line whose
     * threshold differs between installs.
     */
    static final int MIN_PERCENTILE_SAMPLES = 5;

    private final WorkspaceAccessService workspaceAccess;
    private final CycleTimeReportRepository cycleTimeReportRepository;
    private final ReportProperties reportProperties;

    @Transactional(readOnly = true)
    public CycleTimeReportResponse cycleTime(User actor, UUID workspaceId, UUID projectId,
                                             CycleTimeQuery raw) {
        // 1. Tenancy before anything else. 404 for a missing workspace, a missing project and a
        //    non-member alike — never a 403, and never a 400 that would confirm the project
        //    exists to somebody who may not know that.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);

        // 2. Then the dates, BEFORE any arithmetic touches them: `to.plusDays(1)` on a
        //    parseable-but-absurd year is a DateTimeException, i.e. a 500 where the contract
        //    promises a 400.
        ReportWindow.validateBand(raw.from(), raw.to());

        // 3. Only now default the window — the default depends on the configured cap.
        int maxWindowDays = reportProperties.maxWindowDays();
        var query = raw.withDefaultWindow(maxWindowDays);
        ReportWindow.validate(query.from(), query.to(), maxWindowDays);

        var scopedProjectId = ctx.project().getId();
        var start = query.startInclusive();
        var end = query.endExclusive();
        int cap = reportProperties.maxRows();

        List<Object[]> rows;
        List<Object[]> statRows;
        if (query.hasFilters()) {
            var typeId = text(query.typeId());
            var componentId = text(query.componentId());
            var labelId = text(query.labelId());
            // One row past the cap: a full result set IS the truncation signal, so no second
            // count is needed to notice it.
            rows = cycleTimeReportRepository.completedIssuesFiltered(
                    scopedProjectId, start, end, cap + 1, typeId, componentId, labelId);
            statRows = cycleTimeReportRepository.windowStatsFiltered(
                    scopedProjectId, start, end, typeId, componentId, labelId);
        } else {
            // The same two questions without the (CAST(:x AS uuid) IS NULL OR …) filters, which a
            // server-prepared generic plan cannot fold away — see CycleTimeReportRepository.
            rows = cycleTimeReportRepository.completedIssues(scopedProjectId, start, end, cap + 1);
            statRows = cycleTimeReportRepository.windowStats(scopedProjectId, start, end);
        }

        boolean truncated = rows.size() > cap;
        var shipped = truncated ? rows.subList(0, cap) : rows;

        var projectKey = ctx.project().getKey();
        var items = new ArrayList<CycleTimeItem>(shipped.size());
        for (var row : shipped) {
            items.add(new CycleTimeItem(
                    uuid(row[0]),
                    projectKey + "-" + number(row[1]),
                    (String) row[2],
                    uuid(row[3]),
                    instant(row[4]),
                    instant(row[5]),
                    decimal(row[6]),
                    decimal(row[7])));
        }

        var stats = statRows.isEmpty() ? null : statRows.get(0);
        long sampleSize = stats == null ? 0 : number(stats[0]);
        long missingStart = stats == null ? 0 : number(stats[1]);
        var firstIssueAt = stats == null ? null : instant(stats[6]);

        return new CycleTimeReportResponse(
                query.from(), query.to(),
                List.copyOf(items),
                percentiles(stats, sampleSize, missingStart),
                sampleSize,
                missingStart,
                // basedOnIssues is the distinct issues the numbers were computed from, which for
                // this report is the sample itself — NOT items.size(), which is what survived the
                // cap. When they differ the response says so twice: here and in `truncated`.
                new ReportMeta(OffsetDateTime.now(ZoneOffset.UTC), sampleSize, truncated, cap,
                        firstIssueAt, unmatchedFilters(query, stats)));
    }

    // ------------------------------------------------------------------ percentiles

    /**
     * Both pairs, each suppressed on its own sample.
     *
     * <p>The cycle sample is {@code sampleSize - missingStartCount} — the issues that actually
     * have a start — and the lead sample is every completed issue in the window. On a project
     * whose history predates R2 those two numbers can be far apart, so gating them together would
     * either hide a usable lead line or print a cycle line drawn from two issues.
     */
    private static CycleTimePercentiles percentiles(Object[] stats, long sampleSize,
                                                    long missingStart) {
        if (stats == null) {
            return CycleTimePercentiles.NONE;
        }
        long cycleSample = sampleSize - missingStart;
        return new CycleTimePercentiles(
                cycleSample >= MIN_PERCENTILE_SAMPLES
                        ? new ReportPercentiles(decimal(stats[2]), decimal(stats[3]))
                        : ReportPercentiles.NONE,
                sampleSize >= MIN_PERCENTILE_SAMPLES
                        ? new ReportPercentiles(decimal(stats[4]), decimal(stats[5]))
                        : ReportPercentiles.NONE);
    }

    // ------------------------------------------------------------------ disclosure

    /**
     * Which of the supplied filters match no issue in this project at all — the difference between
     * "nothing finished in this window" and "your filter is broken", which look identical on an
     * empty scatter. R1's rule and R1's deliberately weak claim: this is about the project's
     * issues, not about whether the id exists anywhere, so it discloses nothing about any other
     * tenant.
     */
    private static List<String> unmatchedFilters(CycleTimeQuery query, Object[] stats) {
        if (!query.hasFilters() || stats == null || stats.length < 10) {
            return List.of();
        }
        var unmatched = new ArrayList<String>(3);
        if (query.typeId() != null && !flag(stats[7])) {
            unmatched.add("typeId");
        }
        if (query.componentId() != null && !flag(stats[8])) {
            unmatched.add("componentId");
        }
        if (query.labelId() != null && !flag(stats[9])) {
            unmatched.add("labelId");
        }
        return List.copyOf(unmatched);
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code count(*)} is a {@code Long} in Hibernate 6/7 and a {@code BigInteger} before it. */
    private static long number(Object raw) {
        return ((Number) raw).longValue();
    }

    /**
     * A rounded {@code numeric} column — a {@link java.math.BigDecimal} — or null, which is the
     * honesty rule arriving as data: a cycle time with no start, or a percentile with no sample.
     * Null stays null all the way to the JSON; nothing here defaults it to 0, which would put a
     * dot on the floor of the chart for every issue we simply do not know about.
     */
    private static Double decimal(Object raw) {
        return raw == null ? null : ((Number) raw).doubleValue();
    }

    /** A native {@code uuid} column. Accepts the text form a driver change could produce. */
    private static UUID uuid(Object raw) {
        return switch (raw) {
            case null -> null;
            case UUID id -> id;
            case String s -> UUID.fromString(s);
            default -> throw new IllegalStateException(
                    "unexpected uuid type from the report query: " + raw.getClass());
        };
    }

    /**
     * A native {@code timestamptz} column, normalised to UTC — the two shapes a driver/ORM
     * combination can hand back, rather than a cast that turns a mapping change into a 500.
     */
    private static OffsetDateTime instant(Object raw) {
        return switch (raw) {
            case null -> null;
            case OffsetDateTime odt -> odt.withOffsetSameInstant(ZoneOffset.UTC);
            case java.time.Instant i -> i.atOffset(ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(ZoneOffset.UTC);
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
     * {@link CycleTimeReportRepository}'s note on typing a NULL bind parameter for PostgreSQL.
     */
    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
