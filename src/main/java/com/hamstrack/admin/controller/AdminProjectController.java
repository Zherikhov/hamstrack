package com.hamstrack.admin.controller;

import com.hamstrack.admin.dto.ProjectBindingResponse;
import com.hamstrack.admin.dto.UpdateProjectBindingsRequest;
import com.hamstrack.admin.service.AdminProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The assignment matrix: every project across all workspaces × its workflow /
 * priority-set bindings, editable in place. System-admin surface (guarded by
 * hasRole(ADMIN)); deliberately not workspace-scoped.
 */
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
public class AdminProjectController {

    private final AdminProjectService projectService;

    @GetMapping
    public List<ProjectBindingResponse> list() {
        return projectService.list();
    }

    @PatchMapping("/{projectId}/bindings")
    public ProjectBindingResponse updateBindings(@PathVariable UUID projectId,
                                                 @RequestBody UpdateProjectBindingsRequest req) {
        return projectService.updateBindings(projectId, req);
    }
}
