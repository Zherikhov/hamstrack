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
     *     translating it needs the cached catalog the caller already has. S6 adds the
     *     role's id + display name beside it so a custom role can be shown.
     *
     *     <p><strong>{@code null} when the row's {@code role_id} fails the scope/ownership
     *     assertion</strong> (HD-127 §3b). The member stays in the list — one corrupt row
     *     must not 404 the whole People tab — and the role just refused is emphatically NOT
     *     rendered in its place: that name is the one genuine leak in the vicinity, and it
     *     is the reason this listing runs the assertion at all.
     */
    public static WorkspaceMemberResponse of(WorkspaceMember m, String role) {
        var u = m.getUser();
        return new WorkspaceMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                role, m.getJoinedAt());
    }
}
