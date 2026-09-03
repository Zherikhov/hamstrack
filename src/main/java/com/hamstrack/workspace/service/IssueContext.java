package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;

/**
 * The result of {@link WorkspaceAccessService#requireIssue}: workspace membership plus
 * the resolved, managed {@link Project} and {@link Issue} (issue looked up by number
 * within the project).
 *
 * <p>Carries the enclosing project's {@link PermissionSet} <strong>unchanged</strong>
 * (§9.1). There is no issue-level permission set and there must not be one: the ownership
 * modifier (§6.4) is a qualifier the call site applies — {@code permissions().has(
 * ISSUE_EDIT, issue.getReporter().equals(actor))} — not a per-issue grant. Issue-level
 * security is an explicit non-goal (§3).
 */
public record IssueContext(Workspace workspace, WorkspaceMember membership,
                           Project project, Issue issue,
                           RoleView workspaceRole, PermissionSet workspacePermissions,
                           RoleView projectRole, PermissionSet permissions) {

    /**
     * The workspace half of this resolution, as the type a workspace-scoped collaborator can
     * demand instead of a bare id (HD-191 R4).
     *
     * <p>It is a view, not a second resolution: every component is one this record already
     * carries, so it costs no query and cannot disagree with the issue it came from. The point is
     * that a service like {@code WorkspaceStorageService.reserve} — which reads a tenant-wide
     * aggregate and puts it in a response body — can require PROOF of membership in its signature.
     * A {@code UUID} parameter is satisfied by an id taken straight from a URL; this is satisfied
     * only by {@link WorkspaceAccessService}.
     *
     * <p>{@code workspacePermissions()} and not {@code permissions()}: the latter is the enclosing
     * PROJECT's set, and a workspace-scoped caller handed it would authorize a workspace decision
     * with a project grant.
     */
    public WorkspaceContext workspaceContext() {
        return new WorkspaceContext(workspace, membership, workspaceRole, workspacePermissions);
    }
}
