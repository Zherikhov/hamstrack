package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IssueAttachmentRepository extends JpaRepository<IssueAttachment, UUID> {

    List<IssueAttachment> findAllByIssueOrderByCreatedAtAsc(Issue issue);
}
