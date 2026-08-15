package com.hamstrack.workspace.service;

import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;

/**
 * The result of a resolve-workspace → verify-membership check
 * ({@link WorkspaceAccessService#requireMember}). Carries the resolved, managed
 * {@link Workspace} and the caller's {@link WorkspaceMember} (so role-gated callers
 * don't re-query the membership). Both entities stay managed for the caller because
 * the primitive participates in the caller's transaction.
 */
public record WorkspaceContext(Workspace workspace, WorkspaceMember membership) {

    /** The caller's role in this workspace. */
    public WorkspaceRole role() {
        return membership.getRole();
    }
}
