package com.hamstrack.issue.dto;

import java.util.List;

/**
 * The planning view of one project (HD-22, agile-sprints-proposal §7) —
 * {@code GET …/projects/{p}/backlog}. One request returns every section the
 * BacklogPage renders: the project's OPEN sprints (ACTIVE first, then FUTURE by
 * sequence) above the ranked backlog.
 *
 * <p>Each section is independently truncatable at {@code sectionCap}
 * ({@code app.agile.section-max-issues}) following the HD-79 pattern, and its
 * {@link SectionStats} are computed over the WHOLE section, so a truncated section
 * still shows honest totals. The SPA must treat sections as independently
 * refreshable from day one — if the payload ever gets too large the fix is lazy
 * per-section fetches behind this same DTO.
 *
 * @param sectionCap   the server-side per-section cap in force for this response
 * @param bulkMoveCap  the server-side cap on ONE {@code POST …/sprints/{id}/issues}
 *                     ({@code app.agile.max-issues-per-bulk-move}). Echoed here because
 *                     the two caps are independent operator knobs and the stock defaults
 *                     are deliberately different (300 vs 100): a "move all to…" action is
 *                     driven by the rendered SECTION, so a client that assumes one number
 *                     from the other 400s on every section above the bulk cap. With both
 *                     on the wire the SPA chunks at whatever this install actually
 *                     allows instead of hardcoding an operator-tunable constant.
 */
public record BacklogViewResponse(
        List<SprintSection> sprints,
        Section backlog,
        int sectionCap,
        int bulkMoveCap
) {
    /** One sprint's section: the sprint itself plus its (capped) rows. */
    public record SprintSection(
            SprintResponse sprint,
            List<IssueResponse> issues,
            boolean truncated,
            long totalAvailable,
            SectionStats stats
    ) {}

    /** The backlog section — the same shape without a sprint header. */
    public record Section(
            List<IssueResponse> issues,
            boolean truncated,
            long totalAvailable,
            SectionStats stats
    ) {}
}
