package com.easytask.issue.repository;

import com.easytask.issue.entity.IssueHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IssueHistoryRepository extends JpaRepository<IssueHistory, UUID> {

    List<IssueHistory> findByIssue_IdOrderByCreatedAt(UUID issueId);
}
