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
    public static WorkspaceMemberResponse of(WorkspaceMember m) {
        var u = m.getUser();
        return new WorkspaceMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                m.getRole(), m.getJoinedAt());
    }
}
