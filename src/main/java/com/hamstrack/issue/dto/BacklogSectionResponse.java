package com.hamstrack.issue.dto;

import java.util.List;

/**
 * ONE planning section, fetched on its own (HD-96) — {@code GET
 * …/projects/{p}/backlog/sections/backlog} and {@code …/backlog/sections/{sprintId}}.
 *
 * <p><strong>The field names are byte-identical to the aggregate's section records</strong>
 * ({@link BacklogViewResponse.SprintSection} / {@link BacklogViewResponse.Section}) plus the
 * two caps the aggregate carries at its top level, so a client patches a refreshed section
 * into a cached view without translating anything. That identity is the point: HD-96 was a
 * refreshed section quietly answering under a different honesty protocol than the rendered
 * one, and a shared vocabulary is what makes the two comparable at all — {@code truncated}
 * here means what it means there ("this section holds more matching issues than
 * {@code sectionCap}"), never "more exist than the page you received".
 *
 * <p>This is what the aggregate's own DTO prescribed: <em>"The SPA must treat sections as
 * independently refreshable from day one — if the payload ever gets too large the fix is lazy
 * per-section fetches behind this same DTO"</em>. Behind this DTO, and with no page size on
 * the wire: a section fetch that takes no {@code size} has no number for the client to hold,
 * none to echo back, and nothing to clamp.
 *
 * @param sprint          the section's sprint, or {@code null} for the BACKLOG section —
 *                        the encoding this domain already uses everywhere ({@code Issue.sprint}
 *                        null is the backlog; the grouped stats query's NULL group is the
 *                        backlog), rather than a new convention or a second record
 * @param issues          the section's rows in canonical rank order, capped at {@code sectionCap}
 * @param truncated       the section holds more matching issues than {@code sectionCap}
 * @param totalAvailable  matching issues in the WHOLE section, from the grouped stats query —
 *                        never the size of the list above
 * @param stats           whole-section roll-up, filter-aware and cap-blind, so a truncated
 *                        section still shows honest totals
 * @param sectionCap      {@code app.agile.section-max-issues} as this response was built. It
 *                        travels on every planning response and is never sent back by a client:
 *                        an operator may retune it between a render and a refresh, and the
 *                        refreshed section simply carries the new value.
 * @param bulkMoveCap     {@code app.agile.max-issues-per-bulk-move} — an INDEPENDENT operator
 *                        knob with a deliberately different default, echoed for the same reason
 *                        the aggregate echoes it: "move all to…" is driven by the rendered
 *                        section, so a client that inferred one cap from the other would 400 on
 *                        every section above the bulk cap. A view whose sections have each been
 *                        refreshed must still carry both.
 */
public record BacklogSectionResponse(
        SprintResponse sprint,
        List<IssueResponse> issues,
        boolean truncated,
        long totalAvailable,
        SectionStats stats,
        int sectionCap,
        int bulkMoveCap
) {}
