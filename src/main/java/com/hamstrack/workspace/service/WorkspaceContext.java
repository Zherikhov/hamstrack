package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;

/**
 * The result of a resolve-workspace → verify-membership check
 * ({@link WorkspaceAccessService#requireMember}). Carries the resolved, managed
 * {@link Workspace}, the caller's {@link WorkspaceMember} and — since HD-123 — the
 * caller's workspace {@link PermissionSet}, resolved <strong>once per request</strong>
 * (§5.1, Rule P1). Both entities stay managed for the caller because the primitive
 * participates in the caller's transaction.
 *
 * @param workspaceRole the caller's role, as a cached immutable snapshot
 * @param permissions   what that role grants at workspace scope. Never {@code null} and
 *                      never absent — an empty set is a legitimate answer, an absent one
 *                      would make callers fall back to permissive behaviour (§12).
 */
public record WorkspaceContext(Workspace workspace, WorkspaceMember membership,
                               RoleView workspaceRole, PermissionSet permissions) {

    /**
     * The caller's role in this workspace, as the legacy ordinal enum.
     *
     * <p>Kept only so the workspace-scoped call sites that S3 has not migrated yet
     * ({@code WorkspaceService.inviteMember}, {@code LabelService.requireCurator} /
     * {@code requireEditor}, {@code ScopeResolver.requireWorkspaceAdmin}) keep compiling
     * and behaving <em>identically</em>. It throws for a custom role by construction,
     * which is the point: it cannot survive into a world where custom roles exist, and
     * S3 deletes it together with {@link WorkspaceRole}. Write new authorization against
     * {@link #permissions()}.
     */
    @Deprecated(since = "HD-123 S1")
    @SuppressWarnings("deprecation")
    public WorkspaceRole role() {
        return workspaceRole.asWorkspaceRole();
    }
}
