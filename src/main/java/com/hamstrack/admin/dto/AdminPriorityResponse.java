package com.hamstrack.admin.dto;

import com.hamstrack.issue.entity.Priority;

import java.util.UUID;

public record AdminPriorityResponse(
        UUID id, String name, String color, String icon,
        short position, boolean archived, UsageInfo usage
) {
    public static AdminPriorityResponse of(Priority p, UsageInfo usage) {
        return new AdminPriorityResponse(p.getId(), p.getName(), p.getColor(), p.getIcon(),
                p.getPosition(), p.getArchivedAt() != null, usage);
    }
}
