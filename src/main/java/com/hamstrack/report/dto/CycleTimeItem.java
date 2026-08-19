package com.hamstrack.report.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One dot on the finished-work scatter (reports-proposal §2.2): x = {@link #closedAt},
 * y = {@link #cycleDays} or {@link #leadDays} depending on the page's toggle.
 *
 * <h2>The honesty rule, expressed as a nullable field</h2>
 * {@link #cycleDays} is {@code null} — not zero, not "same as lead" — for every issue with no
 * {@link #startedAt}. Cycle time is defined <strong>only</strong> for issues we know the start
 * of; R2's backfill was best-effort by construction (it joins {@code issue_history} by status
 * <em>name</em>, so a renamed status is invisible to it), and an issue created directly into an
 * in-progress status before this release wrote no history row at all.
 *
 * <p><strong>Never substitute {@code created_at} for a missing {@code started_at}.</strong>
 * That single substitution turns a cycle-time report into a lead-time report wearing a false
 * name — every dot moves up, the p85 the aging half draws across its columns moves with it, and
 * nothing anywhere says so. The response reports {@code missingStartCount} instead, and the UI
 * prints <em>"cycle time available for 812 of 940 completed issues"</em>.
 *
 * @param issueId   the issue, so the dot is clickable through to the issue detail.
 * @param key       the project-scoped key ("DEMO-14"), built from the resolved project's key —
 *                  not read back per row, which would be an N+1 over a 20 000-row response.
 * @param title     the issue title, so a hovered dot names something.
 * @param typeId    the issue type, so the client can shape or colour by type without a second
 *                  request. The id only: this response carries no taxonomy, and the SPA
 *                  already holds the project config it would need to resolve it.
 * @param startedAt when work began, or {@code null} when unknown — the provenance of
 *                  {@code cycleDays}, returned so a client is never handed a number (or a gap)
 *                  it cannot explain.
 * @param closedAt  when the issue was closed. Never null: the window is a range on this column.
 *                  Note that {@code closed_at} is CLEARED on re-open and re-stamped on a later
 *                  close, so a reopened-and-reclosed issue appears once, at its most recent
 *                  closure — the same "as of now" caveat {@link FlowBucket} carries.
 * @param cycleDays {@code closedAt - startedAt} in days to two decimals, or {@code null} — see
 *                  above. Fractional on purpose: a large share of real work finishes inside a
 *                  day, and rounding those to 0 or 1 is the difference between a scatter with a
 *                  shape and a bar at the origin.
 * @param leadDays  {@code closedAt - createdAt} in days to two decimals. Defined for every
 *                  completed issue from day one, which is why the page is never empty even on
 *                  an install that upgraded yesterday.
 */
public record CycleTimeItem(
        UUID issueId,
        String key,
        String title,
        UUID typeId,
        OffsetDateTime startedAt,
        OffsetDateTime closedAt,
        Double cycleDays,
        Double leadDays
) {}
