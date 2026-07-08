package com.hamstrack.project.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @Valid @RequestBody CreateProjectRequest req) {
        return projectService.create(actor, workspaceId, req);
    }

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal User actor,
                                      @PathVariable UUID workspaceId) {
        return projectService.list(actor, workspaceId);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@AuthenticationPrincipal User actor,
                               @PathVariable UUID workspaceId,
                               @PathVariable UUID projectId) {
        return projectService.get(actor, workspaceId, projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID projectId,
                                  @Valid @RequestBody UpdateProjectRequest req) {
        return projectService.update(actor, workspaceId, projectId, req);
    }

    @PostMapping("/{projectId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal User actor,
                        @PathVariable UUID workspaceId,
                        @PathVariable UUID projectId) {
        projectService.archive(actor, workspaceId, projectId);
    }

    @GetMapping("/{projectId}/members")
    public List<ProjectMemberResponse> listMembers(@AuthenticationPrincipal User actor,
                                                   @PathVariable UUID workspaceId,
                                                   @PathVariable UUID projectId) {
        return projectService.listMembers(actor, workspaceId, projectId);
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse addMember(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId,
                                           @PathVariable UUID projectId,
                                           @Valid @RequestBody AddProjectMemberRequest req) {
        return projectService.addMember(actor, workspaceId, projectId, req);
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId,
                             @PathVariable UUID projectId,
                             @PathVariable UUID userId) {
        projectService.removeMember(actor, workspaceId, projectId, userId);
    }
}
