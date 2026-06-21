package com.easytask.issue.controller;

import com.easytask.issue.dto.ChangeIssueStatusRequest;
import com.easytask.issue.dto.CreateIssueRequest;
import com.easytask.issue.dto.IssueHistoryResponse;
import com.easytask.issue.dto.IssueResponse;
import com.easytask.issue.dto.MoveIssueRequest;
import com.easytask.issue.dto.UpdateIssueRequest;
import com.easytask.auth.entity.User;
import com.easytask.issue.service.IssueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/issues")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @PostMapping
    public ResponseEntity<IssueResponse> create(@AuthenticationPrincipal User currentUser,
                                                  @PathVariable UUID workspaceId,
                                                  @PathVariable UUID projectId,
                                                  @Valid @RequestBody CreateIssueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(issueService.createIssue(currentUser, workspaceId, projectId, request));
    }

    @GetMapping
    public List<IssueResponse> list(@AuthenticationPrincipal User currentUser,
                                     @PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId) {
        return issueService.listIssues(currentUser, workspaceId, projectId);
    }

    @GetMapping("/{issueId}")
    public IssueResponse get(@AuthenticationPrincipal User currentUser,
                              @PathVariable UUID workspaceId,
                              @PathVariable UUID projectId,
                              @PathVariable UUID issueId) {
        return issueService.getIssue(currentUser, workspaceId, projectId, issueId);
    }

    @PatchMapping("/{issueId}")
    public IssueResponse update(@AuthenticationPrincipal User currentUser,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID projectId,
                                 @PathVariable UUID issueId,
                                 @Valid @RequestBody UpdateIssueRequest request) {
        return issueService.updateIssue(currentUser, workspaceId, projectId, issueId, request);
    }

    @PatchMapping("/{issueId}/status")
    public IssueResponse changeStatus(@AuthenticationPrincipal User currentUser,
                                       @PathVariable UUID workspaceId,
                                       @PathVariable UUID projectId,
                                       @PathVariable UUID issueId,
                                       @Valid @RequestBody ChangeIssueStatusRequest request) {
        return issueService.changeStatus(currentUser, workspaceId, projectId, issueId, request);
    }

    @PatchMapping("/{issueId}/move")
    public IssueResponse move(@AuthenticationPrincipal User currentUser,
                               @PathVariable UUID workspaceId,
                               @PathVariable UUID projectId,
                               @PathVariable UUID issueId,
                               @Valid @RequestBody MoveIssueRequest request) {
        return issueService.moveIssue(currentUser, workspaceId, projectId, issueId, request);
    }

    @GetMapping("/{issueId}/history")
    public List<IssueHistoryResponse> history(@AuthenticationPrincipal User currentUser,
                                               @PathVariable UUID workspaceId,
                                               @PathVariable UUID projectId,
                                               @PathVariable UUID issueId) {
        return issueService.listHistory(currentUser, workspaceId, projectId, issueId);
    }
}
