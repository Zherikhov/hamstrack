package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueAttachmentRepository extends JpaRepository<IssueAttachment, UUID> {

    List<IssueAttachment> findAllByIssueOrderByCreatedAtAsc(Issue issue);

    // Tenant-scoped lookup for upload compensation: the attachment must belong to
    // the resolved issue, so a compensating delete can never touch another
    // issue's/tenant's row (never a global deleteById).
    Optional<IssueAttachment> findByIdAndIssue(UUID id, Issue issue);
}
