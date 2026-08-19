package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;

/**
 * The result of {@link WorkspaceAccessService#resolveProject}: workspace membership (as
 * in {@link WorkspaceContext}) plus the resolved, managed {@link Project} scoped to that
 * workspace, plus <strong>both</strong> permission sets, resolved once per request.
 *
 * @param workspaceRole        the caller's workspace role snapshot
 * @param workspacePermissions what that role grants at workspace scope
 * @param projectRole          the caller's <em>effective</em> project role — their
 *                             explicit {@code project_members} row if they have one, else
 *                             the project-access default chain (§5.2). {@code null} means
 *                             they have no project role at all, which happens in a
 *                             {@code STRICT} workspace or when a project's default is
 *                             deliberately set to Viewer/None
 * @param explicitProjectRole  whether {@code projectRole} came from an actual
 *                             {@code project_members} row rather than the default chain.
 *                             Nothing authorizes on it — it is for surfaces that must tell
 *                             "added to this project" apart from "inherits the default",
 *                             which is the distinction §8.4 deliberately refused to
 *                             destroy by backfilling a row per (member × project)
 * @param permissions          the effective <strong>project-scoped</strong> set: what
 *                             {@code projectRole} grants, plus every project permission if
 *                             the workspace set holds {@code project.administer.all}
 *                             (§17.2). This is the one a project-scoped call site checks
 */
public record ProjectContext(Workspace workspace, WorkspaceMember membership, Project project,
                             RoleView workspaceRole, PermissionSet workspacePermissions,
                             RoleView projectRole, boolean explicitProjectRole,
                             PermissionSet permissions) {
}
