package com.hamstrack.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One (slice, segment) intersection — a stack in a stacked bar, or a cell of the table.
 * Present only on a segmented request; an unsegmented response ships an empty list.
 *
 * <p>Cells reference their buckets <strong>by id</strong> rather than repeating them, because a
 * segmented response is a product of two axes and inlining both buckets into every cell is where
 * a 2 000-cell response stops being small. {@code null} is a real id (the "has no value" bucket),
 * so match it as one.
 *
 * <p><strong>The cells of one slice can sum to more than that slice's total, and only when the
 * segment dimension is many-valued</strong> ({@code segmentMultiValued} on the response — today
 * that is {@code LABEL} alone). An issue with three labels contributes to three cells of the same
 * bar while counting once in the bar. Also, when {@code cellsTruncated} is set they can sum to
 * <em>less</em>. Both directions are disclosed on the response; neither is a defect in the bar,
 * whose total is computed independently ({@link InsightsSlice}).
 *
 * @param sliceId   id of the slice bucket, or {@code null} for its no-value bucket
 * @param segmentId id of the segment bucket, or {@code null} for its no-value bucket
 */
public record InsightsCell(
        UUID sliceId,
        UUID segmentId,
        long count,
        BigDecimal points,
        long unestimatedCount
) {}
