package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.CreateStatusTransitionRequest;
import com.hamstrack.issue.dto.StatusTransitionResponse;
import com.hamstrack.issue.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workflow rules: which status-to-status transitions are allowed for issues
 * in the workspace. When no transitions are configured for a source status,
 * any move from it is permitted; once at least one exists, only the listed
 * targets are accepted (enforced on issue updates and board drag-and-drop).
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/status-transitions")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public List<StatusTransitionResponse> list(@AuthenticationPrincipal User actor,
                                               @PathVariable UUID workspaceId) {
        return workflowService.list(actor, workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StatusTransitionResponse create(@AuthenticationPrincipal User actor,
                                           @PathVariable UUID workspaceId,
                                           @Valid @RequestBody CreateStatusTransitionRequest req) {
        return workflowService.create(actor, workspaceId, req);
    }

    @DeleteMapping("/{transitionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID transitionId) {
        workflowService.delete(actor, workspaceId, transitionId);
    }
}
