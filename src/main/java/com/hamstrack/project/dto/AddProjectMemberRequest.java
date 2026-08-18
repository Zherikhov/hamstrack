package com.hamstrack.project.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * <strong>Exactly one of {@code roleId} and {@code role}</strong> — 422
 * ({@code RoleSelectionException}) for neither and for both. See
 * {@link com.hamstrack.workspace.dto.InviteMemberRequest} for why both exist transitionally.
 *
 * @param roleId the assignable PROJECT role's id, resolved through
 *               {@code RoleRepository.findAssignable(id, workspaceId, PROJECT)}. <strong>This
 *               path does NOT translate.</strong> The legacy {@code role} key maps
 *               {@code "VIEWER"} onto Contributor (see {@code ProjectService.addMember} for
 *               why that translation had to exist), so until now a genuinely read-only
 *               project member was <em>inexpressible</em>. Naming the built-in Viewer by id
 *               stores the built-in Viewer — which is precisely what {@code addMember}'s
 *               javadoc promised S4 would make possible. A foreign, WORKSPACE-scoped or
 *               unknown id is one indistinguishable <strong>422</strong>
 * @param role   the legacy project role key — {@code "MANAGER"} / {@code "MEMBER"} /
 *               {@code "COMMENTER"} / {@code "VIEWER"}. A plain string since HD-126 (S3)
 *               deleted the ordinal {@code ProjectRole} enum; the wire values are exactly the
 *               ones the enum serialised as, and an unusable value is
 *               <strong>422 "Unknown role"</strong> rather than Jackson's 400 (§12 — one
 *               answer for every role reference that cannot be honoured). {@code "VIEWER"} is
 *               still stored as Contributor on <em>this</em> field. Bounded to
 *               {@code roles.key VARCHAR(40)} for the reason {@code InviteMemberRequest}
 *               gives — the value is echoed back in the problem+json detail, and the enum
 *               used to bound it implicitly
 */
public record AddProjectMemberRequest(
        @NotNull UUID userId,
        UUID roleId,
        @Size(max = 40) String role
) {}
