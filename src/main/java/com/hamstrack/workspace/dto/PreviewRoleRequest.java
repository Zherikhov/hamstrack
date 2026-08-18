package com.hamstrack.workspace.dto;

import com.hamstrack.common.security.RoleScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /api/workspaces/{ws}/roles/preview} — <strong>persists nothing</strong>.
 *
 * <p>Answers {@code {"assignment": …}} for a permission list that does not exist yet, so
 * the editor can show the consequence while the checkboxes are being ticked rather than
 * after Save. It produces the same 422s a real write would for an unknown key, a key of the
 * wrong scope or an illegal {@code ownOnly}, which is half the value: the preview and the
 * write agree because they run the same validation.
 *
 * @param scope <strong>required here</strong>, unlike on the update DTO: there is no stored
 *              role to take it from. This is not a way to change a role's scope — nothing
 *              is stored
 */
public record PreviewRoleRequest(
        @NotNull RoleScope scope,
        @Valid @Size(max = 64) List<RolePermissionEntry> permissions
) {}
