package com.hamstrack.issue.service;

import com.hamstrack.issue.entity.*;
import com.hamstrack.project.entity.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Resolves a project's <em>effective</em> taxonomy: its bound workflow and
 * priority/type sets, falling back to the system defaults when the binding is
 * NULL. All issue read/write paths and the public project-config endpoint go
 * through here — nothing else may interpret bindings.
 *
 * <p>The underlying catalog reads are cached (see {@link ProjectConfigCache} /
 * {@code CacheConfig}); this class stays the single place that maps a project to
 * its effective ids and enforces membership/transition rules against the cached
 * lists. A catalog edit propagates within the cache TTL (~60s).
 */
@Service
@RequiredArgsConstructor
public class ProjectConfigService {

    private final ProjectConfigCache cache;

    @Transactional(readOnly = true)
    public Workflow effectiveWorkflow(Project project) {
        return project.getWorkflow() != null ? project.getWorkflow() : cache.systemDefaultWorkflow();
    }

    @Transactional(readOnly = true)
    public PrioritySet effectivePrioritySet(Project project) {
        return project.getPrioritySet() != null ? project.getPrioritySet() : cache.systemDefaultPrioritySet();
    }

    @Transactional(readOnly = true)
    public IssueTypeSet effectiveTypeSet(Project project) {
        return project.getIssueTypeSet() != null ? project.getIssueTypeSet() : cache.systemDefaultTypeSet();
    }

    /** Issue types offered by the project, in the set's display order. */
    @Transactional(readOnly = true)
    public List<IssueType> types(Project project) {
        return cache.typesForSet(effectiveTypeSet(project).getId());
    }

    /**
     * 422 unless the type is offered by the project's type set. Only new
     * issues and type changes are restricted — existing issues keep a type
     * that has left the set.
     */
    @Transactional(readOnly = true)
    public IssueType requireTypeInSet(Project project, IssueType type) {
        if (types(project).stream().noneMatch(t -> t.getId().equals(type.getId()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Issue type '" + type.getName() + "' is not offered by this project");
        }
        return type;
    }

    /** Statuses of the project's workflow in board-column order. */
    @Transactional(readOnly = true)
    public List<Status> statuses(Project project) {
        return cache.statusesForWorkflow(effectiveWorkflow(project).getId());
    }

    /** Priority set items of the project in display order. */
    @Transactional(readOnly = true)
    public List<PrioritySetItem> priorityItems(Project project) {
        return cache.priorityItemsForSet(effectivePrioritySet(project).getId());
    }

    @Transactional(readOnly = true)
    public List<WorkflowTransition> transitions(Project project) {
        return cache.transitionsForWorkflow(effectiveWorkflow(project).getId());
    }

    /** 422 unless the status belongs to the project's workflow. */
    @Transactional(readOnly = true)
    public Status requireStatusInWorkflow(Project project, Status status) {
        if (statuses(project).stream().noneMatch(s -> s.getId().equals(status.getId()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Priority '" + priority.getName() + "' is not offered by this project");
        }
        return priority;
    }

    /**
     * Default priority id for new issues (first item flagged default, else first
     * item). Returns the id, not the cached entity — the caller re-resolves it to
     * a managed instance before assigning it to an issue.
     */
    @Transactional(readOnly = true)
    public java.util.UUID defaultPriorityId(Project project) {
        var items = priorityItems(project);
        if (items.isEmpty()) {
            throw new IllegalStateException("Priority set of project " + project.getId() + " is empty");
        }
        return items.stream().filter(PrioritySetItem::isDefaultForNewIssues)
                .findFirst().orElse(items.getFirst()).getPriority().getId();
    }

    /**
     * Workflow-rule check for a status move. A source status with no
     * source-specific rules is open (any move allowed — matches pre-M1
     * behavior); once it has rules, only its listed targets plus wildcard
     * ("Any → X") targets are accepted. Wildcards grant, never restrict.
     */
    @Transactional(readOnly = true)
    public void validateTransition(Project project, Status from, Status to) {
        var all = transitions(project);
        boolean restricted = all.stream()
                .anyMatch(t -> t.getFromStatus() != null && t.getFromStatus().getId().equals(from.getId()));
        if (!restricted) return;
        boolean ok = all.stream()
                .filter(t -> t.getFromStatus() == null || t.getFromStatus().getId().equals(from.getId()))
                .anyMatch(t -> t.getToStatus().getId().equals(to.getId()));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Transition from '" + from.getName() + "' to '" + to.getName()
                            + "' is not allowed by the workflow");
        }
    }
}
