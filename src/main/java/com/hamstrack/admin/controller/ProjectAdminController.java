package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminCatalogService;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.admin.service.AdminIssueTypeSetService;
import com.hamstrack.admin.service.AdminPrioritySetService;
import com.hamstrack.admin.service.AdminWorkflowService;
import com.hamstrack.admin.service.ScopedProjectAdminService;
import com.hamstrack.auth.entity.User;
import com.hamstrack.common.security.Permission;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project-admin console: a project administrator manages their own project's
 * project-private catalog (statuses, priorities, issue types) and taxonomy bindings.
 * Authorization resolves the project through
 * {@link WorkspaceAccessService#resolveProject} (404 for a missing workspace, a missing
 * project, or a non-member of the <em>workspace</em>) and then requires
 * {@link Permission#PROJECT_TAXONOMY_MANAGE} (403), before delegating with
 * {@code ScopeContext.project(..)}. Project-private rows are visible only in this
 * project; inherited (workspace/global) rows are selected, not edited here.
 *
 * <p><strong>HD-126 (S3) changed one status code here on purpose</strong> (§10.3.2,
 * §12.2): {@code ScopeResolver.requireProjectAdmin} answered <strong>404</strong> to a
 * workspace member who was not a <em>project</em> member, while every neighbouring
 * predicate answered 403 for the same shape of failure. It is now 403 everywhere. That is
 * safe precisely because the project is already listed to the caller — they can see it in
 * {@code GET /projects} — so nothing is disclosed; it must not be copied to any surface
 * where the resource is not already listed. A non-member of the <em>workspace</em> still
 * gets 404, from {@code resolveProject}, and always must.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/admin")
@RequiredArgsConstructor
public class ProjectAdminController {

    private final ScopedProjectAdminService bindingService;
    private final AdminCatalogService catalogService;
    private final AdminWorkflowService workflowService;
    private final AdminPrioritySetService prioritySetService;
    private final AdminIssueTypeSetService issueTypeSetService;
    private final AdminFieldService fieldService;
    private final WorkspaceAccessService workspaceAccess;

    /** Authorize the actor for project taxonomy administration and return the scope. */
    private ScopeContext scope(User actor, UUID workspaceId, UUID projectId) {
        workspaceAccess.resolveProject(actor, workspaceId, projectId)
                .permissions().require(Permission.PROJECT_TAXONOMY_MANAGE);
        return ScopeContext.project(workspaceId, projectId);
    }

    // ---------- bindings ----------

    @GetMapping("/bindings")
    public ProjectBindingResponse bindings(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId,
                                           @PathVariable UUID projectId) {
        return bindingService.projectBindings(actor, workspaceId, projectId);
    }

    @GetMapping("/binding-options")
    public BindingOptionsResponse bindingOptions(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId,
                                                 @PathVariable UUID projectId) {
        return bindingService.projectBindingOptions(actor, workspaceId, projectId);
    }

    @PatchMapping("/bindings")
    public ProjectBindingResponse updateBindings(@AuthenticationPrincipal User actor,
                                                 @PathVariable UUID workspaceId,
                                                 @PathVariable UUID projectId,
                                                 @Valid @RequestBody UpdateProjectBindingsRequest req) {
        return bindingService.updateProjectBindings(actor, workspaceId, projectId, req);
    }

    // ---------- statuses ----------

    @GetMapping("/statuses")
    public List<AdminStatusResponse> listStatuses(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return catalogService.listStatuses(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/statuses")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStatusResponse createStatus(@AuthenticationPrincipal User actor,
                                            @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                            @Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.createStatus(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/statuses/{id}")
    public AdminStatusResponse updateStatus(@AuthenticationPrincipal User actor,
                                            @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                            @PathVariable UUID id, @Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.updateStatus(scope(actor, workspaceId, projectId), id, req);
    }

    @PostMapping("/statuses/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveStatus(@AuthenticationPrincipal User actor,
                              @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setStatusArchived(scope(actor, workspaceId, projectId), id, true);
    }

    @PostMapping("/statuses/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveStatus(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setStatusArchived(scope(actor, workspaceId, projectId), id, false);
    }

    @DeleteMapping("/statuses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatus(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
                             @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteStatus(scope(actor, workspaceId, projectId), id, replaceWithId);
    }

    // ---------- priorities ----------

    @GetMapping("/priorities")
    public List<AdminPriorityResponse> listPriorities(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return catalogService.listPriorities(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/priorities")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPriorityResponse createPriority(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.createPriority(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/priorities/{id}")
    public AdminPriorityResponse updatePriority(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @PathVariable UUID id, @Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.updatePriority(scope(actor, workspaceId, projectId), id, req);
    }

    @PostMapping("/priorities/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archivePriority(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setPriorityArchived(scope(actor, workspaceId, projectId), id, true);
    }

    @PostMapping("/priorities/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchivePriority(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setPriorityArchived(scope(actor, workspaceId, projectId), id, false);
    }

    @DeleteMapping("/priorities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriority(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
                               @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deletePriority(scope(actor, workspaceId, projectId), id, replaceWithId);
    }

    // ---------- issue types ----------

    @GetMapping("/issue-types")
    public List<AdminIssueTypeResponse> listIssueTypes(@AuthenticationPrincipal User actor,
                                                       @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return catalogService.listIssueTypes(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/issue-types")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeResponse createIssueType(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                  @Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.createIssueType(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/issue-types/{id}")
    public AdminIssueTypeResponse updateIssueType(@AuthenticationPrincipal User actor,
                                                  @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                  @PathVariable UUID id, @Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.updateIssueType(scope(actor, workspaceId, projectId), id, req);
    }

    @PostMapping("/issue-types/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveIssueType(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setIssueTypeArchived(scope(actor, workspaceId, projectId), id, true);
    }

    @PostMapping("/issue-types/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveIssueType(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        catalogService.setIssueTypeArchived(scope(actor, workspaceId, projectId), id, false);
    }

    @DeleteMapping("/issue-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueType(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
                                @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteIssueType(scope(actor, workspaceId, projectId), id, replaceWithId);
    }

    // ---------- catalog usage popovers ----------

    @GetMapping("/statuses/{id}/usage")
    public UsageDetailResponse statusUsage(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        return catalogService.statusUsageDetail(scope(actor, workspaceId, projectId), id);
    }

    @GetMapping("/priorities/{id}/usage")
    public UsageDetailResponse priorityUsage(@AuthenticationPrincipal User actor,
                                             @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        return catalogService.priorityUsageDetail(scope(actor, workspaceId, projectId), id);
    }

    @GetMapping("/issue-types/{id}/usage")
    public UsageDetailResponse issueTypeUsage(@AuthenticationPrincipal User actor,
                                              @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        return catalogService.issueTypeUsageDetail(scope(actor, workspaceId, projectId), id);
    }

    // ---------- workflows ----------

    @GetMapping("/workflows")
    public List<AdminWorkflowResponse> listWorkflows(@AuthenticationPrincipal User actor,
                                                     @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return workflowService.list(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminWorkflowResponse createWorkflow(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.create(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/workflows/{id}")
    public AdminWorkflowResponse updateWorkflow(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @PathVariable UUID id, @Valid @RequestBody UpsertWorkflowRequest req) {
        return workflowService.update(scope(actor, workspaceId, projectId), id, req);
    }

    @DeleteMapping("/workflows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkflow(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        workflowService.delete(scope(actor, workspaceId, projectId), id);
    }

    // ---------- priority sets ----------

    @GetMapping("/priority-sets")
    public List<AdminPrioritySetResponse> listPrioritySets(@AuthenticationPrincipal User actor,
                                                           @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return prioritySetService.list(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/priority-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPrioritySetResponse createPrioritySet(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                      @Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.create(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/priority-sets/{id}")
    public AdminPrioritySetResponse updatePrioritySet(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                      @PathVariable UUID id, @Valid @RequestBody UpsertPrioritySetRequest req) {
        return prioritySetService.update(scope(actor, workspaceId, projectId), id, req);
    }

    @DeleteMapping("/priority-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrioritySet(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        prioritySetService.delete(scope(actor, workspaceId, projectId), id);
    }

    // ---------- issue type sets ----------

    @GetMapping("/issue-type-sets")
    public List<AdminIssueTypeSetResponse> listIssueTypeSets(@AuthenticationPrincipal User actor,
                                                             @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return issueTypeSetService.list(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/issue-type-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeSetResponse createIssueTypeSet(@AuthenticationPrincipal User actor,
                                                        @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                        @Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.create(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/issue-type-sets/{id}")
    public AdminIssueTypeSetResponse updateIssueTypeSet(@AuthenticationPrincipal User actor,
                                                        @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                        @PathVariable UUID id, @Valid @RequestBody UpsertIssueTypeSetRequest req) {
        return issueTypeSetService.update(scope(actor, workspaceId, projectId), id, req);
    }

    @DeleteMapping("/issue-type-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueTypeSet(@AuthenticationPrincipal User actor,
                                   @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        issueTypeSetService.delete(scope(actor, workspaceId, projectId), id);
    }

    // ---------- custom fields ----------

    @GetMapping("/fields")
    public List<AdminFieldResponse> listFields(@AuthenticationPrincipal User actor,
                                               @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return fieldService.listFields(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/fields")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldResponse createField(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                          @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.createField(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/fields/{id}")
    public AdminFieldResponse updateField(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                          @PathVariable UUID id, @Valid @RequestBody UpsertFieldRequest req) {
        return fieldService.updateField(scope(actor, workspaceId, projectId), id, req);
    }

    @PostMapping("/fields/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveField(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        fieldService.setFieldArchived(scope(actor, workspaceId, projectId), id, true);
    }

    @PostMapping("/fields/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveField(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        fieldService.setFieldArchived(scope(actor, workspaceId, projectId), id, false);
    }

    @DeleteMapping("/fields/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteField(@AuthenticationPrincipal User actor,
                            @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id,
                            @RequestParam(defaultValue = "false") boolean dropValues) {
        fieldService.deleteField(scope(actor, workspaceId, projectId), id, dropValues);
    }

    @GetMapping("/fields/{id}/usage")
    public UsageDetailResponse fieldUsage(@AuthenticationPrincipal User actor,
                                          @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        return fieldService.fieldUsageDetail(scope(actor, workspaceId, projectId), id);
    }

    // ---------- field sets ----------

    @GetMapping("/field-sets")
    public List<AdminFieldSetResponse> listFieldSets(@AuthenticationPrincipal User actor,
                                                     @PathVariable UUID workspaceId, @PathVariable UUID projectId) {
        return fieldService.listSets(scope(actor, workspaceId, projectId));
    }

    @PostMapping("/field-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminFieldSetResponse createFieldSet(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.createSet(scope(actor, workspaceId, projectId), req);
    }

    @PatchMapping("/field-sets/{id}")
    public AdminFieldSetResponse updateFieldSet(@AuthenticationPrincipal User actor,
                                                @PathVariable UUID workspaceId, @PathVariable UUID projectId,
                                                @PathVariable UUID id, @Valid @RequestBody UpsertFieldSetRequest req) {
        return fieldService.updateSet(scope(actor, workspaceId, projectId), id, req);
    }

    @DeleteMapping("/field-sets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFieldSet(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId, @PathVariable UUID projectId, @PathVariable UUID id) {
        fieldService.deleteSet(scope(actor, workspaceId, projectId), id);
    }
}
