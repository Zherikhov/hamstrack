package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueFieldValue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        long number,
        String key,
        String title,
        String description,
        IssueTypeResponse type,
        StatusResponse status,
        PriorityResponse priority,
        AssigneeInfo assignee,
        AssigneeInfo reporter,
        UUID parentId,
        LocalDate dueDate,
        List<FieldValueResponse> fields,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public record AssigneeInfo(UUID id, String displayName, String avatarUrl) {}

    public static IssueResponse of(Issue i, Collection<IssueFieldValue> fieldValues) {
        var projectKey = i.getProject().getKey() + "-" + i.getNumber();
        return new IssueResponse(
                i.getId(), i.getNumber(), projectKey,
                i.getTitle(), i.getDescription(),
                IssueTypeResponse.of(i.getType()),
                StatusResponse.of(i.getStatus()),
                PriorityResponse.of(i.getPriority()),
                i.getAssignee() == null ? null :
                        new AssigneeInfo(i.getAssignee().getId(), i.getAssignee().getDisplayName(), i.getAssignee().getAvatarUrl()),
                new AssigneeInfo(i.getReporter().getId(), i.getReporter().getDisplayName(), i.getReporter().getAvatarUrl()),
                i.getParent() == null ? null : i.getParent().getId(),
                i.getDueDate(),
                FieldValueResponse.of(fieldValues),
                i.getVersion(),
                i.getCreatedAt(), i.getUpdatedAt());
    }
}
