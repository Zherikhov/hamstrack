package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintState;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A project sprint (HD-22, agile-sprints-proposal §7). The counters are
 * <strong>always present</strong> — like {@code VersionResponse}'s progress and
 * unlike {@code ComponentResponse.issueCount}, they cost ONE grouped query for the
 * whole page of sprints, so there is nothing to opt out of.
 *
 * @param daysRemaining whole days from today (UTC) to {@code endAt}, for an ACTIVE
 *                      sprint with an end date only; {@code 0} = ends today,
 *                      <strong>negative = overdue</strong>. {@code null} otherwise —
 *                      a FUTURE or COMPLETED sprint has nothing to count down.
 * @param points        sum of the sprint's story points; {@code 0} when nothing in
 *                      it is estimated (never null in this design — see §3.4's
 *                      boxed fallback for the variant where it is)
 */
public record SprintResponse(
        UUID id,
        String name,
        String goal,
        SprintState state,
        int sequence,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime completedAt,
        Integer daysRemaining,
        int issueCount,
        int doneIssueCount,
        BigDecimal points,
        BigDecimal donePoints,
        int unestimatedCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static SprintResponse of(Sprint s, SectionStats stats, Integer daysRemaining) {
        var st = stats != null ? stats : SectionStats.EMPTY;
        return new SprintResponse(
                s.getId(), s.getName(), s.getGoal(), s.getState(), s.getSequence(),
                s.getStartAt(), s.getEndAt(), s.getCompletedAt(), daysRemaining,
                st.issueCount(), st.doneIssueCount(), st.points(), st.donePoints(),
                st.unestimatedCount(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
