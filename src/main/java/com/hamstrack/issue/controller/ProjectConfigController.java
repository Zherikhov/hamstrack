package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.IssueTypeResponse;
import com.hamstrack.issue.dto.ProjectConfigResponse;
import com.hamstrack.issue.dto.StatusResponse;
import com.hamstrack.issue.service.FieldValueService;
import com.hamstrack.issue.service.ProjectConfigService;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-side of the taxonomy for regular users: the <em>effective</em>
 * configuration of a project (statuses in board order, transition rules,
 * offered priorities with the default, issue types). The SPA renders the
 * board and issue forms exclusively from this — it never talks to the admin
 * catalog. Workspace members only.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/config")
@RequiredArgsConstructor
public class ProjectConfigController {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectConfigService projectConfigService;
    private final FieldValueService fieldValueService;

    @GetMapping
    @Transactional(readOnly = true)
    public ProjectConfigResponse get(@AuthenticationPrincipal User actor,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);

        var statuses = projectConfigService.statuses(project).stream()
                .map(StatusResponse::of).toList();
        var transitions = projectConfigService.transitions(project).stream()
                .map(t -> new ProjectConfigResponse.TransitionRule(
                        t.getFromStatus() == null ? null : t.getFromStatus().getId(),
                        t.getToStatus().getId()))
                .toList();
        var priorities = projectConfigService.priorityItems(project).stream()
                .map(i -> new ProjectConfigResponse.PriorityOption(
                        i.getPriority().getId(), i.getPriority().getName(),
                        i.getPriority().getColor(), i.getPriority().getIcon(),
                        i.isDefaultForNewIssues()))
                .toList();
        // Types come from the project's type set since M3 (set display order)
        var types = projectConfigService.types(project).stream()
                .filter(t -> t.getArchivedAt() == null)
                .map(IssueTypeResponse::of).toList();
        var fields = fieldValueService.fields(project).stream()
                .filter(i -> i.getField().getArchivedAt() == null)
                .map(i -> new ProjectConfigResponse.FieldOption(
                        i.getField().getId(), i.getField().getKey(), i.getField().getName(),
                        i.getField().getType(), i.getField().getConfig(), i.getField().getDescription(),
                        i.isRequired(), i.isShowOnCreate()))
                .toList();

        return new ProjectConfigResponse(statuses, transitions, priorities, types, fields);
    }
}
