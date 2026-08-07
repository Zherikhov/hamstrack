package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    // Fetch-join the author every comment row renders (avoids 1 + N on a thread);
    // paged (author is ToOne, so LIMIT/OFFSET is applied in SQL, not in memory).
    // Ordering comes from the Pageable's Sort.
    @Query(value = "SELECT c FROM IssueComment c JOIN FETCH c.author "
            + "WHERE c.issue = :issue AND c.deletedAt IS NULL",
            countQuery = "SELECT count(c) FROM IssueComment c "
            + "WHERE c.issue = :issue AND c.deletedAt IS NULL")
    Page<IssueComment> findForIssueWithAuthor(Issue issue, Pageable pageable);
}
