package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IssueHistoryRepository extends JpaRepository<IssueHistory, UUID> {
    // Ordering comes from the Pageable's Sort
    Page<IssueHistory> findByIssue(Issue issue, Pageable pageable);
}