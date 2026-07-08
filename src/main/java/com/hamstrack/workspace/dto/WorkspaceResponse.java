package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceResponse(
        UUID id,
        String slug,
        String name,
        WorkspaceRole myRole,
        Instant createdAt
) {
    public static WorkspaceResponse of(Workspace w, WorkspaceRole role) {
        return new WorkspaceResponse(w.getId(), w.getSlug(), w.getName(), role, w.getCreatedAt());
    }
}
