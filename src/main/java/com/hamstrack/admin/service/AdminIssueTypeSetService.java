package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.AdminIssueTypeSetResponse;
import com.hamstrack.admin.dto.UpsertIssueTypeSetRequest;
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
    public List<AdminIssueTypeSetResponse> list() {
        return setRepository.findAllByScopeWorkspaceIdIsNullOrderByName().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdminIssueTypeSetResponse create(UpsertIssueTypeSetRequest req) {
        if (setRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type set name already exists");
        }
        var set = new IssueTypeSet();
        set.setName(req.name());
        setRepository.save(set);
        applyItems(set, req);
        return toResponse(set);
    }

    @Transactional
    public AdminIssueTypeSetResponse update(UUID id, UpsertIssueTypeSetRequest req) {
        var set = require(id);
        if (!set.getName().equals(req.name())
                && setRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue type set name already exists");
        }
        set.setName(req.name());
        setRepository.save(set);
        itemRepository.deleteAllBySet(set);
        applyItems(set, req);
        return toResponse(set);
    }

    @Transactional
    public void delete(UUID id) {
        var set = require(id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default issue type set cannot be deleted");
        }
        long projects = projectCountService.projectsUsingIssueTypeSet(set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this issue type set — reassign them first");
        }
        setRepository.delete(set);
    }

    private void applyItems(IssueTypeSet set, UpsertIssueTypeSetRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var typeId : req.typeIds()) {
            if (!seen.add(typeId)) continue;
            var item = new IssueTypeSetItem();
            item.setSet(set);
            item.setType(requireType(typeId));
            item.setPosition(pos++);
            itemRepository.save(item);
        }
    }

    private AdminIssueTypeSetResponse toResponse(IssueTypeSet set) {
        var types = itemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> IssueTypeResponse.of(i.getType()))
                .toList();
        return new AdminIssueTypeSetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                types, projectCountService.projectsUsingIssueTypeSet(set));
    }

    private IssueTypeSet require(UUID id) {
        return setRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue type set not found"));
    }

    private IssueType requireType(UUID id) {
        return issueTypeRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown issue type"));
    }
}
