package com.hamstrack.issue.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The honest done-vs-carried-over report of a completed sprint (HD-22,
 * agile-sprints-proposal §7). Returned by {@code POST …/sprints/{id}/complete} and
 * rendered as the summary toast — <em>"Sprint 7 completed — 17 points done, 2 issues
 * moved to Sprint 8."</em>
 *
 * <p>Nothing is persisted as a report artifact: burndown/velocity are explicitly out
 * of scope for 0.13.0.
 *
 * @param completedIssueCount   issues that ended the sprint in a DONE-category status
 *                              — they KEEP their {@code sprint_id}, which is the
 *                              sprint's record of what it delivered
 * @param carriedOverIssueCount unfinished issues moved to the chosen destination
 * @param carriedOverToSprintId the target sprint, or {@code null} when the unfinished
 *                              work went back to the backlog
 */
public record SprintCompletionResult(
        SprintResponse sprint,
        int completedIssueCount,
        int carriedOverIssueCount,
        UUID carriedOverToSprintId,
        BigDecimal donePoints,
        BigDecimal carriedOverPoints
) {}
