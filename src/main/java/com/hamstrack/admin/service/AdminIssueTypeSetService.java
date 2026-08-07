package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.AdminIssueTypeSetResponse;
import com.hamstrack.admin.dto.UpsertIssueTypeSetRequest;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.issue.dto.IssueTypeResponse;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.IssueTypeSetItem;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.issue.repository.IssueTypeSetItemRepository;
import com.hamstrack.issue.repository.IssueTypeSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Issue type set CRUD. Updates replace items wholesale; a set can never be
 * empty. Removing a type from a set never touches existing issues — only
 * creation and type changes are restricted to the set — so unlike workflows
 * there is no "stranded issues" guard here. The system default set can be
 * edited but never deleted.
 */
@Service
@RequiredArgsConstructor
public class AdminIssueTypeSetService {

    private final IssueTypeSetRepository setRepository;
    private final IssueTypeSetItemRepository itemRepository;
    private final IssueTypeRepository issueTypeRepository;
    private final ProjectCountService projectCountService;

    @Transactional(readOnly = true)
    public List<AdminIssueTypeSetResponse> list(ScopeContext scope) {
        // Inherited sets are shown read-only in delegated consoles (see AdminWorkflowService.list)
        var sets = scope.isGlobal()
                ? setRepository.findAllAtScope(null, null)
                : setRepository.findAllBindableForProject(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return sets.stream().map(set -> toResponse(scope, set)).toList();
    }

    @Transactional
    public AdminIssueTypeSetResponse create(ScopeContext scope, UpsertIssueTypeSetRequest req) {
        if (setRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type set name already exists");
        }
        var set = new IssueTypeSet();
        scope.stamp(set);
        set.setName(req.name());
        setRepository.save(set);
        applyItems(scope, set, req);
        return toResponse(scope, set);
    }

    @Transactional
    public AdminIssueTypeSetResponse update(ScopeContext scope, UUID id, UpsertIssueTypeSetRequest req) {
        var set = require(scope, id);
        if (!set.getName().equals(req.name())
                && setRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type set name already exists");
        }
        set.setName(req.name());
        setRepository.save(set);
        itemRepository.deleteAllBySet(set);
        // Flush DELETEs before re-inserting — Hibernate orders INSERTs ahead of
        // DELETEs in one flush, colliding with UNIQUE(set_id, type_id).
        itemRepository.flush();
        applyItems(scope, set, req);
        return toResponse(scope, set);
    }

    @Transactional
    public void delete(ScopeContext scope, UUID id) {
        var set = require(scope, id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default issue type set cannot be deleted");
        }
        long projects = projectCountService.projectsUsingIssueTypeSet(scope, set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this issue type set — reassign them first");
        }
        setRepository.delete(set);
    }

    private void applyItems(ScopeContext scope, IssueTypeSet set, UpsertIssueTypeSetRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var typeId : req.typeIds()) {
            if (!seen.add(typeId)) continue;
            var item = new IssueTypeSetItem();
            item.setSet(set);
            item.setType(requireType(scope, typeId));
            item.setPosition(pos++);
            itemRepository.save(item);
        }
    }

    private AdminIssueTypeSetResponse toResponse(ScopeContext scope, IssueTypeSet set) {
        var types = itemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> IssueTypeResponse.of(i.getType()))
                .toList();
        return new AdminIssueTypeSetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                types, projectCountService.projectsUsingIssueTypeSet(scope, set), set.scopeLabel());
    }

    private IssueTypeSet require(ScopeContext scope, UUID id) {
        return setRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue type set not found"));
    }

    /** A type the set may include: visible to this scope (global ∪ ancestor-ws ∪ own project). */
    private IssueType requireType(ScopeContext scope, UUID id) {
        return issueTypeRepository.findByIdVisibleTo(id, scope.visibleWorkspaceId(), scope.visibleProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown issue type"));
    }
}
