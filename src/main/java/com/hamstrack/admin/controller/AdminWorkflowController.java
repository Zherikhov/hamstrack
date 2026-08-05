package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.AdminIssueTypeSetResponse;
import com.hamstrack.admin.dto.AdminPrioritySetResponse;
import com.hamstrack.admin.dto.AdminWorkflowResponse;
import com.hamstrack.admin.dto.UpsertIssueTypeSetRequest;
import com.hamstrack.admin.dto.UpsertPrioritySetRequest;
import com.hamstrack.admin.dto.UpsertWorkflowRequest;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminIssueTypeSetService;
import com.hamstrack.admin.service.AdminPrioritySetService;
import com.hamstrack.admin.service.AdminWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Reusable binding sets (workflows, priority sets, issue type sets) for the
 * system administrator. Upserts replace nested statuses/transitions/items
 * wholesale; the system defaults are editable but not deletable, and sets in
 * use by projects refuse deletion (409). Guarded by hasRole(ADMIN) in
 * SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminWorkflowController {

    private final AdminWorkflowService workflowService;
    private final AdminPrioritySetService prioritySetService;
    private final AdminIssueTypeSetService issueTypeSetService;

    private static final ScopeContext SCOPE = ScopeContext.global();

    // ---------- workflows ----------

    @GetMapping("/workflows")
    public List<AdminWorkflowResponse> listWorkflows() {
        return workflowService.list(SCOPE);
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminWorkflowResponse createWorkflow(@Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.create(SCOPE, req);
    }

    @PatchMapping("/workflows/{id}")
    public AdminWorkflowResponse updateWorkflow(@PathVariable UUID id, @Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.update(SCOPE, id, req);
    }

    @DeleteMapping("/workflows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkflow(@PathVariable UUID id) {
        workflowService.delete(SCOPE, id);
    }

    // ---------- priority sets ----------

    @GetMapping("/priority-sets")
    public List<AdminPrioritySetResponse> listPrioritySets() {
        return prioritySetService.list(SCOPE);
    }

    @PostMapping("/priority-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPrioritySetResponse createPrioritySet(@Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.create(SCOPE, req);
    }

    @PatchMapping("/priority-sets/{id}")
    public AdminPrioritySetResponse updatePrioritySet(@PathVariable UUID id,
                                                      @Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.update(SCOPE, id, req);
    }

    @DeleteMapping("/priority-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrioritySet(@PathVariable UUID id) {
        prioritySetService.delete(SCOPE, id);
    }

    // ---------- issue type sets ----------

    @GetMapping("/issue-type-sets")
    public List<AdminIssueTypeSetResponse> listIssueTypeSets() {
        return issueTypeSetService.list(SCOPE);
    }

    @PostMapping("/issue-type-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeSetResponse createIssueTypeSet(@Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.create(SCOPE, req);
    }

    @PatchMapping("/issue-type-sets/{id}")
    public AdminIssueTypeSetResponse updateIssueTypeSet(@PathVariable UUID id,
                                                        @Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.update(SCOPE, id, req);
    }

    @DeleteMapping("/issue-type-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueTypeSet(@PathVariable UUID id) {
        issueTypeSetService.delete(SCOPE, id);
    }
}
