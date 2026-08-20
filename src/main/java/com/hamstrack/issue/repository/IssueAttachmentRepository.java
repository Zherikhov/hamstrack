package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
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
}
