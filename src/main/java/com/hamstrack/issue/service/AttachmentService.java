package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AttachmentProperties;
import com.hamstrack.common.event.AttachmentAdded;
import com.hamstrack.common.event.AttachmentDeleted;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.ratelimit.UploadByteBudget;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.storage.FileStorage;
import com.hamstrack.common.tx.AfterCommit;
import com.hamstrack.issue.dto.AttachmentResponse;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import com.hamstrack.issue.exception.AttachmentNotFoundException;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import com.hamstrack.workspace.service.WorkspaceStorageService;
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
    /** HD-191: the per-principal byte budget, spent before any DB work. */
    private final UploadByteBudget uploadByteBudget;
    /** HD-191: the per-workspace quota reservation, spent inside the reserve transaction. */
    private final WorkspaceStorageService workspaceStorage;

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
     *
     * <p><strong>Two HD-191 controls are spent here, in two different places, for two
     * different reasons.</strong> The per-principal byte budget is spent in the cheap
     * pre-check phase — before any DB work, so a refused upload takes no lock and touches
     * no row — and that is safe despite running before tenancy resolution because its key
     * is the CALLER: the 429 is identical for a real workspace, a nonexistent one and
     * somebody else's, so the 404-for-all-three contract is untouched. The workspace quota
     * reservation is spent INSIDE step 1's transaction, after tenancy, after the permission
     * and after the archived check, and before the row is saved — which is what makes
     * "<strong>no byte of a refused upload is ever passed to {@code FileStorage}</strong>"
     * true by construction rather than by ordering luck: {@code fileStorage.store} is only
     * reached after that transaction commits.
     *
     * <p>The resulting refusal order on this door is
     * {@code 400 empty} → {@code 413 too large} → {@code 415 bad extension} →
     * {@code 429} byte budget → {@code 404} non-member/unknown issue →
     * {@code 403} missing {@code attachment.create} → {@code 409} archived project →
     * {@code 409} quota — the budget FOURTH, before tenancy, because it is spent in the
     * pre-check phase above and not inside the transaction. That position is safe rather
     * than merely convenient: the budget is keyed on the CALLER, so its 429 is byte-for-byte
     * identical for a real workspace, a nonexistent one and somebody else's, and a refusal
     * that cannot vary with the target discloses nothing about it — the 404-for-all-three
     * contract survives a 429 arriving first. Cheapest first is the other half: the request
     * that is refused here has taken no lock and read no row. Three rules pin the rest and
     * all predate this ticket: a 403 must never depend on project state (so the permission
     * precedes the archived check), a refused request must not have paid for a lock (so
     * every cheap check precedes the reservation), and between the two 409s archived comes
     * first — it is about <em>this</em> project, it needs no lock, and a quota message on an
     * archived project would send the reader to fix the wrong thing. The order is asserted
     * by {@code WriteBudgetTest.theByteBudgetIsSpentBeforeTenancyAndItsRefusalNamesNoTarget},
     * so this list cannot go stale silently again — it already did once, naming the 429
     * seventh while the code spent it fourth.
     *
     * <p><strong>What neither control bounds, stated so nobody re-derives it from a
     * surprise.</strong> {@code spring.servlet.multipart.resolve-lazily} is unset, so multipart
     * resolution is EAGER: {@code DispatcherServlet.checkMultipart} parses the request before
     * {@code getHandler()}, therefore before every interceptor and before this method. Two
     * consequences, and the second is the one that is easy to write backwards:
     * <ul>
     *   <li><strong>A body over {@code spring.servlet.multipart.max-request-size} is refused by
     *       the parser, so no handler is ever mapped, so NEITHER budget is spent.</strong> Tomcat
     *       streams and buffers until the cap trips and the 413 comes from the exception handler
     *       — one socket read and one temp file, charged to nobody. In-process that is the
     *       cheapest lane on this door and the only one with no bound at all.</li>
     *   <li><strong>Under the cap, the budgets bound the rate at which uploads are ACCEPTED, not
     *       the rate at which bytes are ingested</strong> — a request that ends in 429 has
     *       already been parsed.</li>
     * </ul>
     *
     * <p>So the bound on the pre-parse lane lives at the EDGE, which is the only place a body can
     * be refused before this process allocates anything for it: the shipped {@code Caddyfile}
     * sets {@code request_body max_size} from {@code ATTACHMENT_MAX_UPLOAD_SIZE} — the same
     * variable that feeds the servlet ceiling, so the two cannot drift — and
     * {@code docs/self-hosting.md} tells an operator fronting the stack with their own proxy to
     * match it.
     *
     * <p><strong>Deliberately not also closed with a servlet {@code Filter}.</strong> The option
     * is real — a filter runs before {@code checkMultipart} and the {@code SecurityContext} is
     * already populated, so the pre-parse lane could be made to cost a write unit — and it is
     * declined for three reasons rather than overlooked. <em>(a)</em> It gives one budget two
     * spend sites: {@code PrincipalThrottleInterceptor} already charges every {@code POST} under
     * {@code /api/workspaces/*}{@code /projects/*}{@code /issues/**}, so the filter double-charges
     * unless a request attribute tells the interceptor to stand down — and then "is this handler
     * throttled?" stops being the type question {@code WriteThrottleCoverageTest} reads off the
     * real handler chain. <em>(b)</em> An exception thrown from a filter never reaches the
     * {@code HandlerExceptionResolver} chain, so the shared 429 + {@code Retry-After} body would
     * have to be rendered a second time by hand beside the one {@code GlobalExceptionHandler}
     * produces — one declared control with two refusal shapes, which is ADR-0018's mistake in a
     * different costume. <em>(c)</em> It could only ever bound a COUNT: before the parse the only
     * size available is the client-declared {@code Content-Length}, absent entirely under chunked
     * encoding, and that is exactly the number this door refuses to trust. Bytes are bounded
     * where bytes can be refused, which is the proxy.
     */
    public AttachmentResponse upload(User actor, UUID workspaceId, UUID projectId, long issueNumber, MultipartFile file) {
        // Cheap pre-checks (no DB) so an empty upload never reserves a row.
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        validateUpload(file);
        // The PARSED size, never a client-declared Content-Length — a budget that trusted the
        // header would be a budget the client sets.
        uploadByteBudget.require(actor.getId(), file.getSize());

        // Step 1 — short tx: resolve (tenant scoping) + persist the row, assemble the
        // response, and commit. The key is server-generated from the resolved ids.
        var reserved = txTemplate.execute(status -> {
            var ictx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
            // Permission first, project state second (§10.3.6).
            ictx.permissions().require(Permission.ATTACHMENT_CREATE);
            var issue = ictx.issue();
            requireNotArchived(issue);
            // The quota reservation: bound, then lock, then compare. It takes the RESOLVED
            // context and not an id, so "keyed on the workspace requireIssue proved, never on the
            // one in the URL" is enforced by the signature rather than by this comment — reading
            // the wrong tenant's total is worse than having no quota at all. Throws 409
            // STORAGE_QUOTA_EXCEEDED, which rolls this transaction back: no row, no blob, no
            // counter change.
            workspaceStorage.reserve(ictx.workspaceContext(), file.getSize());

            var filename = sanitizeFilename(file.getOriginalFilename());
            var attachment = new IssueAttachment();
            attachment.setIssue(issue);
            // Denormalised tenant (V26). The composite FK (issue_id, workspace_id) means a
            // value that disagreed with the issue's would be refused by the database.
            attachment.setWorkspaceId(ictx.workspace().getId());
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
                    reserved.attachmentId(), reserved.storageKey(), reserved.sizeBytes());
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
    //
    // WHEN THIS COMPENSATION ITSELF FAILS, THE ARTEFACT IS AN ORPHAN *ROW*, NOT AN ORPHAN BLOB —
    // the store is what failed, so there is nothing in the backend — and it is the expensive
    // direction: the row keeps its size_bytes, the trigger has already counted them, and the
    // workspace pays quota for a blob that was never written. The reconciler cannot see it,
    // because it compares the counter against the ROWS and the row exists, so counter == rows and
    // there is no drift to correct. Nothing else will ever notice, which is why the WARN carries
    // the workspace and the byte count: they are the only way an operator can find and correct
    // the stranded reservation, and docs/self-hosting.md's orphans section names this line.
    private void compensateFailedUpload(User actor, UUID workspaceId, UUID projectId, long issueNumber,
                                        UUID attachmentId, String storageKey, long sizeBytes) {
        try {
            txTemplate.executeWithoutResult(status -> {
                var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
                attachmentRepository.findByIdAndIssue(attachmentId, issue)
                        .ifPresent(attachmentRepository::delete);
            });
        } catch (RuntimeException ce) {
            log.warn("Failed to compensate (delete) attachment row {} after a failed store; "
                     + "workspace {} now holds {} bytes of storage quota for a blob that was never "
                     + "written (key {}). This is invisible to WorkspaceStorageReconciler — the row "
                     + "exists, so the counter agrees with it — and only deleting the row returns "
                     + "the quota",
                    attachmentId, workspaceId, sizeBytes, storageKey, ce);
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
