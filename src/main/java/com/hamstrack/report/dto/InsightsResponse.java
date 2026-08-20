package com.hamstrack.report.dto;

import java.util.List;

/**
 * {@code POST /api/workspaces/{wsId}/search/insights} — the Insights panel's dataset
 * (reports-proposal §2.6).
 *
 * <p>Shaped as a small pivot rather than one flat array, for one reason: <strong>a bar's height
 * and its breakdown are capped separately, so they must be computed separately.</strong>
 * {@link #slices} are exact; {@link #cells} may be partial. Deriving the bars by summing the cells
 * would make every truncated response quietly understate its own chart.
 *
 * @param measure           echo of the requested measure. It ranked the buckets; it did not filter
 *                          them — every bucket carries both numbers ({@link InsightsMeasure})
 * @param slice             echo of the x dimension
 * @param segment           echo of the colour dimension, or {@code null} when unsegmented
 * @param slices            the bars, ordered by {@code measure} descending then label ascending,
 *                          at most {@link #sliceCap}. Totals are exact
 * @param segments          the legend: every segment value appearing in {@link #cells}, ordered the
 *                          same way. Empty when unsegmented
 * @param cells             the (slice, segment) intersections, at most {@link #cellCap}. Empty when
 *                          unsegmented — the bars already are the answer
 * @param sliceMultiValued  whether one issue can occupy several bars ({@code LABEL} only). When
 *                          true the bars sum to more than {@code meta.basedOnIssues}, which is
 *                          arithmetic rather than a defect, and the panel should say so
 * @param segmentMultiValued the same for the colour dimension: when true, a bar's stacks can sum
 *                          to more than the bar
 * @param slicesTruncated   whether there were more slice buckets than {@link #sliceCap}. The ones
 *                          returned are the largest by {@code measure}
 * @param cellsTruncated    whether there were more cells than {@link #cellCap}. The bars are still
 *                          exact; only the breakdown is partial
 * @param sliceCap          the bar cap that would bite, always reported so a client never has to
 *                          guess what it was measured against (the {@code ReportMeta.cap}
 *                          convention, applied to the axis this report can actually truncate)
 * @param cellCap           the cell cap that would bite
 * @param meta              the shared provenance block. Read its javadoc for what
 *                          {@code basedOnIssues} counts here — distinct issues matching the query,
 *                          computed independently of every cap above, and NOT the sum of the
 *                          series. {@code truncated} is always {@code false} and {@code cap} is
 *                          {@code app.reports.max-rows}: this report materialises no issue rows at
 *                          all (PostgreSQL aggregates and returns buckets), so the row cap cannot
 *                          bite — the same fact {@code FlowReportService} reports for the same
 *                          reason. What CAN truncate here is buckets, and it has its own two flags
 *                          above rather than borrowing a field that means rows.
 *                          {@code firstIssueAt} is {@code null} and {@code unmatchedFilters} is
 *                          empty by construction: the first is a per-PROJECT property and this
 *                          report spans a workspace, and the second belongs to id-shaped filter
 *                          parameters — an HQL name that matches nothing is a 422 from the
 *                          resolver long before it could be reported as "unmatched"
 */
public record InsightsResponse(
        InsightsMeasure measure,
        InsightsDimension slice,
        InsightsDimension segment,
        List<InsightsSlice> slices,
        List<InsightsBucket> segments,
        List<InsightsCell> cells,
        boolean sliceMultiValued,
        boolean segmentMultiValued,
        boolean slicesTruncated,
        boolean cellsTruncated,
        int sliceCap,
        int cellCap,
        ReportMeta meta
) {}
