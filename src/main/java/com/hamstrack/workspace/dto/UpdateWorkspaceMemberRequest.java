package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/workspaces/{ws}/members/{userId}} — change an existing member's
 * workspace role (HD-132). Mirrors {@code AddProjectMemberRequest} on the project side.
 *
 * <p>{@code role} is the whole body and is {@code @NotNull}: a PATCH with nothing to
 * change is a client bug, not a no-op worth accepting silently. Every field here is a
 * reference type, so the Jackson 3 {@code FAIL_ON_NULL_FOR_PRIMITIVES} trap
 * (a boxed-vs-primitive omission 400) cannot apply — keep it that way if the record grows.
 */
public record UpdateWorkspaceMemberRequest(
        @NotNull WorkspaceRole role
) {}
