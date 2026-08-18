package com.hamstrack.project.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code PATCH /api/workspaces/{ws}/projects/{p}/members/{userId}} — change an existing
 * project member's role (HD-127, M4). The project twin of
 * {@link com.hamstrack.workspace.dto.UpdateWorkspaceMemberRequest}, and the endpoint whose
 * absence meant a project role could only be corrected by removing the row and adding it
 * back — two calls that each strand-check separately and, in an {@code OPEN} workspace, drop
 * the member onto the default role in between.
 *
 * <p><strong>{@code roleId} only, and required.</strong> There is no legacy {@code role} key
 * here because there is no legacy client: the endpoint is new, so nothing is mid-migration
 * and accepting a key would import the {@code VIEWER → Contributor} translation into a
 * surface that never had it.
 *
 * <p>The single field is a reference type, so the Jackson 3
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} trap cannot apply — keep it that way if the record
 * grows.
 */
public record UpdateProjectMemberRequest(
        @NotNull UUID roleId
) {}
