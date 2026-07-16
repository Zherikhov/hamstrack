package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
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
 * Global catalog CRUD (statuses, priorities, issue types) for the admin
 * console. Deletion never leaves dangling references: entries used by issues
 * require a replacement (remap) or can be archived instead; workflow/set
 * memberships are validated so no workflow ends up empty and no set loses
 * its default.
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
    public List<AdminStatusResponse> listStatuses() {
        return statusRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                .map(s -> AdminStatusResponse.of(s, statusUsage(s)))
                .toList();
    }

    @Transactional
    public AdminStatusResponse createStatus(UpsertStatusRequest req) {
        if (statusRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Status name already exists");
        }
        var s = new Status();
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        s.setPosition(req.position() != null ? req.position() : nextPosition(
                statusRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                        .mapToInt(Status::getPosition).max().orElse(-1)));
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(s));
    }

    @Transactional
    public AdminStatusResponse updateStatus(UUID id, UpsertStatusRequest req) {
        var s = requireStatus(id);
        if (!s.getName().equals(req.name())
                && statusRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Status name already exists");
        }
        s.setName(req.name());
        s.setCategory(req.category());
        if (req.color() != null) s.setColor(req.color());
        if (req.position() != null) s.setPosition(req.position());
        statusRepository.save(s);
        return AdminStatusResponse.of(s, statusUsage(s));
    }

    @Transactional
    public void setStatusArchived(UUID id, boolean archived) {
        var s = requireStatus(id);
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
    public void deleteStatus(UUID id, UUID replaceWithId) {
        var s = requireStatus(id);
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
            var replacement = requireStatus(replaceWithId);
            if (replacement.getId().equals(s.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Replacement must differ");
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
    public List<AdminPriorityResponse> listPriorities() {
        return priorityRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                .map(p -> AdminPriorityResponse.of(p, priorityUsage(p)))
                .toList();
    }

    @Transactional
    public AdminPriorityResponse createPriority(UpsertPriorityRequest req) {
        if (priorityRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Priority name already exists");
        }
        var p = new Priority();
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        p.setPosition(req.position() != null ? req.position() : nextPosition(
                priorityRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                        .mapToInt(Priority::getPosition).max().orElse(-1)));
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(p));
    }

    @Transactional
    public AdminPriorityResponse updatePriority(UUID id, UpsertPriorityRequest req) {
        var p = requirePriority(id);
        if (!p.getName().equals(req.name())
                && priorityRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Priority name already exists");
        }
        p.setName(req.name());
        if (req.color() != null) p.setColor(req.color());
        p.setIcon(req.icon());
        if (req.position() != null) p.setPosition(req.position());
        priorityRepository.save(p);
        return AdminPriorityResponse.of(p, priorityUsage(p));
    }

    @Transactional
    public void setPriorityArchived(UUID id, boolean archived) {
        var p = requirePriority(id);
        p.setArchivedAt(archived ? Instant.now() : null);
        priorityRepository.save(p);
    }

    @Transactional
    public void deletePriority(UUID id, UUID replaceWithId) {
        var p = requirePriority(id);
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
            replacement = requirePriority(replaceWithId);
            if (replacement.getId().equals(p.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Replacement must differ");
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
                        .findFirst().orElse(remaining.get(0));
                heir.setDefaultForNewIssues(true);
                prioritySetItemRepository.save(heir);
            }
        }
        prioritySetItemRepository.deleteAllByPriority(p);
        priorityRepository.delete(p);
    }

    // ---------- issue types ----------

    @Transactional(readOnly = true)
    public List<AdminIssueTypeResponse> listIssueTypes() {
        return issueTypeRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                .map(t -> AdminIssueTypeResponse.of(t, issueTypeUsage(t)))
                .toList();
    }

    @Transactional
    public AdminIssueTypeResponse createIssueType(UpsertIssueTypeRequest req) {
        if (issueTypeRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type name already exists");
        }
        var t = new IssueType();
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        t.setPosition(req.position() != null ? req.position() : nextPosition(
                issueTypeRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                        .mapToInt(IssueType::getPosition).max().orElse(-1)));
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, new UsageInfo(0, 0, 0, 0));
    }

    @Transactional
    public AdminIssueTypeResponse updateIssueType(UUID id, UpsertIssueTypeRequest req) {
        var t = requireIssueType(id);
        if (!t.getName().equals(req.name())
                && issueTypeRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type name already exists");
        }
        t.setName(req.name());
        if (req.color() != null) t.setColor(req.color());
        t.setIcon(req.icon());
        if (req.position() != null) t.setPosition(req.position());
        issueTypeRepository.save(t);
        return AdminIssueTypeResponse.of(t, issueTypeUsage(t));
    }

    @Transactional
    public void setIssueTypeArchived(UUID id, boolean archived) {
        var t = requireIssueType(id);
        t.setArchivedAt(archived ? Instant.now() : null);
        issueTypeRepository.save(t);
    }

    @Transactional
    public void deleteIssueType(UUID id, UUID replaceWithId) {
        var t = requireIssueType(id);
        long issues = issueRepository.countByType(t);
        if (issues > 0 && replaceWithId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    issues + " issues use this type — pass replaceWithId to remap them, or archive instead");
        }
        if (issueTypeRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At least one issue type must remain");
        }
        for (var set : issueTypeSetItemRepository.findSetsUsingType(id)) {
            if (issueTypeSetItemRepository.findAllBySetOrderByPosition(set).size() <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Issue type set '" + set.getName() + "' would be left empty");
            }
        }
        if (replaceWithId != null) {
            var replacement = requireIssueType(replaceWithId);
            if (replacement.getId().equals(t.getId())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Replacement must differ");
            }
            issueRepository.remapType(t, replacement);
        }
        // Set memberships are cleaned up by FK cascade
        issueTypeRepository.delete(t);
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse statusUsageDetail(UUID id) {
        var s = requireStatus(id);
        var workflows = workflowStatusRepository.findWorkflowsUsingStatus(s.getId());
        var projects = workflows.stream()
                .flatMap(wf -> projectCountService.projectsListUsingWorkflow(wf).stream())
                .toList();
        return new UsageDetailResponse(
                workflows.stream().map(w -> w.getName()).toList(),
                List.of(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByStatus(s));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse priorityUsageDetail(UUID id) {
        var p = requirePriority(id);
        var sets = prioritySetItemRepository.findSetsUsingPriority(p.getId());
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingPrioritySet(set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(s -> s.getName()).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByPriority(p));
    }

    @Transactional(readOnly = true)
    public UsageDetailResponse issueTypeUsageDetail(UUID id) {
        var t = requireIssueType(id);
        var sets = issueTypeSetItemRepository.findSetsUsingType(t.getId());
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingIssueTypeSet(set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(s -> s.getName()).toList(),
                UsageDetailResponse.dedupe(projects),
                issueRepository.countByType(t));
    }

    // ---------- helpers ----------

    private UsageInfo statusUsage(Status s) {
        long workflows = workflowStatusRepository.countByStatus(s);
        long projects = workflowStatusRepository.findWorkflowsUsingStatus(s.getId()).stream()
                .mapToLong(projectCountService::projectsUsingWorkflow).sum();
        return new UsageInfo(workflows, 0, projects, issueRepository.countByStatus(s));
    }

    private UsageInfo priorityUsage(Priority p) {
        long sets = prioritySetItemRepository.countByPriority(p);
        long projects = prioritySetItemRepository.findSetsUsingPriority(p.getId()).stream()
                .mapToLong(projectCountService::projectsUsingPrioritySet).sum();
        return new UsageInfo(0, sets, projects, issueRepository.countByPriority(p));
    }

    private UsageInfo issueTypeUsage(IssueType t) {
        long sets = issueTypeSetItemRepository.countByType(t);
        long projects = issueTypeSetItemRepository.findSetsUsingType(t.getId()).stream()
                .mapToLong(projectCountService::projectsUsingIssueTypeSet).sum();
        return new UsageInfo(0, sets, projects, issueRepository.countByType(t));
    }

    private short nextPosition(int currentMax) {
        return (short) (currentMax + 1);
    }

    private Status requireStatus(UUID id) {
        return statusRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Status not found"));
    }

    private Priority requirePriority(UUID id) {
        return priorityRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Priority not found"));
    }

    private IssueType requireIssueType(UUID id) {
        return issueTypeRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue type not found"));
    }
}
