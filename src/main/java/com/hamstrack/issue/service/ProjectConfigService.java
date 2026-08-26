package com.hamstrack.issue.service;

import com.hamstrack.issue.entity.*;
import com.hamstrack.project.entity.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

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
     * Resolve a client-supplied issue type id <strong>from within this project's effective type
     * set</strong>, or 422. Only new issues and type changes are restricted — an existing issue
     * keeps a type that has since left the set.
     *
     * <p><strong>Why this resolves rather than validates, which is a tenancy fix and not a
     * refactor.</strong> It used to take an already-loaded {@code IssueType}, which
     * {@code IssueService} fetched with a bare {@code findById} across every scope, and the
     * refusal quoted {@code type.getName()}. A member of workspace A who supplied workspace B's
     * private type id therefore learned that the id existed, that it was unarchived, and what it
     * was called. Nothing cross-tenant was ever persisted — this guard refused the write — but
     * the refusal itself was the disclosure, and it was the only resolver in
     * {@code IssueService.create} that was not scoped (its parent, component, label, version and
     * sprint siblings all take the project or the workspace).
     *
     * <p>Checking the id against the list the guard was going to consult anyway collapses
     * "unknown id", "archived" and "belongs to another tenant" into one answer that can name
     * nothing. The message must stay free of the id and the name: a caller learns only that the
     * value is not usable here, which is all they can act on anyway.
     *
     * <p><strong>Returns nothing on purpose.</strong> The list it checks comes from
     * {@link ProjectConfigCache}, which hands back <em>detached</em> entities — the caller must
     * still load a managed one, and returning the cached instance would silently hand a detached
     * entity to {@code issue.setType(...)}. That trap already has a comment of its own at
     * {@code IssueService}'s default-priority line; this method is the reason it now applies to
     * all three rather than one.
     */
    @Transactional(readOnly = true)
    public void requireTypeOffered(Project project, UUID typeId) {
        boolean offered = types(project).stream()
                .anyMatch(t -> t.getId().equals(typeId) && t.getArchivedAt() == null);
        if (!offered) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That issue type is not offered by this project");
        }
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

    /**
     * Resolve a client-supplied status id from within the project's effective workflow, or 422.
     * See {@link #requireTypeOffered} for why this resolves instead of validating — the refusal
     * used to quote another tenant's status name back to the caller.
     */
    @Transactional(readOnly = true)
    public void requireStatusOffered(Project project, UUID statusId) {
        boolean offered = statuses(project).stream()
                .anyMatch(s -> s.getId().equals(statusId) && s.getArchivedAt() == null);
        if (!offered) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That status is not part of this project's workflow");
        }
    }

    /**
     * Resolve a priority id from within the project's effective priority set, or 422. See
     * {@link #requireTypeOffered} for why this resolves instead of validating.
     *
     * <p>Also the check for the <em>server-chosen</em> default ({@link #defaultPriorityId}),
     * which is drawn from this same set. That path can still 422 — if the set is empty, or if the
     * item it picks as default has since been <em>archived</em> — and both are configuration
     * faults worth surfacing rather than hiding behind a second lookup. ("Cannot 422 in practice"
     * is what this said, and it is wrong about the archived case.)
     */
    @Transactional(readOnly = true)
    public void requirePriorityOffered(Project project, UUID priorityId) {
        boolean offered = priorityItems(project).stream()
                .anyMatch(i -> i.getPriority().getId().equals(priorityId)
                               && i.getPriority().getArchivedAt() == null);
        if (!offered) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That priority is not offered by this project");
        }
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
