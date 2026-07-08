package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssueComment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID authorId,
        String authorName,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommentResponse of(IssueComment c) {
        return new CommentResponse(
                c.getId(), c.getAuthor().getId(), c.getAuthor().getDisplayName(),
                c.getBody(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
