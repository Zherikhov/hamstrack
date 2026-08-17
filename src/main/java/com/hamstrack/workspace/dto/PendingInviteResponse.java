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
    /**
     * @param roleKey the invited role's key ({@code "MEMBER"}) — the built-in keys are the
     *     legacy enum names, so this string is byte-identical to what this response
     *     carried before HD-123 turned {@code workspace_invites.role} into a
     *     {@code roles} row.
     */
    public static PendingInviteResponse of(WorkspaceInvite invite, String roleKey) {
        return new PendingInviteResponse(
                invite.getId(),
                invite.getWorkspace().getId(),
                invite.getWorkspace().getName(),
                roleKey,
                invite.getInvitedBy().getDisplayName(),
                invite.getCreatedAt(),
                invite.getExpiresAt()
        );
    }
}
