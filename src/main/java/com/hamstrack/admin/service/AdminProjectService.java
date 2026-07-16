package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.ProjectBindingResponse;
import com.hamstrack.admin.dto.UpdateProjectBindingsRequest;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueTypeSetRepository;
import com.hamstrack.issue.repository.PrioritySetRepository;
import com.hamstrack.issue.repository.WorkflowRepository;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The admin assignment matrix: every project across every workspace × its
 * taxonomy bindings. Changing a workflow is refused while the project has
 * issues in statuses the new workflow doesn't contain (they'd disappear from
 * the board). This is a system-admin surface — the usual workspace-membership
 * scoping deliberately doesn't apply, /api/admin/** is role-guarded instead.
 */
@Service
@RequiredArgsConstructor
public class AdminProjectService {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final PrioritySetRepository prioritySetRepository;
    private final FieldSetRepository fieldSetRepository;
    private final IssueTypeSetRepository issueTypeSetRepository;
    private final IssueRepository issueRepository;
    private final ProjectConfigService projectConfigService;

    @Transactional(readOnly = true)
    public List<ProjectBindingResponse> list() {
        return projectRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectBindingResponse updateBindings(UUID projectId, UpdateProjectBindingsRequest req) {
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        var newWorkflow = req.workflowId() != null
                ? workflowRepository.findByIdAndScopeWorkspaceIdIsNull(req.workflowId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown workflow"))
                : null;
        var newSet = req.prioritySetId() != null
                ? prioritySetRepository.findByIdAndScopeWorkspaceIdIsNull(req.prioritySetId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown priority set"))
                : null;
        var newFieldSet = req.fieldSetId() != null
                ? fieldSetRepository.findByIdAndScopeWorkspaceIdIsNull(req.fieldSetId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown field set"))
                : null;
        var newTypeSet = req.issueTypeSetId() != null
                ? issueTypeSetRepository.findByIdAndScopeWorkspaceIdIsNull(req.issueTypeSetId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown issue type set"))
                : null;

        // Workflow change guard: no issue may sit in a status the new workflow lacks
        var currentWorkflowId = project.getWorkflow() != null ? project.getWorkflow().getId() : null;
        var newWorkflowId = newWorkflow != null ? newWorkflow.getId() : null;
        if (!java.util.Objects.equals(currentWorkflowId, newWorkflowId)) {
            project.setWorkflow(newWorkflow); // set before resolving effective statuses
            var newStatuses = projectConfigService.statuses(project);
            long stranded = issueRepository.countByProjectAndStatusNotIn(project, newStatuses);
            if (stranded > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        stranded + " issues are in statuses the new workflow doesn't contain — move them first");
            }
        }

        project.setWorkflow(newWorkflow);
        project.setPrioritySet(newSet);
        // Field set changes freely: existing values stay stored (invisible until
        // a set containing the field is bound again) — nothing is lost
        project.setFieldSet(newFieldSet);
        // Type set changes freely too: existing issues keep their type; only
        // creation and type changes are restricted to the new set
        project.setIssueTypeSet(newTypeSet);
        projectRepository.save(project);
        return toResponse(project);
    }

    private ProjectBindingResponse toResponse(Project p) {
        return new ProjectBindingResponse(
                p.getId(), p.getKey(), p.getName(), p.isArchived(),
                p.getWorkspace().getId(), p.getWorkspace().getName(),
                p.getWorkflow() != null ? p.getWorkflow().getId() : null,
                p.getPrioritySet() != null ? p.getPrioritySet().getId() : null,
                p.getFieldSet() != null ? p.getFieldSet().getId() : null,
                p.getIssueTypeSet() != null ? p.getIssueTypeSet().getId() : null);
    }
}
