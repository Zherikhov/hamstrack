package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
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
        // HD-22 — KANBAN | SCRUM. A presentation switch, not a permission (§3.5): the
        // sprint API behaves identically either way. It rides this response because
        // NavRail and both settings areas already fetch it, so the Scrum board costs no
        // extra request on the hot path.
        BoardMode boardMode,
        ProjectRole myRole,
        Instant createdAt
) {
    public static ProjectResponse of(Project p, ProjectRole role) {
        return new ProjectResponse(
                p.getId(), p.getWorkspace().getId(),
                p.getName(), p.getKey(), p.getDescription(),
                p.isArchived(), p.getBoardMode(), role, p.getCreatedAt());
    }
}
