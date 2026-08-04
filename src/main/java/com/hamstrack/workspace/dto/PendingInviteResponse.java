package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceInvite;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending workspace invite addressed to the current user, shown on the
 * onboarding "join a team" screen so it can be accepted without the emailed
 * token link.
 */
public record PendingInviteResponse(
        UUID id,
        UUID workspaceId,
        String workspaceName,
        String role,
        String invitedByName,
        Instant createdAt,
        Instant expiresAt
) {
    public static PendingInviteResponse of(WorkspaceInvite invite) {
        return new PendingInviteResponse(
                invite.getId(),
                invite.getWorkspace().getId(),
                invite.getWorkspace().getName(),
                invite.getRole().name(),
                invite.getInvitedBy().getDisplayName(),
                invite.getCreatedAt(),
                invite.getExpiresAt()
        );
    }
}
