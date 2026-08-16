package com.hamstrack.workspace.service;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;

/**
 * The result of {@link WorkspaceAccessService#requireProjectMember}: workspace
 * membership (as in {@link WorkspaceContext}) plus the resolved, managed
 * {@link Project} scoped to that workspace.
 */
public record ProjectContext(Workspace workspace, WorkspaceMember membership, Project project) {

    /** The caller's role in the enclosing workspace. */
    public WorkspaceRole role() {
        return membership.getRole();
    }
}
