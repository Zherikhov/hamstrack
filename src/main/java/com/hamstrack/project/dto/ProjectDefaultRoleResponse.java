package com.hamstrack.project.dto;

import com.hamstrack.workspace.dto.SettableRolesView;
import com.hamstrack.workspace.entity.ProjectAccessMode;

import java.util.UUID;

/**
 * <strong>{@code GET /api/workspaces/{ws}/projects/{p}/default-role}</strong> (HD-130,
 * S7 §7.1 P1) — what the project's default-access picker opens against.
 *
 * <p>The two ids duplicate {@link ProjectResponse}'s {@code defaultRole} on purpose: a picker
 * dialog is worth a self-contained read, and one repeated column is cheaper than teaching the
 * dialog to reach into another query's cache.
 *
 * <p><strong>Ids only, and no name is ever added here.</strong> That is safe precisely because
 * an id is not a name: a client resolves it against {@code GET /roles}, which is
 * workspace-scoped and therefore cannot return a foreign role, and renders a placeholder when
 * it finds nothing — while the scope/ownership assertion that decides <em>access</em> still
 * runs, in {@code WorkspaceAccessService.defaultProjectRole}, on the value that actually
 * matters. If a name, key or permission summary is ever wanted here it must be resolved
 * through {@code WorkspaceAccessService.resolveRoleOrDegrade(id, RoleScope.PROJECT,
 * workspaceId, DEFAULT_PROJECT_ROLE)} and emitted as JSON {@code null} on failure — never
 * {@code roleCatalog.view(id).name()}. Emitting the name of a role the assertion just refused
 * is exactly the leak the member-list degrade (HD-127 §3b) exists to prevent, and it would
 * reach every member of the workspace.
 *
 * @param projectRoleId   this project's own override, or {@code null} when it inherits
 * @param workspaceRoleId the workspace-level default, or {@code null} when the chain falls
 *                        through to the built-in Contributor
 * @param mode            the workspace's access mode, included so the dialog can say "this
 *                        workspace is Restricted, so nothing is inherited right now — this
 *                        default applies again if it is switched back to Open" without a
 *                        second fetch. It is <em>read</em> here and owned by
 *                        {@code WorkspaceResponse}; nothing writes it through this endpoint
 * @param settable        the project-scope grant ceiling, rendered — comparand
 *                        {@code ctx.permissions()}, the actor's real effective set in this
 *                        project, with nobody exempt
 */
public record ProjectDefaultRoleResponse(
        UUID projectRoleId,
        UUID workspaceRoleId,
        ProjectAccessMode mode,
        SettableRolesView settable
) {
}
