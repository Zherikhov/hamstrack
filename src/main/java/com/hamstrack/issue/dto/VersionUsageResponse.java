package com.hamstrack.issue.dto;

/**
 * What a version is used for (HD-32, §6.3) — backs the delete-confirmation dialog
 * and the "move N unresolved issues to →" prompt in the release dialog.
 *
 * @param fixIssueCount           issues with a FIX link
 * @param affectsIssueCount       issues with an AFFECTS link
 * @param unresolvedFixIssueCount of the FIX-linked issues, those NOT in a
 *                                DONE-category status — the number
 *                                {@code moveUnresolvedToVersionId} would move
 */
public record VersionUsageResponse(
        int fixIssueCount,
        int affectsIssueCount,
        int unresolvedFixIssueCount
) {}
