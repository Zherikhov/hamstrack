package com.hamstrack.issue.service;

import com.hamstrack.issue.entity.*;
import com.hamstrack.issue.repository.*;
import com.hamstrack.project.entity.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Resolves a project's <em>effective</em> taxonomy: its bound workflow and
 * priority set, falling back to the system defaults when the binding is NULL.
 * All issue read/write paths and the public project-config endpoint go
 * through here — nothing else may interpret bindings.
 */
@Service
@RequiredArgsConstructor
public class ProjectConfigService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final PrioritySetRepository prioritySetRepository;
    private final PrioritySetItemRepository prioritySetItemRepository;
    private final IssueTypeSetRepository issueTypeSetRepository;
    private final IssueTypeSetItemRepository issueTypeSetItemRepository;

    @Transactional(readOnly = true)
    public Workflow effectiveWorkflow(Project project) {
        if (project.getWorkflow() != null) return project.getWorkflow();
        return workflowRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default workflow is missing"));
    }

    @Transactional(readOnly = true)
    public PrioritySet effectivePrioritySet(Project project) {
        if (project.getPrioritySet() != null) return project.getPrioritySet();
        return prioritySetRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default priority set is missing"));
    }

    @Transactional(readOnly = true)
    public IssueTypeSet effectiveTypeSet(Project project) {
        if (project.getIssueTypeSet() != null) return project.getIssueTypeSet();
        return issueTypeSetRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default issue type set is missing"));
    }

    /** Issue types offered by the project, in the set's display order. */
    @Transactional(readOnly = true)
    public List<IssueType> types(Project project) {
        return issueTypeSetItemRepository.findAllBySetOrderByPosition(effectiveTypeSet(project))
                .stream().map(IssueTypeSetItem::getType).toList();
    }

    /**
     * 422 unless the type is offered by the project's type set. Only new
     * issues and type changes are restricted — existing issues keep a type
     * that has left the set.
     */
    @Transactional(readOnly = true)
    public IssueType requireTypeInSet(Project project, IssueType type) {
        if (!issueTypeSetItemRepository.existsBySetAndType(effectiveTypeSet(project), type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Issue type '" + type.getName() + "' is not offered by this project");
        }
        return type;
    }

    /** Statuses of the project's workflow in board-column order. */
    @Transactional(readOnly = true)
    public List<Status> statuses(Project project) {
        return workflowStatusRepository.findAllByWorkflowOrderByPosition(effectiveWorkflow(project))
                .stream().map(WorkflowStatus::getStatus).toList();
    }

    /** Priority set items of the project in display order. */
    @Transactional(readOnly = true)
    public List<PrioritySetItem> priorityItems(Project project) {
        return prioritySetItemRepository.findAllBySetOrderByPosition(effectivePrioritySet(project));
    }

    @Transactional(readOnly = true)
    public List<WorkflowTransition> transitions(Project project) {
        return workflowTransitionRepository.findAllByWorkflow(effectiveWorkflow(project));
    }

    /** 422 unless the status belongs to the project's workflow. */
    @Transactional(readOnly = true)
    public Status requireStatusInWorkflow(Project project, Status status) {
        var workflow = effectiveWorkflow(project);
        if (!workflowStatusRepository.existsByWorkflowAndStatus(workflow, status)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Status '" + status.getName() + "' is not part of this project's workflow");
        }
        return status;
    }

    /** 422 unless the priority is offered by the project's priority set. */
    @Transactional(readOnly = true)
    public Priority requirePriorityInSet(Project project, Priority priority) {
        boolean offered = priorityItems(project).stream()
                .anyMatch(i -> i.getPriority().getId().equals(priority.getId()));
        if (!offered) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Priority '" + priority.getName() + "' is not offered by this project");
        }
        return priority;
    }

    /** Default priority for new issues (first item flagged default, else first item). */
    @Transactional(readOnly = true)
    public Priority defaultPriority(Project project) {
        var items = priorityItems(project);
        if (items.isEmpty()) {
            throw new IllegalStateException("Priority set of project " + project.getId() + " is empty");
        }
        return items.stream().filter(PrioritySetItem::isDefaultForNewIssues)
                .findFirst().orElse(items.get(0)).getPriority();
    }

    /**
     * Workflow-rule check for a status move. A source status with no
     * source-specific rules is open (any move allowed — matches pre-M1
     * behavior); once it has rules, only its listed targets plus wildcard
     * ("Any → X") targets are accepted. Wildcards grant, never restrict.
     */
    @Transactional(readOnly = true)
    public void validateTransition(Project project, Status from, Status to) {
        var workflow = effectiveWorkflow(project);
        var all = workflowTransitionRepository.findAllByWorkflow(workflow);
        boolean restricted = all.stream()
                .anyMatch(t -> t.getFromStatus() != null && t.getFromStatus().getId().equals(from.getId()));
        if (!restricted) return;
        boolean ok = all.stream()
                .filter(t -> t.getFromStatus() == null || t.getFromStatus().getId().equals(from.getId()))
                .anyMatch(t -> t.getToStatus().getId().equals(to.getId()));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Transition from '" + from.getName() + "' to '" + to.getName()
                            + "' is not allowed by the workflow");
        }
    }
}
