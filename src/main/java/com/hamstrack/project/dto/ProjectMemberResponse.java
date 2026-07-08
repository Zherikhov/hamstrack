package com.hamstrack.project.dto;

import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberResponse(
        UUID userId,
        String email,
        String displayName,
        String avatarUrl,
        ProjectRole role,
        Instant joinedAt
) {
    public static ProjectMemberResponse of(ProjectMember m) {
        var u = m.getUser();
        return new ProjectMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                m.getRole(), m.getJoinedAt());
    }
}
