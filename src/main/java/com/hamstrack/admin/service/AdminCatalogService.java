package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catalog CRUD (statuses, priorities, issue types) for every admin console,
 * scoped by a {@link ScopeContext}: global (system admin), workspace-scoped
 * (workspace OWNER/ADMIN) or project-private (project MANAGER). Rows are created
 * stamped with the scope and only rows at the caller's scope resolve for
 * edit/delete — inherited rows are invisible here (they're selected, not edited).
 *
 * <p>Deletion never leaves dangling references: entries used by issues require a
 * replacement (remap) or can be archived; workflow/set memberships are validated
 * so no workflow ends up empty and no set loses its default. Usage and integrity
 * checks span all scopes — a global status may be used by workflows anywhere.
 */
@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private final StatusRepository statusRepository;
    private final PriorityRepository priorityRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final IssueRepository issueRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final PrioritySetItemRepository prioritySetItemRepository;
    private final IssueTypeSetItemRepository issueTypeSetItemRepository;
    private final ProjectCountService projectCountService;

    // ---------- statuses ----------

    @Transactional(readOnly = true)
    public List<AdminStatusResponse> listStatuses(ScopeContext scope) {
        // Delegated consoles also SEE inherited (global/workspace) rows — read-only,
        // tagged by scope — so a project admin sees what the project already uses;
        // only own-scope rows are editable (enforced by findByIdAtScope on write).
        var rows = scope.isGlobal()
                ? statusRepository.findAllAtScope(null, null)
                : statusRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(s -> AdminStatusResponse.of(s, statusUsage(scope, s))).toList();
    }

    @Transactional
    public AdminStatusResponse createStatus(ScopeContext scope, UpsertStatusRequest req) {
        if (statusRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A status named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var s = new Status();
        scope.stamp(s);
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        s.setPosition(req.position() != null ? req.position() : nextPosition(
                statusRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(Status::getPosition).max().orElse(-1)));
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(scope, s));
    }

    @Transactional
    public AdminStatusResponse updateStatus(ScopeContext scope, UUID id, UpsertStatusRequest req) {
        var s = requireStatus(scope, id);
        if (!s.getName().equals(req.name())
                && statusRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A status named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        if (req.position() != null) s.setPosition(req.position());
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(scope, s));
    }

    @Transactional
    public void setStatusArchived(ScopeContext scope, UUID id, boolean archived) {
        var s = requireStatus(scope, id);
        s.setArchivedAt(archived ? Instant.now() : null);
        statusRepository.save(s);
    }

    /**
     * Delete with optional remap. Refused (409) while issues reference the
     * status and no replacement is given, or when removing it would leave a
     * workflow empty. Workflow memberships/transitions are cleaned up by FK
     * cascade after issues are remapped.
     */
    @Transactional
    public void deleteStatus(ScopeContext scope, UUID id, UUID replaceWithId) {
        var s = requireStatus(scope, id);
        long issues = issueRepository.countByStatus(s);
        if (issues > 0 && replaceWithId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    issues + " issues use this status — pass replaceWithId to remap them, or archive instead");
        }
        for (var wf : workflowStatusRepository.findWorkflowsUsingStatus(id)) {
            if (workflowStatusRepository.findAllByWorkflowOrderByPosition(wf).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Workflow '" + wf.getName() + "' would be left without statuses");
            }
        }
        if (replaceWithId != null) {
            var replacement = statusRepository.findById(replaceWithId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement status not found"));
            if (replacement.getId().equals(s.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            // Every workflow that contains the deleted status must offer the
            // replacement, otherwise remapped issues would leave their board
            for (var wf : workflowStatusRepository.findWorkflowsUsingStatus(id)) {
                if (!workflowStatusRepository.existsByWorkflowAndStatus(wf, replacement)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Replacement status is not part of workflow '" + wf.getName() + "'");
                }
            }
            issueRepository.remapStatus(s, replacement);
        }
        statusRepository.delete(s);
    }

    // ---------- priorities ----------

    @Transactional(readOnly = true)
    public List<AdminPriorityResponse> listPriorities(ScopeContext scope) {
        var rows = scope.isGlobal()
                ? priorityRepository.findAllAtScope(null, null)
                : priorityRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(p -> AdminPriorityResponse.of(p, priorityUsage(scope, p))).toList();
    }

    @Transactional
    public AdminPriorityResponse createPriority(ScopeContext scope, UpsertPriorityRequest req) {
        if (priorityRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A priority named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var p = new Priority();
        scope.stamp(p);
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        p.setPosition(req.position() != null ? req.position() : nextPosition(
                priorityRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(Priority::getPosition).max().orElse(-1)));
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(scope, p));
    }

    @Transactional
    public AdminPriorityResponse updatePriority(ScopeContext scope, UUID id, UpsertPriorityRequest req) {
        var p = requirePriority(scope, id);
        if (!p.getName().equals(req.name())
                && priorityRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A priority named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        if (req.position() != null) p.setPosition(req.position());
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(scope, p));
    }

    @Transactional
    public void setPriorityArchived(ScopeContext scope, UUID id, boolean archived) {
        var p = requirePriority(scope, id);
        p.setArchivedAt(archived ? Instant.now() : null);
        priorityRepository.save(p);
    }

    @Transactional
    public void deletePriority(ScopeContext scope, UUID id, UUID replaceWithId) {
        var p = requirePriority(scope, id);
        long issues = issueRepository.countByPriority(p);
        if (issues > 0 && replaceWithId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    issues + " issues use this priority — pass replaceWithId to remap them, or archive instead");
        }
        var sets = prioritySetItemRepository.findSetsUsingPriority(id);
        for (var set : sets) {
            if (prioritySetItemRepository.findAllBySetOrderByPosition(set).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Priority set '" + set.getName() + "' would be left empty");
            }
        }
        Priority replacement = null;
        if (replaceWithId != null) {
            replacement = priorityRepository.findById(replaceWithId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement priority not found"));
            if (replacement.getId().equals(p.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            issueRepository.remapPriority(p, replacement);
        }
        // Keep every affected set with a default: hand the flag to the
        // replacement (if member) or to the first remaining item
        for (var set : sets) {
            var items = prioritySetItemRepository.findAllBySetOrderByPosition(set);
            var deletedItem = items.stream()
                    .filter(i -> i.getPriority().getId().equals(p.getId())).findFirst().orElse(null);
            if (deletedItem != null && deletedItem.isDefaultForNewIssues()) {
                var remaining = items.stream()
                        .filter(i -> !i.getPriority().getId().equals(p.getId())).toList();
                var repl = replacement;
                var heir = remaining.stream()
                        .filter(i -> repl != null && i.getPriority().getId().equals(repl.getId()))
                        .findFirst().orElse(remaining.getFirst());
                heir.setDefaultForNewIssues(true);
                prioritySetItemRepository.save(heir);
            }
        }
        prioritySetItemRepository.deleteAllByPriority(p);
        priorityRepository.delete(p);
    }

    // ---------- issue types ----------

    @Transactional(readOnly = true)
    public List<AdminIssueTypeResponse> listIssueTypes(ScopeContext scope) {
        var rows = scope.isGlobal()
                ? issueTypeRepository.findAllAtScope(null, null)
                : issueTypeRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(t -> AdminIssueTypeResponse.of(t, issueTypeUsage(scope, t))).toList();
    }

    @Transactional
    public AdminIssueTypeResponse createIssueType(ScopeContext scope, UpsertIssueTypeRequest req) {
        if (issueTypeRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An issue type named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        var t = new IssueType();
        scope.stamp(t);
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        t.setPosition(req.position() != null ? req.position() : nextPosition(
                issueTypeRepository.findAllAtScope(scope.workspaceId(), scope.projectId()).stream()
                        .mapToInt(IssueType::getPosition).max().orElse(-1)));
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, new UsageInfo(0, 0, 0, 0));
    }

    @Transactional
    public AdminIssueTypeResponse updateIssueType(ScopeContext scope, UUID id, UpsertIssueTypeRequest req) {
        var t = requireIssueType(scope, id);
        if (!t.getName().equals(req.name())
                && issueTypeRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An issue type named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        if (req.position() != null) t.setPosition(req.position());
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, issueTypeUsage(scope, t));
    }

    @Transactional
    public void setIssueTypeArchived(ScopeContext scope, UUID id, boolean archived) {
        var t = requireIssueType(scope, id);
        t.setArchivedAt(archived ? Instant.now() : null);
        issueTypeRepository.save(t);
    }

    @Transactional
    public void deleteIssueType(ScopeContext scope, UUID id, UUID replaceWithId) {
        var t = requireIssueType(scope, id);
        long issues = issueRepository.countByType(t);
        if (issues > 0 && replaceWithId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    issues + " issues use this type — pass replaceWithId to remap them, or archive instead");
        }
        // The base (global) catalog must never be emptied; inherited types cover
        // delegated scopes, so they may delete their last own type freely.
        if (scope.isGlobal() && issueTypeRepository.findAllAtScope(null, null).size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At least one issue type must remain");
        }
        for (var set : issueTypeSetItemRepository.findSetsUsingType(id)) {
            if (issueTypeSetItemRepository.findAllBySetOrderByPosition(set).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Issue type set '" + set.getName() + "' would be left empty");
            }
        }
        if (replaceWithId != null) {
            var replacement = issueTypeRepository.findById(replaceWithId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Replacement type not found"));
            if (replacement.getId().equals(t.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Replacement must differ");
            }
            issueRepository.remapType(t, replacement);
        }
        // Set memberships are cleaned up by FK cascade
        issueTypeRepository.delete(t);
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse statusUsageDetail(ScopeContext scope, UUID id) {
        var s = requireStatus(scope, id);
        var workflows = workflowStatusRepository.findWorkflowsUsingStatus(s.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = workflows.stream()
                .flatMap(wf -> projectCountService.projectsListUsingWorkflow(scope, wf).stream())
                .toList();
        return new UsageDetailResponse(
                workflows.stream().map(Workflow::getName).toList(),
                List.of(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByStatusScoped(s, scope.workspaceId(), scope.projectId()));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse priorityUsageDetail(ScopeContext scope, UUID id) {
        var p = requirePriority(scope, id);
        var sets = prioritySetItemRepository.findSetsUsingPriority(p.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingPrioritySet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(PrioritySet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByPriorityScoped(p, scope.workspaceId(), scope.projectId()));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse issueTypeUsageDetail(ScopeContext scope, UUID id) {
        var t = requireIssueType(scope, id);
        var sets = issueTypeSetItemRepository.findSetsUsingType(t.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingIssueTypeSet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(IssueTypeSet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByTypeScoped(t, scope.workspaceId(), scope.projectId()));
    }

    // ---------- helpers ----------

    // Usage is aggregated only over containers (workflows/sets) the scope can
    // see and only over projects/issues within the scope — so a delegated
    // console never reports figures spanning other tenants (see ScopeContext.canSee).
    private UsageInfo statusUsage(ScopeContext scope, Status s) {
        var workflows = workflowStatusRepository.findWorkflowsUsingStatus(s.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = workflows.stream()
                .mapToLong(wf -> projectCountService.projectsUsingWorkflow(scope, wf)).sum();
        return new UsageInfo(workflows.size(), 0, projects,
                issueRepository.countByStatusScoped(s, scope.workspaceId(), scope.projectId()));
    }

    private UsageInfo priorityUsage(ScopeContext scope, Priority p) {
        var sets = prioritySetItemRepository.findSetsUsingPriority(p.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingPrioritySet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                issueRepository.countByPriorityScoped(p, scope.workspaceId(), scope.projectId()));
    }

    private UsageInfo issueTypeUsage(ScopeContext scope, IssueType t) {
        var sets = issueTypeSetItemRepository.findSetsUsingType(t.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingIssueTypeSet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                issueRepository.countByTypeScoped(t, scope.workspaceId(), scope.projectId()));
    }

    private short nextPosition(int currentMax) {
        return (short) (currentMax + 1);
    }

    private Status requireStatus(ScopeContext scope, UUID id) {
        return statusRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
    }

    private Priority requirePriority(ScopeContext scope, UUID id) {
        return priorityRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Priority not found"));
    }

    private IssueType requireIssueType(ScopeContext scope, UUID id) {
        return issueTypeRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue type not found"));
    }
}
