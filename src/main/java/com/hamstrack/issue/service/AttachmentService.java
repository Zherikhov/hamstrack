package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AttachmentProperties;
import com.hamstrack.common.event.AttachmentAdded;
import com.hamstrack.common.event.AttachmentDeleted;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.storage.FileStorage;
import com.hamstrack.issue.dto.AttachmentResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import com.hamstrack.issue.exception.AttachmentNotFoundException;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final WorkspaceAccessService workspaceAccess;
    private final ProjectMemberRepository projectMemberRepository;
    /** HD-123 S1 bridge: translates a {@code roles} row back to the legacy enum. */
    private final RoleCatalog roleCatalog;
    private final IssueAttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductMetrics metrics;
    private final AttachmentProperties attachmentProperties;
    private final TransactionTemplate txTemplate;

    public record AttachmentDownload(String filename, String contentType, long sizeBytes, InputStream stream) {}

    // Row-and-response holder assembled inside the reserve tx, so nothing after
    // commit touches a lazy association (open-in-view=false).
    private record ReservedAttachment(UUID attachmentId, String storageKey, String contentType,
                                      long sizeBytes, AttachmentResponse response) {}

    /**
     * Upload orchestrator — deliberately NOT {@code @Transactional}. The blob write
     * runs OFF the DB transaction so a slow/hung storage backend (bounded by HD-76)
     * can never pin a Hikari connection until the pool exhausts. Flow:
     * <ol>
     *   <li>a short tx reserves + commits the attachment row (tenant scoping + the
     *       server-generated key resolved inside it),</li>
     *   <li>{@code fileStorage.store} runs after commit on the request thread (the
     *       client still blocks until the blob is durably stored),</li>
     *   <li>on store failure a second short tx deletes ONLY the reserved row (scoped
     *       to the resolved issue), then the failure is rethrown as 500.</li>
     * </ol>
     * Because {@code @Transactional} self-invocation would bypass the proxy, the two
     * DB steps run via {@link TransactionTemplate} rather than same-bean calls.
     */
    public AttachmentResponse upload(User actor, UUID workspaceId, UUID projectId, long issueNumber, MultipartFile file) {
        // Cheap pre-checks (no DB) so an empty upload never reserves a row.
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        validateUpload(file);

        // Step 1 — short tx: resolve (tenant scoping) + persist the row, assemble the
        // response, and commit. The key is server-generated from the resolved ids.
        var reserved = txTemplate.execute(status -> {
            var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
            requireNotArchived(issue);

            var filename = sanitizeFilename(file.getOriginalFilename());
            var attachment = new IssueAttachment();
            attachment.setIssue(issue);
            attachment.setFilename(filename);
            attachment.setSizeBytes(file.getSize());
            // Never trust the client Content-Type: derive a safe one from the filename.
            // A malformed client header would otherwise be stored verbatim and later
            // 500 the download (MediaType.parseMediaType) or spoof how a browser renders.
            attachment.setContentType(MediaTypeFactory.getMediaType(filename)
                    .map(MediaType::toString).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
            attachment.setUploadedBy(actor);
            // Key is server-generated (no user input) — the original filename lives only in the DB
            attachment.setStorageKey("ws/" + workspaceId + "/issues/" + issue.getId() + "/" + UUID.randomUUID());
            attachmentRepository.save(attachment);
            return new ReservedAttachment(attachment.getId(), attachment.getStorageKey(),
                    attachment.getContentType(), attachment.getSizeBytes(), AttachmentResponse.of(attachment));
        });

        // Step 2 — off any tx (row already committed): write the blob. A store failure
        // (IOException / SdkException / HD-76 timeout) triggers compensation below.
        try (var in = file.getInputStream()) {
            fileStorage.store(reserved.storageKey(), in, reserved.sizeBytes(), reserved.contentType());
        } catch (IOException | RuntimeException e) {
            // Step 3 — compensate: delete ONLY the reserved row (scoped to the resolved
            // issue). If compensation itself fails, log the orphan for out-of-band
            // cleanup (mirrors deleteFromStorageAfterCommit) and still surface the 500.
            compensateFailedUpload(actor, workspaceId, projectId, issueNumber,
                    reserved.attachmentId(), reserved.storageKey());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
        }

        // Only signal "added" once the blob truly exists. NOTE: this method is NOT
        // @Transactional (blob I/O runs off the tx, HD-76), so this event is published
        // with no active transaction — the AFTER_COMMIT listener fires it inline via
        // fallbackExecution = true (see SseEventListener), reproducing the old
        // SseRegistry.afterCommit no-tx branch.
        metrics.attachmentUploaded(reserved.sizeBytes());
        eventPublisher.publishEvent(new AttachmentAdded(workspaceId, projectId, issueNumber));
        return reserved.response();
    }

    // Compensating delete for a failed store — a short tx that re-resolves the issue
    // (tenant scoping) and removes only the reserved attachment belonging to it. Never
    // a global deleteById, so a race can't delete another issue's/tenant's row.
    private void compensateFailedUpload(User actor, UUID workspaceId, UUID projectId, long issueNumber,
                                        UUID attachmentId, String storageKey) {
        try {
            txTemplate.executeWithoutResult(status -> {
                var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
                attachmentRepository.findByIdAndIssue(attachmentId, issue)
                        .ifPresent(attachmentRepository::delete);
            });
        } catch (RuntimeException ce) {
            log.warn("Failed to compensate (delete) orphaned attachment {} key {}",
                    attachmentId, storageKey, ce);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        return attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue).stream()
                .map(AttachmentResponse::of)
                .toList();
    }

    // Metadata resolved in a short read tx, materialized into a plain record so the
    // DB connection is released BEFORE the (potentially slow, HD-76-bounded) blob read.
    private record AttachmentMeta(String filename, String contentType, long sizeBytes, String storageKey) {}

    /**
     * Non-{@code @Transactional} orchestrator: look up the attachment metadata in a
     * short read tx (tenant scoping preserved), then open the blob stream OUTSIDE the
     * tx so the DB connection isn't held while the response streams to the client.
     */
    public AttachmentDownload download(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID attachmentId) {
        var meta = txTemplate.execute(status -> {
            var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
            var attachment = findAttachmentOnIssue(attachmentId, issue);
            return new AttachmentMeta(attachment.getFilename(), attachment.getContentType(),
                    attachment.getSizeBytes(), attachment.getStorageKey());
        });
        // Open outside the tx — the read connection is already released.
        return new AttachmentDownload(meta.filename(), meta.contentType(), meta.sizeBytes(),
                fileStorage.open(meta.storageKey()));
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID attachmentId) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        requireNotArchived(issue);
        var attachment = findAttachmentOnIssue(attachmentId, issue);
        if (!attachment.getUploadedBy().getId().equals(actor.getId())
                && !hasProjectRole(actor, issue, ProjectRole.MANAGER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        attachmentRepository.delete(attachment);
        deleteFromStorageAfterCommit(attachment.getStorageKey());

        eventPublisher.publishEvent(new AttachmentDeleted(workspaceId, projectId, issueNumber));
    }

    /**
     * Called by IssueService.delete — DB rows go away via ON DELETE CASCADE, but the
     * blobs must be cleaned up explicitly (after commit, so a rollback keeps them).
     */
    @Transactional
    public void removeStoredFilesForIssue(Issue issue) {
        attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue)
                .forEach(a -> deleteFromStorageAfterCommit(a.getStorageKey()));
    }

    // Blob deletion must not precede the commit (a rollback can't restore the file),
    // and a storage failure must not fail the request — the row is gone, the orphan
    // blob is only a cleanup concern
    private void deleteFromStorageAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    fileStorage.delete(storageKey);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete stored attachment {}", storageKey, e);
                }
            }
        });
    }

    // Business-policy gate (size + file type), enforced here rather than only at
    // the servlet multipart layer so a future global-admin setting can drive it.
    private void validateUpload(MultipartFile file) {
        var limit = attachmentProperties.maxFileSize();
        if (file.getSize() > limit.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "File exceeds the " + limit.toMegabytes() + " MB limit");
        }
        var ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        boolean allowed = ext != null && attachmentProperties.allowedExtensions().stream()
                .anyMatch(a -> a.equalsIgnoreCase(ext));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type '" + (ext == null ? "" : ext) + "' is not allowed. Allowed types: "
                            + String.join(", ", attachmentProperties.allowedExtensions()));
        }
    }

    private String sanitizeFilename(String original) {
        var name = original != null ? StringUtils.getFilename(original) : null;
        if (name == null || name.isBlank()) name = "file";
        return truncate(name, 255);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void requireNotArchived(Issue issue) {
        if (issue.getProject().isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    /**
     * <p>HD-123 S1: {@code project_members.role} is a {@code roles} row now, so the
     * ordinal comparison reads the role's key through the cached view (0 queries). The
     * predicate is unchanged. S2 replaces the whole thing with
     * {@code ctx.permissions().require(ATTACHMENT_DELETE, isUploader)} — this is one of
     * the two authorization sites in the codebase that bypass {@code ScopeResolver}
     * entirely (the other is {@code LabelService.requireEditor}), which is exactly why it
     * has its own row in {@code PermissionParityTest}.
     */
    @SuppressWarnings("deprecation")
    private boolean hasProjectRole(User actor, Issue issue, ProjectRole required) {
        return projectMemberRepository.findByProjectAndUser(issue.getProject(), actor)
                .map(pm -> roleCatalog.view(pm.getRole().getId()).asProjectRole().isAtLeast(required))
                .orElse(false);
    }

    // The attachment must belong to the issue resolved from the URL — a global findById
    // would let callers reach attachments in workspaces they aren't members of
    private IssueAttachment findAttachmentOnIssue(UUID attachmentId, Issue issue) {
        return attachmentRepository.findById(attachmentId)
                .filter(a -> a.getIssue().getId().equals(issue.getId()))
                .orElseThrow(AttachmentNotFoundException::new);
    }

}
