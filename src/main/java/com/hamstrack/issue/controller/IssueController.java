package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.*;
import com.hamstrack.issue.entity.IssuePriority;
import com.hamstrack.issue.service.CommentService;
import com.hamstrack.issue.service.IssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;
    private final CommentService commentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId,
                                @PathVariable UUID projectId,
                                @Valid @RequestBody CreateIssueRequest req) {
        return issueService.create(actor, workspaceId, projectId, req);
    }

    @GetMapping
    public List<IssueResponse> list(@AuthenticationPrincipal User actor,
                                    @PathVariable UUID workspaceId,
                                    @PathVariable UUID projectId,
                                    @RequestParam(required = false) UUID statusId,
                                    @RequestParam(required = false) UUID assigneeId,
                                    @RequestParam(required = false) IssuePriority priority) {
        return issueService.list(actor, workspaceId, projectId, statusId, assigneeId, priority);
    }

    @GetMapping("/{number}")
    public IssueResponse get(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId,
                             @PathVariable UUID projectId,
                             @PathVariable long number) {
        return issueService.get(actor, workspaceId, projectId, number);
    }

    @GetMapping("/{number}/history")
    public List<IssueHistoryResponse> history(@AuthenticationPrincipal User actor,
                                              @PathVariable UUID workspaceId,
                                              @PathVariable UUID projectId,
                                              @PathVariable long number) {
        return issueService.getHistory(actor, workspaceId, projectId, number);
    }

    @PatchMapping("/{number}")
    public IssueResponse update(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId,
                                @PathVariable UUID projectId,
                                @PathVariable long number,
                                @Valid @RequestBody UpdateIssueRequest req) {
        return issueService.update(actor, workspaceId, projectId, number, req);
    }

    @DeleteMapping("/{number}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal User actor,
                       @PathVariable UUID workspaceId,
                       @PathVariable UUID projectId,
                       @PathVariable long number) {
        issueService.delete(actor, workspaceId, projectId, number);
    }

    // --- Comments ---

    @PostMapping("/{number}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse createComment(@AuthenticationPrincipal User actor,
                                         @PathVariable UUID workspaceId,
                                         @PathVariable UUID projectId,
                                         @PathVariable long number,
                                         @Valid @RequestBody CreateCommentRequest req) {
        return commentService.create(actor, workspaceId, projectId, number, req);
    }

    @GetMapping("/{number}/comments")
    public List<CommentResponse> listComments(@AuthenticationPrincipal User actor,
                                              @PathVariable UUID workspaceId,
                                              @PathVariable UUID projectId,
                                              @PathVariable long number) {
        return commentService.list(actor, workspaceId, projectId, number);
    }

    @PatchMapping("/{number}/comments/{commentId}")
    public CommentResponse updateComment(@AuthenticationPrincipal User actor,
                                         @PathVariable UUID workspaceId,
                                         @PathVariable UUID projectId,
                                         @PathVariable long number,
                                         @PathVariable UUID commentId,
                                         @Valid @RequestBody CreateCommentRequest req) {
        return commentService.update(actor, workspaceId, projectId, number, commentId, req);
    }

    @DeleteMapping("/{number}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@AuthenticationPrincipal User actor,
                              @PathVariable UUID workspaceId,
                              @PathVariable UUID projectId,
                              @PathVariable long number,
                              @PathVariable UUID commentId) {
        commentService.delete(actor, workspaceId, projectId, number, commentId);
    }
}
