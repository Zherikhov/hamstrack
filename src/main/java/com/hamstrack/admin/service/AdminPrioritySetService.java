package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.AdminPrioritySetResponse;
import com.hamstrack.admin.dto.UpsertPrioritySetRequest;
import com.hamstrack.issue.dto.PriorityResponse;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.PrioritySetItem;
import com.hamstrack.issue.repository.PriorityRepository;
import com.hamstrack.issue.repository.PrioritySetItemRepository;
import com.hamstrack.issue.repository.PrioritySetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Priority set CRUD. Updates replace items wholesale; every set always keeps
 * at least one item and exactly one default (the first item is promoted when
 * none is flagged). The system default set can be edited but never deleted.
 */
@Service
@RequiredArgsConstructor
public class AdminPrioritySetService {

    private final PrioritySetRepository prioritySetRepository;
    private final PrioritySetItemRepository prioritySetItemRepository;
    private final PriorityRepository priorityRepository;
    private final ProjectCountService projectCountService;

    @Transactional(readOnly = true)
    public List<AdminPrioritySetResponse> list() {
        return prioritySetRepository.findAllByScopeWorkspaceIdIsNullOrderByName().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AdminPrioritySetResponse create(UpsertPrioritySetRequest req) {
        if (prioritySetRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Priority set name already exists");
        }
        var set = new PrioritySet();
        set.setName(req.name());
        prioritySetRepository.save(set);
        applyItems(set, req);
        return toResponse(set);
    }

    @Transactional
    public AdminPrioritySetResponse update(UUID id, UpsertPrioritySetRequest req) {
        var set = require(id);
        if (!set.getName().equals(req.name())
                && prioritySetRepository.existsByScopeWorkspaceIdIsNullAndName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Priority set name already exists");
        }
        set.setName(req.name());
        prioritySetRepository.save(set);
        prioritySetItemRepository.deleteAllBySet(set);
        applyItems(set, req);
        return toResponse(set);
    }

    @Transactional
    public void delete(UUID id) {
        var set = require(id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default priority set cannot be deleted");
        }
        long projects = projectCountService.projectsUsingPrioritySet(set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this priority set — reassign them first");
        }
        prioritySetRepository.delete(set);
    }

    private void applyItems(PrioritySet set, UpsertPrioritySetRequest req) {
        var seen = new HashSet<UUID>();
        boolean hasDefault = req.items().stream().anyMatch(UpsertPrioritySetRequest.Item::isDefault);
        short pos = 0;
        boolean first = true;
        for (var itemReq : req.items()) {
            if (!seen.add(itemReq.priorityId())) continue;
            var priority = requirePriority(itemReq.priorityId());
            var item = new PrioritySetItem();
            item.setSet(set);
            item.setPriority(priority);
            item.setPosition(pos++);
            item.setDefaultForNewIssues(hasDefault ? itemReq.isDefault() : first);
            prioritySetItemRepository.save(item);
            first = false;
        }
    }

    private AdminPrioritySetResponse toResponse(PrioritySet set) {
        var items = prioritySetItemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> new AdminPrioritySetResponse.Item(
                        PriorityResponse.of(i.getPriority()), i.isDefaultForNewIssues()))
                .toList();
        return new AdminPrioritySetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                items, projectCountService.projectsUsingPrioritySet(set));
    }

    private PrioritySet require(UUID id) {
        return prioritySetRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Priority set not found"));
    }

    private Priority requirePriority(UUID id) {
        return priorityRepository.findByIdAndScopeWorkspaceIdIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown priority"));
    }
}
