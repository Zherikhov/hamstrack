package com.hamstrack.admin.service;

import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * "Used by N projects" counters and "where is this used?" listings, scoped to
 * the caller's {@link ScopeContext}. A global scope (system admin / DC) counts
 * across the whole install; a delegated (workspace/project) scope counts only
 * projects the caller can see, so a tenant never learns usage spanning other
 * tenants. Projects with a NULL binding implicitly use the system default, so
 * defaults also count the unbound projects — but only within the same scope.
 */
@Service
@RequiredArgsConstructor
public class ProjectCountService {

    private final ProjectRepository projectRepository;

    public long projectsUsingWorkflow(ScopeContext scope, Workflow workflow) {
        return projectRepository.countProjectsUsingWorkflow(
                workflow.getId(), workflow.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public long projectsUsingPrioritySet(ScopeContext scope, PrioritySet set) {
        return projectRepository.countProjectsUsingPrioritySet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public long projectsUsingIssueTypeSet(ScopeContext scope, IssueTypeSet set) {
        return projectRepository.countProjectsUsingIssueTypeSet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public long projectsUsingFieldSet(ScopeContext scope, FieldSet set) {
        return projectRepository.countProjectsUsingFieldSet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    // ---- listings for the "where is this used?" popovers ----

    public List<Project> projectsListUsingWorkflow(ScopeContext scope, Workflow workflow) {
        return projectRepository.findProjectsUsingWorkflow(
                workflow.getId(), workflow.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public List<Project> projectsListUsingPrioritySet(ScopeContext scope, PrioritySet set) {
        return projectRepository.findProjectsUsingPrioritySet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public List<Project> projectsListUsingFieldSet(ScopeContext scope, FieldSet set) {
        return projectRepository.findProjectsUsingFieldSet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }

    public List<Project> projectsListUsingIssueTypeSet(ScopeContext scope, IssueTypeSet set) {
        return projectRepository.findProjectsUsingIssueTypeSet(
                set.getId(), set.isSystemDefault(), scope.workspaceId(), scope.projectId());
    }
}
