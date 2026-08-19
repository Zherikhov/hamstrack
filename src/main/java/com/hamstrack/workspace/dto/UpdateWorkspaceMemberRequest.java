package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code PATCH /api/workspaces/{ws}/members/{userId}} — change an existing member's
 * workspace role (HD-132). Mirrors {@link com.hamstrack.project.dto.AddProjectMemberRequest}
 * on the project side.
 *
 * <p><strong>Exactly one of {@code roleId} and {@code role}</strong> — 422
 * ({@code RoleSelectionException}) for neither and for both. Neither is {@code @NotBlank}
 * any more precisely because the other may carry the answer; the "a PATCH with nothing to
 * change is a client bug" rule is now enforced by that 422 rather than by bean validation,
 * which would have answered 400 for a well-formed body whose only fault is a missing
 * <em>value</em>. See {@link InviteMemberRequest} for why both fields exist.
 *
 * <p>Every field here is a reference type, so the Jackson 3
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} trap (a boxed-vs-primitive omission 400) cannot
 * apply — keep it that way if the record grows.
 *
 * @param roleId the assignable role's id, resolved through
 *               {@code findAssignable(id, workspaceId, WORKSPACE)} — the only way to name a
 *               custom role
 * @param role   the legacy role <em>key</em>, built-ins only. Same wire values as the
 *               deleted enum, bounded to {@code roles.key VARCHAR(40)} for the reason
 *               {@link InviteMemberRequest} gives
 */
public record UpdateWorkspaceMemberRequest(
        UUID roleId,
        @Size(max = 40) String role
) {}
