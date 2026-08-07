package com.hamstrack.issue.service;

import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.PrioritySetItem;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.issue.entity.WorkflowTransition;
import com.hamstrack.issue.repository.IssueTypeSetItemRepository;
import com.hamstrack.issue.repository.IssueTypeSetRepository;
import com.hamstrack.issue.repository.PrioritySetItemRepository;
import com.hamstrack.issue.repository.PrioritySetRepository;
import com.hamstrack.issue.repository.WorkflowRepository;
import com.hamstrack.issue.repository.WorkflowStatusRepository;
import com.hamstrack.issue.repository.WorkflowTransitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Caching layer behind {@link ProjectConfigService}, in a SEPARATE bean on
 * purpose: {@code @Cacheable} is applied by a Spring proxy, so a self-invocation
 * from within {@code ProjectConfigService} would bypass the cache. All lookups
 * are keyed by the effective workflow/set id and return fully-initialized
 * (fetch-joined) entities — read-only, safe to hand back detached across the
 * cache TTL (see {@code CacheConfig}). Never mutate or persist these.
 */
@Service
@RequiredArgsConstructor
public class ProjectConfigCache {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStatusRepository workflowStatusRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final PrioritySetRepository prioritySetRepository;
    private final PrioritySetItemRepository prioritySetItemRepository;
    private final IssueTypeSetRepository issueTypeSetRepository;
    private final IssueTypeSetItemRepository issueTypeSetItemRepository;

    @Cacheable(cacheNames = "cfgSystemDefaultWorkflow", key = "'default'")
    @Transactional(readOnly = true)
    public Workflow systemDefaultWorkflow() {
        return workflowRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default workflow is missing"));
    }

    @Cacheable(cacheNames = "cfgSystemDefaultPrioritySet", key = "'default'")
    @Transactional(readOnly = true)
    public PrioritySet systemDefaultPrioritySet() {
        return prioritySetRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default priority set is missing"));
    }

    @Cacheable(cacheNames = "cfgSystemDefaultTypeSet", key = "'default'")
    @Transactional(readOnly = true)
    public IssueTypeSet systemDefaultTypeSet() {
        return issueTypeSetRepository.findBySystemDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("System default issue type set is missing"));
    }

    @Cacheable(cacheNames = "cfgStatuses", key = "#workflowId")
    @Transactional(readOnly = true)
    public List<Status> statusesForWorkflow(UUID workflowId) {
        return workflowStatusRepository.findStatusesByWorkflowId(workflowId);
    }

    @Cacheable(cacheNames = "cfgTransitions", key = "#workflowId")
    @Transactional(readOnly = true)
    public List<WorkflowTransition> transitionsForWorkflow(UUID workflowId) {
        return workflowTransitionRepository.findByWorkflowIdWithStatuses(workflowId);
    }

    @Cacheable(cacheNames = "cfgPriorityItems", key = "#setId")
    @Transactional(readOnly = true)
    public List<PrioritySetItem> priorityItemsForSet(UUID setId) {
        return prioritySetItemRepository.findBySetIdWithPriority(setId);
    }

    @Cacheable(cacheNames = "cfgTypes", key = "#setId")
    @Transactional(readOnly = true)
    public List<IssueType> typesForSet(UUID setId) {
        return issueTypeSetItemRepository.findTypesBySetId(setId);
    }
}
