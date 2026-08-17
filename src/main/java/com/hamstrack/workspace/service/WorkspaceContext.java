package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;

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
}
