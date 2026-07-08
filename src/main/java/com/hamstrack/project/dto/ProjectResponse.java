package com.hamstrack.project.dto;

import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String key,
        String description,
        boolean archived,
        ProjectRole myRole,
        Instant createdAt
) {
    public static ProjectResponse of(Project p, ProjectRole role) {
        return new ProjectResponse(
                p.getId(), p.getWorkspace().getId(),
                p.getName(), p.getKey(), p.getDescription(),
                p.isArchived(), role, p.getCreatedAt());
    }
}
