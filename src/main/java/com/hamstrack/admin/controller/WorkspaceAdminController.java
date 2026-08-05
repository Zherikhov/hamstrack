package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.scope.ScopeResolver;
import com.hamstrack.admin.service.AdminCatalogService;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.admin.service.AdminIssueTypeSetService;
import com.hamstrack.admin.service.AdminPrioritySetService;
import com.hamstrack.admin.service.AdminWorkflowService;
import com.hamstrack.admin.service.ScopedProjectAdminService;
import com.hamstrack.auth.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workspace-admin console: workspace-scoped catalog (statuses, priorities, issue
 * types) plus the binding matrix for every project in a workspace the actor
 * owns/administers. Unlike {@code /api/admin/**} (system admin, guarded in
 * SecurityConfig), authorization is membership-based — every handler resolves
 * the workspace-OWNER/ADMIN scope via {@link ScopeResolver} before delegating to
 * the shared services with {@code ScopeContext.workspace(..)}.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/admin")
@RequiredArgsConstructor
public class WorkspaceAdminController {

    private final ScopedProjectAdminService bindingService;
    private final AdminCatalogService catalogService;
    private final AdminWorkflowService workflowService;
    private final AdminPrioritySetService prioritySetService;
    private final AdminIssueTypeSetService issueTypeSetService;
    private final AdminFieldService fieldService;
    private final ScopeResolver scopeResolver;

    /** Authorize the actor as a workspace admin and return the workspace scope. */
    private ScopeContext scope(User actor, UUID workspaceId) {
        scopeResolver.requireWorkspaceAdmin(actor, workspaceId);
        return ScopeContext.workspace(workspaceId);
    }

    // ---------- binding matrix ----------

    @GetMapping("/projects")
    public List<ProjectBindingResponse> matrix(@AuthenticationPrincipal User actor,
                                               @PathVariable UUID workspaceId) {
        return bindingService.workspaceMatrix(actor, workspaceId);
    }

    @GetMapping("/binding-options")
    public BindingOptionsResponse bindingOptions(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId) {
        return bindingService.workspaceBindingOptions(actor, workspaceId);
    }

    @PatchMapping("/projects/{projectId}/bindings")
    public ProjectBindingResponse updateBindings(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId,
                                                 @PathVariable UUID projectId,
                                                 @Valid @RequestBody UpdateProjectBindingsRequest req) {
        return bindingService.updateWorkspaceProjectBindings(actor, workspaceId, projectId, req);
    }

    // ---------- statuses ----------

    @GetMapping("/statuses")
    public List<AdminStatusResponse> listStatuses(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId) {
        return catalogService.listStatuses(scope(actor, workspaceId));
    }

    @PostMapping("/statuses")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStatusResponse createStatus(@AuthenticationPrincipal User actor,
                                            @PathVariable UUID workspaceId,
                                            @Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.createStatus(scope(actor, workspaceId), req);
    }

    @PatchMapping("/statuses/{id}")
    public AdminStatusResponse updateStatus(@AuthenticationPrincipal User actor,
                                            @PathVariable UUID workspaceId, @PathVariable UUID id,
                                            @Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.updateStatus(scope(actor, workspaceId), id, req);
    }

    @PostMapping("/statuses/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveStatus(@AuthenticationPrincipal User actor,
                              @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setStatusArchived(scope(actor, workspaceId), id, true);
    }

    @PostMapping("/statuses/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveStatus(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setStatusArchived(scope(actor, workspaceId), id, false);
    }

    @DeleteMapping("/statuses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatus(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId, @PathVariable UUID id,
                             @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteStatus(scope(actor, workspaceId), id, replaceWithId);
    }

    // ---------- priorities ----------

    @GetMapping("/priorities")
    public List<AdminPriorityResponse> listPriorities(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId) {
        return catalogService.listPriorities(scope(actor, workspaceId));
    }

    @PostMapping("/priorities")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPriorityResponse createPriority(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId,
                                                @Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.createPriority(scope(actor, workspaceId), req);
    }

    @PatchMapping("/priorities/{id}")
    public AdminPriorityResponse updatePriority(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                @Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.updatePriority(scope(actor, workspaceId), id, req);
    }

    @PostMapping("/priorities/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archivePriority(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setPriorityArchived(scope(actor, workspaceId), id, true);
    }

    @PostMapping("/priorities/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchivePriority(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setPriorityArchived(scope(actor, workspaceId), id, false);
    }

    @DeleteMapping("/priorities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriority(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID id,
                               @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deletePriority(scope(actor, workspaceId), id, replaceWithId);
    }

    // ---------- issue types ----------

    @GetMapping("/issue-types")
    public List<AdminIssueTypeResponse> listIssueTypes(@AuthenticationPrincipal User actor,
                                                       @PathVariable UUID workspaceId) {
        return catalogService.listIssueTypes(scope(actor, workspaceId));
    }

    @PostMapping("/issue-types")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeResponse createIssueType(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId,
                                                  @Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.createIssueType(scope(actor, workspaceId), req);
    }

    @PatchMapping("/issue-types/{id}")
    public AdminIssueTypeResponse updateIssueType(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                  @Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.updateIssueType(scope(actor, workspaceId), id, req);
    }

    @PostMapping("/issue-types/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveIssueType(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setIssueTypeArchived(scope(actor, workspaceId), id, true);
    }

    @PostMapping("/issue-types/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveIssueType(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId, @PathVariable UUID id) {
        catalogService.setIssueTypeArchived(scope(actor, workspaceId), id, false);
    }

    @DeleteMapping("/issue-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueType(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID id,
                                @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteIssueType(scope(actor, workspaceId), id, replaceWithId);
    }

    // ---------- catalog usage popovers ----------

    @GetMapping("/statuses/{id}/usage")
    public UsageDetailResponse statusUsage(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId, @PathVariable UUID id) {
        return catalogService.statusUsageDetail(scope(actor, workspaceId), id);
    }

    @GetMapping("/priorities/{id}/usage")
    public UsageDetailResponse priorityUsage(@AuthenticationPrincipal User actor,
                                             @PathVariable UUID workspaceId, @PathVariable UUID id) {
        return catalogService.priorityUsageDetail(scope(actor, workspaceId), id);
    }

    @GetMapping("/issue-types/{id}/usage")
    public UsageDetailResponse issueTypeUsage(@AuthenticationPrincipal User actor,
                                              @PathVariable UUID workspaceId, @PathVariable UUID id) {
        return catalogService.issueTypeUsageDetail(scope(actor, workspaceId), id);
    }

    // ---------- workflows ----------

    @GetMapping("/workflows")
    public List<AdminWorkflowResponse> listWorkflows(@AuthenticationPrincipal User actor,
                                                     @PathVariable UUID workspaceId) {
        return workflowService.list(scope(actor, workspaceId));
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminWorkflowResponse createWorkflow(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId,
                                                @Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.create(scope(actor, workspaceId), req);
    }

    @PatchMapping("/workflows/{id}")
    public AdminWorkflowResponse updateWorkflow(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                @Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.update(scope(actor, workspaceId), id, req);
    }

    @DeleteMapping("/workflows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkflow(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID id) {
        workflowService.delete(scope(actor, workspaceId), id);
    }

    // ---------- priority sets ----------

    @GetMapping("/priority-sets")
    public List<AdminPrioritySetResponse> listPrioritySets(@AuthenticationPrincipal User actor,
                                                           @PathVariable UUID workspaceId) {
        return prioritySetService.list(scope(actor, workspaceId));
    }

    @PostMapping("/priority-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPrioritySetResponse createPrioritySet(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId,
                                                      @Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.create(scope(actor, workspaceId), req);
    }

    @PatchMapping("/priority-sets/{id}")
    public AdminPrioritySetResponse updatePrioritySet(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                      @Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.update(scope(actor, workspaceId), id, req);
    }

    @DeleteMapping("/priority-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrioritySet(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId, @PathVariable UUID id) {
        prioritySetService.delete(scope(actor, workspaceId), id);
    }

    // ---------- issue type sets ----------

    @GetMapping("/issue-type-sets")
    public List<AdminIssueTypeSetResponse> listIssueTypeSets(@AuthenticationPrincipal User actor,
                                                             @PathVariable UUID workspaceId) {
        return issueTypeSetService.list(scope(actor, workspaceId));
    }

    @PostMapping("/issue-type-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeSetResponse createIssueTypeSet(@AuthenticationPrincipal User actor,
                                                        @PathVariable UUID workspaceId,
                                                        @Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.create(scope(actor, workspaceId), req);
    }

    @PatchMapping("/issue-type-sets/{id}")
    public AdminIssueTypeSetResponse updateIssueTypeSet(@AuthenticationPrincipal User actor,
                                                        @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                        @Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.update(scope(actor, workspaceId), id, req);
    }

    @DeleteMapping("/issue-type-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueTypeSet(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId, @PathVariable UUID id) {
        issueTypeSetService.delete(scope(actor, workspaceId), id);
    }

    // ---------- custom fields ----------

    @GetMapping("/fields")
    public List<AdminFieldResponse> listFields(@AuthenticationPrincipal User actor,
                                               @PathVariable UUID workspaceId) {
        return fieldService.listFields(scope(actor, workspaceId));
    }

    @PostMapping("/fields")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldResponse createField(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId,
                                          @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.createField(scope(actor, workspaceId), req);
    }

    @PatchMapping("/fields/{id}")
    public AdminFieldResponse updateField(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId, @PathVariable UUID id,
                                          @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.updateField(scope(actor, workspaceId), id, req);
    }

    @PostMapping("/fields/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveField(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId, @PathVariable UUID id) {
        fieldService.setFieldArchived(scope(actor, workspaceId), id, true);
    }

    @PostMapping("/fields/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveField(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID id) {
        fieldService.setFieldArchived(scope(actor, workspaceId), id, false);
    }

    @DeleteMapping("/fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteField(@AuthenticationPrincipal User actor,
                            @PathVariable UUID workspaceId, @PathVariable UUID id,
                            @RequestParam(defaultValue = "false") boolean dropValues) {
        fieldService.deleteField(scope(actor, workspaceId), id, dropValues);
    }

    @GetMapping("/fields/{id}/usage")
    public UsageDetailResponse fieldUsage(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId, @PathVariable UUID id) {
        return fieldService.fieldUsageDetail(scope(actor, workspaceId), id);
    }

    // ---------- field sets ----------

    @GetMapping("/field-sets")
    public List<AdminFieldSetResponse> listFieldSets(@AuthenticationPrincipal User actor,
                                                     @PathVariable UUID workspaceId) {
        return fieldService.listSets(scope(actor, workspaceId));
    }

    @PostMapping("/field-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldSetResponse createFieldSet(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId,
                                                @Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.createSet(scope(actor, workspaceId), req);
    }

    @PatchMapping("/field-sets/{id}")
    public AdminFieldSetResponse updateFieldSet(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID id,
                                                @Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.updateSet(scope(actor, workspaceId), id, req);
    }

    @DeleteMapping("/field-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFieldSet(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID id) {
        fieldService.deleteSet(scope(actor, workspaceId), id);
    }
}
