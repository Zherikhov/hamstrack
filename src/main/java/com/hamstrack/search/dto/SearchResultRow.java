package com.hamstrack.search.dto;

import com.hamstrack.issue.dto.IssueResponse;

import java.util.UUID;

/**
 * One HQL search result row (Advanced Search proposal §8.1). Reuses the existing
 * {@link IssueResponse} — the board/backlog and issue drawer already render it, so
 * the results table needs no new mapping — plus the owning project's id/key/name,
 * because results are cross-project and each row must self-identify its project.
 * ({@code issue.key} already embeds the human project key; {@code projectName} is
 * the genuinely new datum for grouping/column display.)
 */
public record SearchResultRow(IssueResponse issue, UUID projectId, String projectKey, String projectName) {}
