package com.hamstrack.workspace.service;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;

/**
 * The result of {@link WorkspaceAccessService#requireIssue}: workspace membership
 * plus the resolved, managed {@link Project} and {@link Issue} (issue looked up by
 * number within the project).
 */
public record IssueContext(Workspace workspace, WorkspaceMember membership,
                           Project project, Issue issue) {

    /** The caller's role in the enclosing workspace. */
    public WorkspaceRole role() {
        return membership.getRole();
    }
}
