package com.hamstrack.admin.dto;

import com.hamstrack.auth.entity.User;

import java.time.Instant;
import java.util.UUID;

/**
 * A user account as shown in the Admin console directory. {@code hasPassword}
 * is false while the account is still waiting for its setup link to be used —
 * the admin can (re)generate one.
 */
public record AdminUserResponse(
        UUID id,
        String email,
        String displayName,
        String systemRole,
        String status,
        boolean hasPassword,
        Instant createdAt
) {
    public static AdminUserResponse of(User u) {
        return new AdminUserResponse(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.getSystemRole().name(),
                u.getStatus().name(),
                u.getPasswordHash() != null && !u.getPasswordHash().isBlank(),
                u.getCreatedAt()
        );
    }
}
