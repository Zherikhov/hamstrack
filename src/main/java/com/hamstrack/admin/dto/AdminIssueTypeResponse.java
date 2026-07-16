package com.hamstrack.admin.dto;

import com.hamstrack.issue.entity.IssueType;

import java.util.UUID;

public record AdminIssueTypeResponse(
        UUID id, String name, String color, String icon,
        short position, boolean archived, UsageInfo usage
) {
    public static AdminIssueTypeResponse of(IssueType t, UsageInfo usage) {
        return new AdminIssueTypeResponse(t.getId(), t.getName(), t.getColor(), t.getIcon(),
                t.getPosition(), t.getArchivedAt() != null, usage);
    }
}
