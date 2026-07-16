package com.hamstrack.admin.dto;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;

import java.util.UUID;

public record AdminStatusResponse(
        UUID id, String name, StatusCategory category, String color,
        short position, boolean archived, UsageInfo usage
) {
    public static AdminStatusResponse of(Status s, UsageInfo usage) {
        return new AdminStatusResponse(s.getId(), s.getName(), s.getCategory(), s.getColor(),
                s.getPosition(), s.getArchivedAt() != null, usage);
    }
}
