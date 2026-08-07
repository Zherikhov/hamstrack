package com.hamstrack.admin.service;

import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.entity.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * Shared integrity guard for a project's workflow rebind, used by BOTH the
 * system matrix ({@link AdminProjectService}) and the delegated project console
 * ({@link ScopedProjectAdminService}) so the check can't drift between them.
 *
 * <p>If the workflow binding is changing, the rebind is refused (409) while any
 * issue sits in a status the new workflow doesn't contain — it would vanish from
 * the board (which renders only workflow statuses). As a side effect it sets the
 * project's workflow to {@code newWorkflow}, since the effective-status
 * resolution reads it; callers set the remaining bindings and persist.
 */
@Component
@RequiredArgsConstructor
public class WorkflowRebindGuard {

    private final ProjectConfigService projectConfigService;
    private final IssueRepository issueRepository;

    public void check(Project project, Workflow newWorkflow) {
        var currentWorkflowId = project.getWorkflow() != null ? project.getWorkflow().getId() : null;
        var newWorkflowId = newWorkflow != null ? newWorkflow.getId() : null;
        if (Objects.equals(currentWorkflowId, newWorkflowId)) {
            return;
        }
        project.setWorkflow(newWorkflow); // set before resolving effective statuses
        var newStatuses = projectConfigService.statuses(project);
        // An empty workflow can't be bound (admin guards forbid it), but be
        // explicit: with no statuses EVERY issue is stranded — never let the
        // "status not in ()" edge silently report zero and allow the rebind.
        long stranded = newStatuses.isEmpty()
                ? issueRepository.countByProject(project)
                : issueRepository.countByProjectAndStatusNotIn(project, newStatuses);
        if (stranded > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    stranded + " issues are in statuses the new workflow doesn't contain — move them first");
        }
    }
}
