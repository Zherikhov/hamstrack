package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssueAttachment;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        String filename,
        long sizeBytes,
        String contentType,
        UUID uploadedById,
        String uploadedByName,
        Instant createdAt
) {
    public static AttachmentResponse of(IssueAttachment a) {
        return new AttachmentResponse(
                a.getId(), a.getFilename(), a.getSizeBytes(), a.getContentType(),
                a.getUploadedBy().getId(), a.getUploadedBy().getDisplayName(), a.getCreatedAt());
    }
}
