package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import com.hamstrack.workspace.dto.ProjectStorageEntry;
import com.hamstrack.workspace.service.StorageTotals;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueAttachmentRepository extends JpaRepository<IssueAttachment, UUID> {

    List<IssueAttachment> findAllByIssueOrderByCreatedAtAsc(Issue issue);

    // Tenant-scoped lookup for upload compensation: the attachment must belong to
    // the resolved issue, so a compensating delete can never touch another
    // issue's/tenant's row (never a global deleteById).
    Optional<IssueAttachment> findByIdAndIssue(UUID id, Issue issue);

    /**
     * The storage keys of one issue's attachments as SCALARS — deliberately not the
     * entities.
     *
     * <p>Used by the issue-delete path, where materialising the entities is not merely
     * wasteful but wrong: a managed {@code IssueAttachment} whose {@code issue} points at
     * an entity this transaction has since {@code remove}d makes Hibernate's commit-time
     * {@code checkForTransientReferences} treat the parent as transient (a DELETED entry
     * is "gone", regardless of whether the DELETE has been executed yet) and throw
     * {@code TransientPropertyValueException} — a 500 that rolls the whole delete back, so
     * an issue with an attachment could not be deleted at all. Nothing scalar can be
     * flushed, so nothing can be re-checked.
     */
    @Query("SELECT a.storageKey FROM IssueAttachment a WHERE a.issue = :issue")
    List<String> findStorageKeysByIssue(@Param("issue") Issue issue);

    /**
     * What one workspace's attachment ROWS actually add up to (HD-191) — the number
     * {@code workspace_storage_usage.bytes_used} claims to equal.
     *
     * <p>Read only by {@code WorkspaceStorageReconciler}, never on the upload path: it is exact
     * and it is O(attachments in the workspace), which is precisely why the online check reads a
     * counter instead. Indexed by {@code issue_attachments_workspace_idx} (V26), so it is a
     * single-table aggregate rather than the three-level join it would have been before that
     * column existed.
     *
     * <p>{@code bytes} is {@code null} for a workspace with no attachments (SUM over no rows),
     * which is the reason {@link StorageTotals#bytesOrZero()} exists rather than a
     * {@code COALESCE} whose literal's type would have to be pinned by hand.
     */
    @Query("""
            SELECT new com.hamstrack.workspace.service.StorageTotals(SUM(a.sizeBytes), COUNT(a))
              FROM IssueAttachment a
             WHERE a.workspaceId = :workspaceId
            """)
    StorageTotals totalsForWorkspace(@Param("workspaceId") UUID workspaceId);

    /**
     * Where the space went, by project — the {@code workspace.edit} breakdown
     * ({@code GET …/storage/projects}).
     *
     * <p><strong>Scoped by {@code a.workspaceId}, never by a project id taken from the
     * request.</strong> The grouping key comes out of the rows the predicate already restricted,
     * so a project of another tenant cannot appear in the result no matter what the caller sends
     * — there is nothing to send.
     *
     * <p>No {@code COALESCE}: every group has at least one row by construction, so the SUM
     * cannot be null here even though it can be in {@link #totalsForWorkspace}.
     */
    @Query("""
            SELECT new com.hamstrack.workspace.dto.ProjectStorageEntry(
                       p.id, p.key, p.name, SUM(a.sizeBytes), COUNT(a))
              FROM IssueAttachment a
              JOIN a.issue i
              JOIN i.project p
             WHERE a.workspaceId = :workspaceId
             GROUP BY p.id, p.key, p.name
             ORDER BY SUM(a.sizeBytes) DESC
            """)
    List<ProjectStorageEntry> storageByProject(@Param("workspaceId") UUID workspaceId);
}
