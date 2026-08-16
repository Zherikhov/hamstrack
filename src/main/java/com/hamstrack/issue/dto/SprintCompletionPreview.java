package com.hamstrack.issue.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a {@code POST …/sprints/{id}/complete} would report, computed BEFORE the fact
 * (HD-22, agile-sprints-proposal §7) — the completion dialog's data source, so it
 * never has to guess.
 *
 * <p><strong>Read-only and readable by any project member</strong> (unlike the
 * completion itself, which is curator-only): looking at the numbers is not a
 * commitment. The counters are the same ones the subsequent completion reports, off
 * the same query — no drift.
 *
 * @param unfinishedIssueCount issues whose status category is NOT DONE — exactly
 *                             what the completion will move
 * @param targetCandidates     the FUTURE sprints of this project the unfinished work
 *                             may be carried over to; empty means the dialog must
 *                             offer "Create sprint" instead
 */
public record SprintCompletionPreview(
        int totalIssueCount,
        int doneIssueCount,
        int unfinishedIssueCount,
        BigDecimal totalPoints,
        BigDecimal donePoints,
        BigDecimal unfinishedPoints,
        List<SprintRef> targetCandidates
) {}
