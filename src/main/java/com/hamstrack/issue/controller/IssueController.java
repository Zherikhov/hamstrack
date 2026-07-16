package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.dto.*;
import com.hamstrack.issue.service.AttachmentService;
import com.hamstrack.issue.service.CommentService;
import com.hamstrack.issue.service.IssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Issues and their sub-resources (comments, attachments, change history).
 * Issues are addressed by their project-scoped {@code number} (the numeric
 * part of "DEMO-42"), not by UUID. Updates use optimistic locking via the
 * {@code version} field (409 on a stale version) and status changes are
 * validated against configured workflow transitions. Comment edits/deletes
 * are author-only; attachment deletion is allowed to the uploader or a
 * project MANAGER. Deleting an issue requires MANAGER and removes stored
 * attachment blobs.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;
    private final CommentService commentService;
    private final AttachmentService attachmentService;

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
                                    @RequestParam(required = false) UUID priorityId) {
        return issueService.list(actor, workspaceId, projectId, statusId, assigneeId, priorityId);
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

    // --- Attachments ---

    @PostMapping("/{number}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse uploadAttachment(@AuthenticationPrincipal User actor,
                                               @PathVariable UUID workspaceId,
                                               @PathVariable UUID projectId,
                                               @PathVariable long number,
                                               @RequestParam("file") MultipartFile file) {
        return attachmentService.upload(actor, workspaceId, projectId, number, file);
    }

    @GetMapping("/{number}/attachments")
    public List<AttachmentResponse> listAttachments(@AuthenticationPrincipal User actor,
                                                    @PathVariable UUID workspaceId,
                                                    @PathVariable UUID projectId,
                                                    @PathVariable long number) {
        return attachmentService.list(actor, workspaceId, projectId, number);
    }

    @GetMapping("/{number}/attachments/{attachmentId}")
    public ResponseEntity<InputStreamResource> downloadAttachment(@AuthenticationPrincipal User actor,
                                                                  @PathVariable UUID workspaceId,
                                                                  @PathVariable UUID projectId,
                                                                  @PathVariable long number,
                                                                  @PathVariable UUID attachmentId) {
        var download = attachmentService.download(actor, workspaceId, projectId, number, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(download.filename(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(new InputStreamResource(download.stream()));
    }

    @DeleteMapping("/{number}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@AuthenticationPrincipal User actor,
                                 @PathVariable UUID workspaceId,
                                 @PathVariable UUID projectId,
                                 @PathVariable long number,
                                 @PathVariable UUID attachmentId) {
        attachmentService.delete(actor, workspaceId, projectId, number, attachmentId);
    }
}
