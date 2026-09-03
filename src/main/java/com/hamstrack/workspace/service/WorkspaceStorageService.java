package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AttachmentProperties;
import com.hamstrack.common.config.StorageQuotaProperties;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.security.Permission;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.workspace.dto.ProjectStorageEntry;
import com.hamstrack.workspace.dto.WorkspaceStorageByProjectResponse;
import com.hamstrack.workspace.dto.WorkspaceStorageResponse;
import com.hamstrack.workspace.entity.WorkspaceStorageUsage;
import com.hamstrack.workspace.exception.StorageQuotaExceededException;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reading and enforcing a workspace's attachment storage ceiling (HD-191).
 *
 * <p>Three things live here and they have three different callers: the summary every member may
 * read, the per-project breakdown behind {@code workspace.edit}, and the reservation the upload
 * path takes before a single byte reaches {@code FileStorage}.
 *
 * <p><strong>Tenancy.</strong> Both reads resolve through
 * {@link WorkspaceAccessService#requireMember}, so a non-existent workspace and a non-member are
 * <strong>both 404</strong>, never 403 — and the usage aggregate is keyed on the resolved
 * workspace's own id, never on anything taken from the request. A 403 appears on exactly one
 * door: a proven member without {@code workspace.edit} asking for the breakdown.
 *
 * <p><strong>No new permission constant.</strong> {@code workspace.edit} is what the built-in
 * Owner and Admin hold and is already the grant meaning "workspace-wide settings". A permission
 * key is wire format and permanent; minting one for a single read is a permanent commitment
 * bought for nothing.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceStorageService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceStorageUsageRepository usageRepository;
    private final IssueAttachmentRepository attachmentRepository;
    private final StorageQuotaProperties quotaProperties;
    private final AttachmentProperties attachmentProperties;
    private final LockTimeout lockTimeout;

    /**
     * The workspace summary — <strong>any member</strong>, no permission beyond membership.
     *
     * <p>It returns the same figures a quota refusal already hands the same person, which is the
     * whole argument for the open gate: a member who cannot see how full the workspace is cannot
     * tell "I am blocked" from "the server is broken".
     *
     * <p>A missing counter row reads as zero rather than as an error. Nothing couples this to
     * workspace creation: V26 seeded a row per workspace and the reservation upserts one, but a
     * workspace with neither is simply empty, which is true.
     */
    @Transactional(readOnly = true)
    public WorkspaceStorageResponse summary(User actor, UUID workspaceId) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
        var usage = usageRepository.findByWorkspaceId(ws.workspace().getId());
        long used = usage.map(WorkspaceStorageUsage::getBytesUsed).orElse(0L);
        long count = usage.map(WorkspaceStorageUsage::getAttachmentCount).orElse(0L);
        OffsetDateTime asOf = usage.map(WorkspaceStorageUsage::getUpdatedAt).orElse(null);

        boolean enabled = quotaProperties.enabled();
        long quota = quotaProperties.workspaceBytes().toBytes();
        return new WorkspaceStorageResponse(
                enabled,
                // Absent numbers, never a sentinel: with no ceiling there is no "remaining", and a
                // -1 is a value a client will eventually render as "-1 GB left".
                enabled ? quota : null,
                used,
                enabled ? Math.max(0, quota - used) : null,
                count,
                // NOT clamped at 100 — lowering the quota below current usage is a legitimate
                // operator action, and clamping would hide the state that explains the refusals.
                // The divisor cannot be zero: StorageQuotaConsistency refuses a non-positive
                // max-file-size and a quota below it at startup, which is where that guard has to
                // live — a zero here is a JSON `Infinity` token, i.e. a body no client can parse,
                // rather than a wrong number somebody would notice.
                enabled ? (used * 100.0) / quota : null,
                quotaProperties.warnAtPercent(),
                attachmentProperties.maxFileSize().toBytes(),
                asOf);
    }

    /**
     * Where the space went, by project — <strong>{@code workspace.edit}</strong>.
     *
     * <p>Gated, and the summary is not, because this is real disclosure: it names projects and
     * their volumes, including projects the caller may not be a member of. That is the owner's
     * answer to "where did the space go" and it is not everybody's business.
     *
     * <p><strong>What bounds the response: the number of projects in one workspace.</strong> There
     * is no page and no limit — one row per project that holds at least one attachment, each of
     * them a name, an id and two numbers. That is deliberate — the page is a reconciliation of a
     * total, and a total assembled from a truncated list is a wrong total — and what bounds it is
     * that the caller must hold {@code workspace.edit} and that a workspace's project count is
     * bounded by what its own members create. It is a single indexed aggregate on
     * {@code issue_attachments.workspace_id}, so the cost is in the response size, not the query.
     * If projects per workspace ever stop being a small number this is the read that has to grow
     * a page — a visible change to the response shape, never a silent truncation.
     *
     * <p>{@code unattributedBytes} is the counter's total minus what the rows add up to, and it
     * is published rather than folded away: a non-zero value is exactly the drift an operator
     * needs to see, and normalising it would make the page lie in the one state it exists for.
     */
    @Transactional(readOnly = true)
    public WorkspaceStorageByProjectResponse byProject(User actor, UUID workspaceId) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
        ws.permissions().require(Permission.WORKSPACE_EDIT);

        var id = ws.workspace().getId();
        var usage = usageRepository.findByWorkspaceId(id);
        long total = usage.map(WorkspaceStorageUsage::getBytesUsed).orElse(0L);
        OffsetDateTime asOf = usage.map(WorkspaceStorageUsage::getUpdatedAt).orElse(null);

        List<ProjectStorageEntry> rows =
                attachmentRepository.storageByProject(id);
        long attributed = rows.stream().mapToLong(ProjectStorageEntry::bytes).sum();

        return new WorkspaceStorageByProjectResponse(asOf, total, total - attributed, rows);
    }

    /**
     * <strong>Take the reservation for one upload, or refuse it.</strong> Called from inside the
     * upload's short transaction, after tenancy and authorization and after the archived-project
     * check, and <em>before</em> the attachment row is saved — so a refusal leaves no row, no
     * blob and no counter change.
     *
     * <p><strong>{@link Propagation#MANDATORY}</strong>: outside a transaction the lock below is
     * released at once and the check-then-write race it exists to forbid comes straight back,
     * silently. The same posture {@link LockTimeout} takes, and for the same reason — a guard
     * that quietly stops guarding is worse than no guard.
     *
     * <p>The sequence, and every step earns its place:
     * <ol>
     *   <li>{@code lockTimeout.applyToCurrentTransaction()} — <strong>bound, then lock.</strong>
     *       The standing rule; a bound applied after a locking read bounds nothing. A wait past
     *       it surfaces as the existing 409 + {@code Retry-After} from
     *       {@code handlePessimisticLock}, which is right here: unlike the quota refusal, a lost
     *       lock race really is fixed by retrying.</li>
     *   <li>{@code ensureRow} — idempotent, because {@code SELECT … FOR UPDATE} locks rows that
     *       exist and silently locks nothing otherwise, which would switch the mutual exclusion
     *       off on a workspace's very first concurrent uploads.</li>
     *   <li>{@code lockAndReadBytesUsed} — <strong>on {@code workspace_storage_usage}, never on
     *       {@code workspaces}.</strong> Nothing references this table, so the lock serialises
     *       only concurrent uploads into one workspace and blocks no FK child insert anywhere in
     *       the tenant. Choosing a lockable row that is not the parent is half the reason the
     *       counter is a table of its own.</li>
     *   <li>compare and refuse. The comparison is {@code >}, so a file that exactly fills the
     *       quota is accepted.</li>
     * </ol>
     *
     * <p><strong>The size is the PARSED one</strong> ({@code MultipartFile.getSize()}), never a
     * client-declared {@code Content-Length} — so the accounting cannot be understated by a lying
     * header.
     *
     * <p><strong>It takes the PROOF, not an id</strong> (HD-191 R4). A {@code WorkspaceContext}
     * is produced only by {@link WorkspaceAccessService}, so membership is established by the
     * argument's type rather than by a sentence in this javadoc that a future call site has to
     * read. The method reads that workspace's tenant-wide total and puts it in a 409 body; taking
     * a bare {@code UUID} would mean one caller passing an id straight from a URL discloses
     * another tenant's aggregate, with no error and nothing to review — and "today's only call
     * site is correct" is a claim about today. The upload path already holds an
     * {@code IssueContext}, so the proof costs it one token.
     *
     * <p><strong>Counted even when the quota is disabled — and not refused.</strong> With
     * {@code app.storage.quota.enabled=false} this method still runs, still takes the lock and
     * the counter still follows the rows; only the comparison is skipped. Turning the switch back
     * on must not resume from a blank number, which is {@code RecipientMailThrottle}'s reasoning
     * applied to a ceiling that is measured rather than timed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(WorkspaceContext workspace, long fileBytes) {
        var workspaceId = workspace.workspace().getId();
        lockTimeout.applyToCurrentTransaction();
        usageRepository.ensureRow(workspaceId);
        Long used = usageRepository.lockAndReadBytesUsed(workspaceId);
        long usedBytes = used == null ? 0L : used;

        if (!quotaProperties.enabled()) {
            return;
        }
        long quota = quotaProperties.workspaceBytes().toBytes();
        if (usedBytes + fileBytes > quota) {
            throw new StorageQuotaExceededException(quota, usedBytes, fileBytes);
        }
    }
}
