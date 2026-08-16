package com.hamstrack.issue.dto;

import java.util.List;

/**
 * Response shape for the capped board issue list (HD-79) — the no-{@code size}
 * variant of {@code GET .../issues}. The list is bounded to {@code cap}; when the
 * project (after the same filters) exceeds it, {@code truncated} is true and
 * {@code totalAvailable} reports the full count so the SPA can render a truncation
 * banner ("Showing first {cap} of {totalAvailable}"). The paged ({@code ?size=})
 * variant is unchanged and still returns a {@code PageResponse}.
 */
public record BoardIssuesResponse(
        List<IssueResponse> issues,
        boolean truncated,
        long totalAvailable,
        int cap
) {}
