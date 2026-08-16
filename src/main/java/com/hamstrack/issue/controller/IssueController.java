package com.hamstrack.issue.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.dto.*;
import com.hamstrack.issue.service.AttachmentService;
import com.hamstrack.issue.service.CommentService;
import com.hamstrack.issue.service.IssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Sort;
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
 *
 * <p>Beyond its taxonomy an issue also carries <em>classification content</em>:
 * workspace {@code labels} (HD-30), one project {@code component} (HD-31) and the
 * fix/affects {@code versions} (HD-32). The collection-valued ones
 * ({@code labelIds}, {@code fixVersionIds}, {@code affectsVersionIds}) are
 * FULL-REPLACEMENT sets on create/update — {@code []} clears, absent leaves
 * unchanged — while the single-valued {@code componentId} follows the
 * {@code assigneeId}/{@code clearAssignee} convention. An unknown, foreign-tenant
 * or archived id in any of them is a <strong>422</strong>, never a 404.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects/{projectId}/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;
    private final CommentService commentService;
    private final AttachmentService attachmentService;

    /**
     * Create an issue. The service assembles AND serializes the response body
     * inside the create transaction (returning raw JSON bytes), so the request is
     * atomic from the client's view — a 201 with a complete body, or a 4xx/5xx
     * with nothing persisted. This closes the "row committed, then 500 during
     * post-commit response serialization → client retries → duplicate" defect.
     */
    @PostMapping
    public ResponseEntity<byte[]> create(@AuthenticationPrincipal User actor,
                                         @PathVariable UUID workspaceId,
                                         @PathVariable UUID projectId,
                                         @Valid @RequestBody CreateIssueRequest req) {
        var body = issueService.createSerialized(actor, workspaceId, projectId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Issue list. Without {@code size} → the board view: a capped
     * {@link BoardIssuesResponse} ({@code { issues, truncated, totalAvailable, cap }})
     * bounded server-side by {@code app.board.max-issues} (HD-79 — the board needs
     * every card, but not an unbounded list). With {@code size} → a
     * {@link PageResponse} (backlog); {@code excludeDone} drops DONE-category
     * statuses server-side.
     *
     * <p><strong>Label filter (HD-30 §3.6) is server-side</strong>, unlike the HD-43
     * quick-filter chips: repeat {@code ?labelId=} per label and set
     * {@code labelMatch=any|all} (default {@code any}). It must search the whole
     * project, not just the capped page already on screen — so it is a query
     * parameter, ANDed with {@code statusId}/{@code assigneeId}/{@code priorityId}.
     * {@code labelMatch} is a closed set ({@link LabelMatch}) — an unknown value is a
     * 400, never a silent fall-through to the broader "any"; the number of distinct
     * {@code labelId} values is capped at
     * {@code app.classification.max-labels-per-issue} (400 beyond it).
     *
     * <p><strong>Component filter (HD-31 §3.6)</strong> is server-side for the same
     * reason: {@code ?componentId=} is a single optional uuid ANDed with everything
     * above. It is a plain ToOne column, so it needs no sub-select. An id from another
     * project simply matches nothing — never an error, never a leak.
     *
     * <p><strong>Fix-version filter (HD-32 §3.6)</strong>, likewise server-side:
     * {@code ?fixVersionId=} is a single optional uuid ANDed with everything above,
     * compiled as a correlated EXISTS restricted to the <strong>FIX</strong> link
     * type — an affects link to the same version must not match a "fix version"
     * filter. An id from another project simply matches nothing.
     */
    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal User actor,
                                  @PathVariable UUID workspaceId,
                                  @PathVariable UUID projectId,
                                  @RequestParam(required = false) UUID statusId,
                                  @RequestParam(required = false) UUID assigneeId,
                                  @RequestParam(required = false) UUID priorityId,
                                  @RequestParam(required = false) UUID componentId,
                                  @RequestParam(required = false) List<UUID> labelId,
                                  @RequestParam(defaultValue = "any") String labelMatch,
                                  @RequestParam(required = false) UUID fixVersionId,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(defaultValue = "false") boolean excludeDone) {
        // Parsed here (not bound as an enum @RequestParam): Spring's String→enum
        // conversion is case-sensitive, and the wire contract is lowercase any/all.
        var match = LabelMatch.parse(labelMatch);
        if (size == null) {
            return ResponseEntity.ok(issueService.listCapped(
                    actor, workspaceId, projectId, statusId, assigneeId, priorityId,
                    componentId, labelId, match, fixVersionId));
        }
        var pageable = Paging.of(page, size,
                Sort.by(Sort.Order.asc("position"), Sort.Order.desc("createdAt")));
        return ResponseEntity.ok(issueService.listPaged(
                actor, workspaceId, projectId, statusId, assigneeId, priorityId,
                componentId, labelId, match, fixVersionId, excludeDone, pageable));
    }

    @GetMapping("/{number}")
    public IssueResponse get(@AuthenticationPrincipal User actor,
                             @PathVariable UUID workspaceId,
                             @PathVariable UUID projectId,
                             @PathVariable long number) {
        return issueService.get(actor, workspaceId, projectId, number);
    }

    /** Direct children of an issue, in board order. Same tenant scoping as the issue itself. */
    @GetMapping("/{number}/children")
    public List<IssueResponse> children(@AuthenticationPrincipal User actor,
                                        @PathVariable UUID workspaceId,
                                        @PathVariable UUID projectId,
                                        @PathVariable long number) {
        return issueService.children(actor, workspaceId, projectId, number);
    }

    @GetMapping("/{number}/history")
    public PageResponse<IssueHistoryResponse> history(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId,
                                                      @PathVariable UUID projectId,
                                                      @PathVariable long number,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return issueService.getHistory(actor, workspaceId, projectId, number,
                Paging.of(page, size, Sort.by("createdAt").ascending()));
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
    public PageResponse<CommentResponse> listComments(@AuthenticationPrincipal User actor,
                                                      @PathVariable UUID workspaceId,
                                                      @PathVariable UUID projectId,
                                                      @PathVariable long number,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return commentService.list(actor, workspaceId, projectId, number,
                Paging.of(page, size, Sort.by("createdAt").ascending()));
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
        // New uploads store a server-derived type, but legacy rows may hold a
        // malformed client-supplied one — fall back instead of 500ing the download.
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
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
