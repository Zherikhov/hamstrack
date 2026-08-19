package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.entity.Project;
import com.hamstrack.report.dto.AgingColumn;
import com.hamstrack.report.dto.AgingItem;
import com.hamstrack.report.dto.AgingReportResponse;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.dto.ReportPercentiles;
import com.hamstrack.report.repository.AgingReportRepository;
import com.hamstrack.report.service.LifetimeCycleTimeCache.LifetimeCycleTime;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aging work in progress (reports-proposal §2.2) — <em>which open item is rotting right
 * now</em>, and the report the epic ships <strong>instead of</strong> a cumulative flow diagram.
 *
 * <p>That substitution is the argument, not a compromise (§1.3). A CFD shades an area and asks
 * the reader to infer where the queue is; it is the most expensive report in the set (the only
 * one needing a per-day-per-status snapshot, i.e. the only reason v1 would need precomputation at
 * all), it is documented as widely misread, and Jira's own implementation renders backwards
 * transitions incorrectly — routine in a product whose workflows permit them. Everything a team
 * actually gets from a CFD is delivered here, live, from the transactional store: this report
 * <strong>names DEMO-31</strong> and says it has been open longer than 85% of everything the team
 * has ever finished.
 *
 * <h2>No window</h2>
 * "What is rotting now" is a current-state question. There is nothing to validate, nothing to
 * clamp and nothing to refuse — which is why this is the one report on the base path with no 400
 * at all, and why its cost is bounded by work in flight rather than by history.
 *
 * <h2>Columns from the workflow, items from the issues, and the disagreement between them</h2>
 * Columns are the project's <em>effective</em> statuses via {@link ProjectConfigService} (already
 * cached), minus the DONE category, in board order — including the empty ones, because a status
 * nobody is in is a fact about the board and a report that only draws columns it found rows for
 * redraws its own axis every day.
 *
 * <p>An issue can legitimately sit in a status that is not in the project's current workflow: the
 * codebase gates <em>transitions</em>, not existing rows, so a workflow swap strands whatever was
 * mid-flight. Those issues are gathered into one trailing "Not on this board" column rather than
 * dropped. A report whose purpose is to name the item nobody is looking at must not begin by
 * hiding the items nobody is looking at — an awkward column is much the lesser evil.
 *
 * <h2>Cost</h2>
 * Two statements after resolution on the steady-state path, plus zero for the columns (the config
 * cache). Neither is per-issue: the item query is capped and discloses truncation, and the open
 * counts are one aggregate pass bounded by work in flight.
 *
 * <p>The third statement — the lifetime percentiles — is the one unbounded read in the whole
 * feature (O(project history), no window, no cap, growing forever) and runs at most once per
 * project per minute per node behind {@link LifetimeCycleTimeCache}. A cold request therefore
 * costs three statements and a warm one two, both pinned by {@code CycleTimeQueryCountTest}.
 * Splitting it out of the combined statement R3 first shipped is what makes it cacheable at all:
 * only that half tolerates being a minute old, and the open half must not be.
 */
@Service
@RequiredArgsConstructor
public class AgingReportService {

    private final WorkspaceAccessService workspaceAccess;
    private final AgingReportRepository agingReportRepository;
    private final ProjectConfigService projectConfigService;
    private final LifetimeCycleTimeCache lifetimeCycleTimeCache;
    private final ReportProperties reportProperties;

    @Transactional(readOnly = true)
    public AgingReportResponse aging(User actor, UUID workspaceId, UUID projectId) {
        // Tenancy first and last: there is nothing else to validate on this endpoint. 404 for a
        // missing workspace, a missing project and a non-member alike.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        var scopedProjectId = ctx.project().getId();
        int cap = reportProperties.maxRows();

        // One row past the cap: a full result set IS the truncation signal.
        var rows = agingReportRepository.openIssues(scopedProjectId, cap + 1);
        boolean truncated = rows.size() > cap;
        var shipped = truncated ? rows.subList(0, cap) : rows;

        // The counts come from their own statement rather than from the rows, because when the
        // cap bites the rows are exactly the wrong thing to count: `basedOnIssues` must state how
        // many issues the report was ABOUT, which is the number the reader can no longer see.
        // Live, every request — see the repository on why this half is deliberately not cached.
        var counts = agingReportRepository.openCounts(scopedProjectId);
        var row = counts.isEmpty() ? null : counts.get(0);
        long totalOpen = row == null ? 0 : number(row[0]);
        var firstIssueAt = row == null ? null : instant(row[1]);

        // The reference lines, from a per-project 60s snapshot of the project's whole finished
        // history. The RESOLVED id, never the path variable: a cache key is an address into
        // shared process memory, so it may only be an id the caller has been proven to hold.
        var lifetime = lifetimeCycleTimeCache.forProject(scopedProjectId);

        var itemsByStatus = groupByStatus(shipped, ctx.project().getKey());

        return new AgingReportResponse(
                columns(ctx.project(), itemsByStatus),
                lifetimePercentiles(lifetime),
                new ReportMeta(OffsetDateTime.now(ZoneOffset.UTC), totalOpen, truncated, cap,
                        firstIssueAt, List.of()));
    }

    // ------------------------------------------------------------------ columns

    /**
     * Items bucketed by their status id, in the order the query returned them — oldest first —
     * because a {@link LinkedHashMap} of lists appended to in scan order preserves that ordering
     * inside every bucket without a second sort.
     */
    private static Map<UUID, List<AgingItem>> groupByStatus(List<Object[]> rows, String projectKey) {
        var byStatus = new LinkedHashMap<UUID, List<AgingItem>>();
        for (var row : rows) {
            var item = new AgingItem(
                    uuid(row[0]),
                    projectKey + "-" + number(row[1]),
                    (String) row[2],
                    ((Number) row[6]).doubleValue(),
                    uuid(row[4]),
                    instant(row[5]));
            byStatus.computeIfAbsent(uuid(row[3]), id -> new ArrayList<>()).add(item);
        }
        return byStatus;
    }

    /**
     * The workflow's non-DONE statuses in board order, then — only if anything is stranded there —
     * the trailing off-workflow column.
     *
     * <p>The leftover set is computed by removing every column that was drawn, rather than by
     * asking each issue whether its status is in the workflow: same answer, but this way an issue
     * cannot be counted twice or dropped by a mismatch between the two membership tests.
     */
    private List<AgingColumn> columns(Project project,
                                      Map<UUID, List<AgingItem>> itemsByStatus) {
        var columns = new ArrayList<AgingColumn>();
        var remaining = new LinkedHashMap<>(itemsByStatus);
        for (var status : projectConfigService.statuses(project)) {
            if (status.getCategory() == StatusCategory.DONE) {
                continue;
            }
            var items = remaining.remove(status.getId());
            columns.add(new AgingColumn(status.getId(), status.getName(), status.getCategory(),
                    items == null ? List.of() : List.copyOf(items)));
        }
        if (!remaining.isEmpty()) {
            var stranded = new ArrayList<AgingItem>();
            remaining.values().forEach(stranded::addAll);
            columns.add(new AgingColumn(null, AgingColumn.OFF_WORKFLOW_NAME, null,
                    List.copyOf(stranded)));
        }
        return List.copyOf(columns);
    }

    // ------------------------------------------------------------------ the lines

    /**
     * The p50/p85 drawn across the columns: <strong>cycle time over the project's whole completed
     * history</strong>, suppressed below {@link CycleTimeReportService#MIN_PERCENTILE_SAMPLES}
     * samples exactly as the other half suppresses its own.
     *
     * <p>Same threshold and same silence, on purpose: the two halves of one page must not disagree
     * about when there is enough evidence to draw a line, and a reader who is told "not enough
     * completed work" on the scatter must not find a confident p85 painted across the columns
     * beside it.
     *
     * <p>The suppression is applied <strong>here</strong>, to the cached triple, rather than being
     * baked into what the cache stores. The threshold is this report's editorial rule about when
     * there is enough evidence to draw a line; the cache holds the measurement. Storing an
     * already-suppressed pair would put the rule inside the shared snapshot, where the next reader
     * of the same numbers could neither see the sample size nor apply a different threshold to it.
     */
    private static ReportPercentiles lifetimePercentiles(LifetimeCycleTime lifetime) {
        if (lifetime.sampleSize() < CycleTimeReportService.MIN_PERCENTILE_SAMPLES) {
            return ReportPercentiles.NONE;
        }
        return new ReportPercentiles(lifetime.p50(), lifetime.p85());
    }

    // ------------------------------------------------------------------ plumbing

    private static long number(Object raw) {
        return ((Number) raw).longValue();
    }

    private static UUID uuid(Object raw) {
        return switch (raw) {
            case null -> null;
            case UUID id -> id;
            case String s -> UUID.fromString(s);
            default -> throw new IllegalStateException(
                    "unexpected uuid type from the report query: " + raw.getClass());
        };
    }

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
}
