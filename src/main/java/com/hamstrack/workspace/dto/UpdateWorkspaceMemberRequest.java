package com.hamstrack.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/workspaces/{ws}/members/{userId}} — change an existing member's
 * workspace role (HD-132). Mirrors {@code AddProjectMemberRequest} on the project side.
 *
 * <p>{@code role} is the whole body and is {@code @NotBlank}: a PATCH with nothing to
 * change is a client bug, not a no-op worth accepting silently. Every field here is a
 * reference type, so the Jackson 3 {@code FAIL_ON_NULL_FOR_PRIMITIVES} trap
 * (a boxed-vs-primitive omission 400) cannot apply — keep it that way if the record grows.
 *
 * <p>A role <em>key</em> string rather than the deleted {@code WorkspaceRole} enum since
 * HD-126 (S3) — same wire values, and bounded to {@code roles.key VARCHAR(40)} for the
 * reason {@link InviteMemberRequest} gives: an unusable value is echoed back in the
 * problem+json detail, and the enum used to bound it implicitly.
 */
public record UpdateWorkspaceMemberRequest(
        @NotBlank @Size(max = 40) String role
) {}
