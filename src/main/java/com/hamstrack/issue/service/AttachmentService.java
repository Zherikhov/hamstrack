package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AttachmentProperties;
import com.hamstrack.common.event.AttachmentAdded;
import com.hamstrack.common.event.AttachmentDeleted;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.storage.FileStorage;
import com.hamstrack.common.tx.AfterCommit;
import com.hamstrack.issue.dto.AttachmentResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import com.hamstrack.issue.exception.AttachmentNotFoundException;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
            var ictx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
            // Permission first, project state second (§10.3.6).
            ictx.permissions().require(Permission.ATTACHMENT_CREATE);
            var issue = ictx.issue();
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
            // issue). If compensation itself fails, log the orphan for out-of-band cleanup and
            // still surface the 500 — the same policy deleteFromStorageAfterCommit follows (record
            // it, never fail the request on it), at WARN because this one leaves a row an operator
            // can find rather than a blob only the log names.
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

    /**
     * <strong>{@link Permission#ATTACHMENT_DELETE}, with the ownership modifier</strong>
     * (§6.4): {@code own} = you uploaded the file, which is today's rule verbatim;
     * the unrestricted grant is what the built-in project MANAGER holds, matching
     * today's "uploader <em>or</em> project MANAGER".
     *
     * <p>Order matters twice. The attachment is resolved <em>before</em> the check
     * because {@code isOwn} is a property of the object, not of the actor — and it is a
     * 404-if-absent lookup scoped to an issue the caller can already read, so it
     * discloses nothing. The archived-project 409 then runs <em>after</em> the
     * permission (§10.3.6): a 403 must never depend on project state.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID attachmentId) {
        var ictx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
        var issue = ictx.issue();
        var attachment = findAttachmentOnIssue(attachmentId, issue);
        boolean isUploader = attachment.getUploadedBy().getId().equals(actor.getId());
        ictx.permissions().require(Permission.ATTACHMENT_DELETE, isUploader);
        requireNotArchived(issue);
        attachmentRepository.delete(attachment);
        deleteFromStorageAfterCommit(attachment.getStorageKey());

        eventPublisher.publishEvent(new AttachmentDeleted(workspaceId, projectId, issueNumber));
    }

    /**
     * Called by IssueService.delete — DB rows go away via ON DELETE CASCADE, but the
     * blobs must be cleaned up explicitly (after commit, so a rollback keeps them).
     *
     * <p><strong>Caller contract: this method authorizes nothing.</strong> It takes a bare
     * {@code Issue} and deletes every blob attached to it, so the argument must come from a
     * resolved {@link com.hamstrack.common.security.WorkspaceAccessService} context (as
     * {@code IssueService.delete}'s does) — handing it an unscoped
     * {@code issueRepository.findById(idFromUrl)} result is irreversible cross-tenant blob
     * deletion with no error at all.
     *
     * <p><strong>Scalar keys, never the entities</strong> (HD-137 R4). Loading the
     * {@code IssueAttachment} rows here left them MANAGED in a persistence context whose
     * next act is {@code issueRepository.delete(issue)}. At commit,
     * {@code AbstractFlushingEventListener.checkForTransientReferences} runs
     * {@code Cascade.cascade(CascadingActions.CHECK_ON_FLUSH, …)} over every MANAGED/SAVING
     * entry, walking <em>all</em> of its to-one associations — not just uncascaded ones;
     * cascade-persist children merely pass because the earlier {@code PERSIST_ON_FLUSH} pass
     * already persisted them. Its predicate is {@code CascadingActions.isChildTransient},
     * which for an entity that HAS a persistence-context entry returns
     * {@code entry.getStatus().isDeletedOrGone() && !isCascadeDeleteEnabled} — no database
     * snapshot is consulted, and the DELETE does not have to have been executed. (Only when
     * there is no entry at all does it fall through to {@code ForeignKeys.isTransient}.) So
     * the commit threw {@code TransientPropertyValueException} and rolled back:
     * <strong>deleting any issue that had at least one attachment was a 500</strong>, and
     * there was no test anywhere that deleted an issue with an attachment on it. Now covered
     * by {@code AttachmentCrudTenancyTest.deletingAnIssueThatHasAnAttachmentSucceeds}.
     *
     * <p>The projection is the fix rather than a detach: nothing scalar can be flushed, so
     * there is nothing for the commit to re-check, and no future caller can reintroduce the
     * problem by forgetting a line. (A third lever exists here and only here: the
     * {@code && !isCascadeDeleteEnabled} clause means an {@code @OnDelete(action =
     * OnDeleteAction.CASCADE)} on {@code IssueAttachment.issue} would also suppress the
     * throw, since this FK really is {@code ON DELETE CASCADE}. It is not available to
     * {@code SprintScopeLedger} — see the note there.)
     */
    @Transactional
    public void removeStoredFilesForIssue(Issue issue) {
        attachmentRepository.findStorageKeysByIssue(issue)
                .forEach(this::deleteFromStorageAfterCommit);
    }

    // Blob deletion must not precede the commit (a rollback can't restore the file), and a storage
    // failure must not fail the request — the row is gone, the orphan blob is only a cleanup
    // concern. This is where the rule was first written by hand; HD-181 gave it a name and this now
    // calls it, so there is one shape to copy rather than two to choose between. Two deliberate
    // consequences of the move: the failure is logged at ERROR rather than WARN (AfterCommit has one
    // severity, and an orphan nobody will ever come back for is what that severity is about — no
    // alert reads log level, they are all metric-based), and a call site reached with no transaction
    // deletes inline instead of throwing "synchronization is not active".
    //
    // What keeps that inline branch safe is a contract on the CALLER, not a property of the
    // transaction state at the moment of the call: callers must already have the row's deletion
    // inside a transaction when they get here. "No transaction, so there is no rollback to protect
    // the blob from" holds only for a caller that opens none afterwards either — and this class
    // already contains the other shape, since upload() is deliberately not @Transactional and runs
    // its DB work through txTemplate. Delete a blob inline and then write through a template that
    // rolls back, and the result is row present, blob gone: a live attachment whose download can
    // never be served. That is the opposite direction from the one this method tolerates (an orphan
    // blob is a cleanup concern, an orphan row is broken data), and it is what the hand-rolled code
    // this replaced refused loudly with IllegalStateException. Both callers today are proxied
    // @Transactional and removeStoredFilesForIssue opens one of its own; the contract is what keeps
    // that true of the next one.
    private void deleteFromStorageAfterCommit(String storageKey) {
        AfterCommit.run("delete of stored attachment blob " + storageKey,
                () -> fileStorage.delete(storageKey));
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

    // The attachment must belong to the issue resolved from the URL — a global findById
    // would let callers reach attachments in workspaces they aren't members of
    private IssueAttachment findAttachmentOnIssue(UUID attachmentId, Issue issue) {
        return attachmentRepository.findById(attachmentId)
                .filter(a -> a.getIssue().getId().equals(issue.getId()))
                .orElseThrow(AttachmentNotFoundException::new);
    }

}
