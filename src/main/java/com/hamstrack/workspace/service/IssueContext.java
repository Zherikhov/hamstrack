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
}
