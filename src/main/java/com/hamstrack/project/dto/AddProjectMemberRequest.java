package com.hamstrack.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param role the project role key — {@code "MANAGER"} / {@code "MEMBER"} /
 *             {@code "COMMENTER"} / {@code "VIEWER"}. A plain string since HD-126 (S3)
 *             deleted the ordinal {@code ProjectRole} enum; the wire values are exactly
 *             the ones the enum serialised as, and an unusable value is now
 *             <strong>422 "Unknown role"</strong> rather than Jackson's 400 (§12 —
 *             one answer for every role reference that cannot be honoured).
 *             {@code "VIEWER"} is still stored as Contributor; see
 *             {@code ProjectService.addMember}. Bounded to {@code roles.key VARCHAR(40)}
 *             for the reason {@code InviteMemberRequest} gives — the value is echoed back
 *             in the problem+json detail, and the enum used to bound it implicitly.
 */
public record AddProjectMemberRequest(
        @NotNull UUID userId,
        @NotBlank @Size(max = 40) String role
) {}
