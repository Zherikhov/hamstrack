package com.hamstrack.issue.dto;

import java.util.UUID;

/**
 * Move one issue in the shared backlog/board rank, optionally into or out of a sprint
 * — {@code POST …/issues/{number}/rank} (HD-22, agile-sprints-proposal §3.3, §4.4).
 *
 * <p><strong>The client never sends a rank value.</strong> It sends the neighbours it
 * dropped between; the server fills in whichever neighbour is missing with one indexed
 * query over the <em>target section</em> and takes the midpoint. Both absent ⇒ append
 * to the section's end. {@code issues.position} is therefore server-written only and is
 * not even exposed on {@code IssueResponse} — a client can neither invent, corrupt nor
 * leak a rank.
 *
 * @param afterIssueId  the issue the moved one should land AFTER (below it), or null
 * @param beforeIssueId the issue it should land BEFORE (above it), or null. Both
 *                      anchors must already be in the target section (422 otherwise),
 *                      and neither may be the moved issue itself (422).
 * @param sprintId      move into this sprint at the same time; unknown/foreign → 422,
 *                      COMPLETED → 422. Mutually exclusive with {@code clearSprint}
 *                      (400 when both are set).
 * @param clearSprint   move back to the backlog. Boxed {@link Boolean} on purpose —
 *                      Boot 4 / Jackson 3 enable {@code FAIL_ON_NULL_FOR_PRIMITIVES},
 *                      so a primitive would 400 every request that omits the flag; the
 *                      canonical constructor coalesces null → false.
 * @param version       OPTIONAL optimistic check: present → 409 when stale, absent →
 *                      the move applies. Ranking is a positional, last-drag-wins
 *                      operation; forcing a version round-trip on every drop would
 *                      produce a 409 storm during a planning meeting (§3.3.3).
 */
public record RankIssueRequest(
        UUID afterIssueId,
        UUID beforeIssueId,
        UUID sprintId,
        Boolean clearSprint,
        Integer version
) {
    public RankIssueRequest {
        clearSprint = clearSprint != null && clearSprint;
    }
}
