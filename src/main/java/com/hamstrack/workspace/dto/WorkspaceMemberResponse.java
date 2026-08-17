package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberResponse(
        UUID userId,
        String email,
        String displayName,
        String avatarUrl,
        WorkspaceRole role,
        Instant joinedAt
) {
    /**
     * @param role the member's role as the legacy enum. Passed in rather than read off
     *     {@code m.getRole()} because that is now a {@code roles} row, and translating it
     *     needs the cached catalog the caller already has (HD-123 S1). S4 replaces this
     *     field with the role's id + display name so a custom role can be shown.
     */
    public static WorkspaceMemberResponse of(WorkspaceMember m, WorkspaceRole role) {
        var u = m.getUser();
        return new WorkspaceMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                role, m.getJoinedAt());
    }
}
