package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    // Fetch-join the author every comment row renders (avoids 1 + N on a thread)
    @Query("SELECT c FROM IssueComment c JOIN FETCH c.author "
            + "WHERE c.issue = :issue AND c.deletedAt IS NULL ORDER BY c.createdAt ASC")
    List<IssueComment> findAllForIssueWithAuthor(Issue issue);
}
