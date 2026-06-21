package com.easytask.issue.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IssueHistoryResponse(
        UUID id,
        UUID issueId,
        UUID actorId,
        String actorName,
        String field,
        String oldValue,
        String newValue,
        OffsetDateTime createdAt
) {
}
