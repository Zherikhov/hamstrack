package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceMember;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberResponse(
        UUID userId,
        String email,
        String displayName,
        String avatarUrl,
        String role,
        Instant joinedAt
) {
    /**
     * @param role the member's role <em>key</em> ({@code "OWNER"}/{@code "ADMIN"}/
     *     {@code "MEMBER"} — the same strings the deleted enum serialised as). Passed in
     *     rather than read off {@code m.getRole()} because that is a {@code roles} row and
     *     translating it needs the cached catalog the caller already has. S4 adds the
     *     role's id + display name beside it so a custom role can be shown.
     */
    public static WorkspaceMemberResponse of(WorkspaceMember m, String role) {
        var u = m.getUser();
        return new WorkspaceMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                role, m.getJoinedAt());
    }
}
