package com.hamstrack.report.dto;

import java.util.List;

/**
 * {@code GET …/reports/aging} — the unfinished-work half of the cycle time page
 * (reports-proposal §2.2, §4.3), and the half the proposal argues almost nobody ships well.
 *
 * <h2>No window, on purpose</h2>
 * This endpoint takes no {@code from}/{@code to} and cannot be given one: "which open item is
 * rotting <em>right now</em>" is a question about current state, and a window on it would be
 * meaningless (every open issue is open today) or misleading (a window on {@code created_at}
 * would hide exactly the oldest items, which are the entire point). It is the one report in the
 * epic whose cost is bounded by how much work is <em>in flight</em> rather than by a date range,
 * which is a far smaller number on any healthy project and is capped anyway
 * ({@code app.reports.max-rows}, {@code meta.truncated}).
 *
 * <h2>The percentiles belong to the other half</h2>
 * {@link #percentiles} are the <strong>cycle-time</strong> p50/p85 of this project's finished
 * work, and they are the reason this report is actionable rather than a list. Drawn across the
 * columns, an item above the p85 line is visibly older than 85% of everything the team has ever
 * completed — a statement about this item, made in the team's own units, with no target, no
 * SLA and nothing configured.
 *
 * <p>Two consequences worth stating because both look like bugs from the outside:
 * <ul>
 *   <li>they are computed over the project's <strong>whole history</strong> of completed work,
 *       not over a window — "everything the team has ever finished" is the claim the line makes
 *       and it is the claim the query makes;</li>
 *   <li>they come from issues that have a {@code started_at}, so on a project whose history
 *       predates R2's backfill they can be suppressed ({@code null}) while the columns are full.
 *       The columns still render — the lines are an overlay, not a precondition — and the
 *       client prints the same "not enough completed work" sentence the other half uses.</li>
 * </ul>
 *
 * @param columns     one per non-DONE status of the effective workflow in board order, plus at
 *                    most one trailing "Not on this board" column ({@link AgingColumn}). Empty
 *                    columns are present.
 * @param percentiles cycle-time p50/p85 over the project's completed work, or a suppressed pair.
 * @param meta        the shared provenance block. {@code basedOnIssues} is the number of open
 *                    issues considered — which, when {@code truncated}, is larger than the
 *                    number of items actually shipped.
 */
public record AgingReportResponse(
        List<AgingColumn> columns,
        ReportPercentiles percentiles,
        ReportMeta meta
) {}
