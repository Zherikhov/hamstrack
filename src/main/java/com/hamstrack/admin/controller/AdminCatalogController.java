package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.service.AdminCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Global catalog management (statuses, priorities, issue types) for the
 * system administrator. The whole /api/admin/** area is guarded by
 * {@code hasRole("ADMIN")} in SecurityConfig — no per-method checks here.
 * DELETE takes an optional {@code replaceWithId} to remap referencing issues;
 * archive hides an entry from new use without touching history.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCatalogController {

    private final AdminCatalogService catalogService;

    // ---------- statuses ----------

    @GetMapping("/statuses")
    public List<AdminStatusResponse> listStatuses() {
        return catalogService.listStatuses();
    }

    @PostMapping("/statuses")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStatusResponse createStatus(@Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.createStatus(req);
    }

    @PatchMapping("/statuses/{id}")
    public AdminStatusResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UpsertStatusRequest req) {
        return catalogService.updateStatus(id, req);
    }

    @PostMapping("/statuses/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveStatus(@PathVariable UUID id) {
        catalogService.setStatusArchived(id, true);
    }

    @PostMapping("/statuses/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveStatus(@PathVariable UUID id) {
        catalogService.setStatusArchived(id, false);
    }

    @DeleteMapping("/statuses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatus(@PathVariable UUID id, @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteStatus(id, replaceWithId);
    }

    @GetMapping("/statuses/{id}/usage")
    public UsageDetailResponse statusUsage(@PathVariable UUID id) {
        return catalogService.statusUsageDetail(id);
    }

    // ---------- priorities ----------

    @GetMapping("/priorities")
    public List<AdminPriorityResponse> listPriorities() {
        return catalogService.listPriorities();
    }

    @PostMapping("/priorities")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPriorityResponse createPriority(@Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.createPriority(req);
    }

    @PatchMapping("/priorities/{id}")
    public AdminPriorityResponse updatePriority(@PathVariable UUID id, @Valid @RequestBody UpsertPriorityRequest req) {
        return catalogService.updatePriority(id, req);
    }

    @PostMapping("/priorities/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archivePriority(@PathVariable UUID id) {
        catalogService.setPriorityArchived(id, true);
    }

    @PostMapping("/priorities/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchivePriority(@PathVariable UUID id) {
        catalogService.setPriorityArchived(id, false);
    }

    @DeleteMapping("/priorities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriority(@PathVariable UUID id, @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deletePriority(id, replaceWithId);
    }

    @GetMapping("/priorities/{id}/usage")
    public UsageDetailResponse priorityUsage(@PathVariable UUID id) {
        return catalogService.priorityUsageDetail(id);
    }

    // ---------- issue types ----------

    @GetMapping("/issue-types")
    public List<AdminIssueTypeResponse> listIssueTypes() {
        return catalogService.listIssueTypes();
    }

    @PostMapping("/issue-types")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminIssueTypeResponse createIssueType(@Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.createIssueType(req);
    }

    @PatchMapping("/issue-types/{id}")
    public AdminIssueTypeResponse updateIssueType(@PathVariable UUID id, @Valid @RequestBody UpsertIssueTypeRequest req) {
        return catalogService.updateIssueType(id, req);
    }

    @PostMapping("/issue-types/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveIssueType(@PathVariable UUID id) {
        catalogService.setIssueTypeArchived(id, true);
    }

    @PostMapping("/issue-types/{id}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unarchiveIssueType(@PathVariable UUID id) {
        catalogService.setIssueTypeArchived(id, false);
    }

    @DeleteMapping("/issue-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueType(@PathVariable UUID id, @RequestParam(required = false) UUID replaceWithId) {
        catalogService.deleteIssueType(id, replaceWithId);
    }

    @GetMapping("/issue-types/{id}/usage")
    public UsageDetailResponse issueTypeUsage(@PathVariable UUID id) {
        return catalogService.issueTypeUsageDetail(id);
    }
}
