package com.hamstrack.report.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of a sprint-review list (reports-proposal §2.4) — the issue, as the retro needs to read
 * it.
 *
 * <p>Ids rather than names for the taxonomy, exactly as {@code CycleTimeItem} and
 * {@code AgingItem} do: the SPA already holds the project's types, statuses and members, so
 * resolving them here would be three joins per report to re-send what the client has.
 *
 * <p><strong>A departed issue still gets a row.</strong> When an issue is deleted its ledger
 * entries survive with {@code issue_id} nulled (the FK is {@code ON DELETE SET NULL}), which is
 * the whole reason a completed sprint's record cannot be rewritten by a later delete. Such a row
 * arrives with {@link #deleted} true, its snapshotted {@link #key} and {@link #points}, and
 * {@code null} for everything that only the live issue could answer. The client renders it as the
 * line it is — "DEMO-77 (deleted)" — rather than dropping it, because dropping it would silently
 * change what the sprint delivered.
 *
 * @param issueId    the issue, or {@code null} if it no longer exists. Also the only field a
 *                   client can link from
 * @param key        the key the ledger snapshotted when the issue entered the sprint — present
 *                   for every row, deleted or not
 * @param title      {@code null} for a deleted issue. Never snapshotted: a title is edited far too
 *                   often for a copy of it to be a fact about the sprint, and the ledger
 *                   deliberately stores only what identifies the row
 * @param typeId     {@code null} for a deleted issue
 * @param assigneeId the issue's <em>current</em> assignee, {@code null} when unassigned or deleted.
 *                   Never a per-person breakdown: it is one field on a row, and no list, count,
 *                   filter or CSV column in this feature groups by it (§2.5)
 * @param statusId   the issue's current status, {@code null} for a deleted issue
 * @param points     what the issue weighed <strong>when it entered this sprint</strong> — the
 *                   ledger's snapshot, not today's estimate. {@code null} means it was
 *                   unestimated at that moment, which is a different statement from zero. See
 *                   {@code SprintReviewService} for why this report snapshots and the burn-up
 *                   does not
 * @param closedAt   when the issue was closed, or {@code null} if it is still open (or was
 *                   reopened, which clears the stamp)
 * @param deleted    whether the issue is gone and this row is all that is left of it
 */
public record SprintReviewIssue(
        UUID issueId,
        String key,
        String title,
        UUID typeId,
        UUID assigneeId,
        UUID statusId,
        BigDecimal points,
        OffsetDateTime closedAt,
        boolean deleted
) {}
