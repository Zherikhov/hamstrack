package com.hamstrack.issue.dto;

import java.util.UUID;

/**
 * Outcome of a label merge (HD-30, §4.4). No per-issue history rows are written — a
 * merge can touch thousands of issues and writing one row each would make a single
 * request unbounded. This response plus the target's bumped {@code updatedAt} ARE the
 * record of the operation.
 *
 * @param targetId             the surviving label
 * @param mergedLabelCount     how many source labels were absorbed and deleted
 * @param reassignedIssueCount how many issue attachments now point at the target
 *                             (duplicates that collapsed onto an existing target
 *                             attachment are not counted twice)
 */
public record MergeLabelsResponse(UUID targetId, int mergedLabelCount, int reassignedIssueCount) {}
