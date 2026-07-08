package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssueHistory;

import java.time.Instant;
import java.util.UUID;

public record IssueHistoryResponse(
        UUID id,
        String field,
        String oldValue,
        String newValue,
        UUID changedById,
        String changedByName,
        Instant createdAt
) {
    public static IssueHistoryResponse of(IssueHistory h) {
        return new IssueHistoryResponse(
                h.getId(),
                h.getField(),
                h.getOldValue(),
                h.getNewValue(),
                h.getChangedBy().getId(),
                h.getChangedBy().getDisplayName(),
                h.getCreatedAt()
        );
    }
}
