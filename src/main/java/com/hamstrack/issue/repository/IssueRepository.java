package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssuePriority;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    Optional<Issue> findByProjectAndNumber(Project project, long number);

    Optional<Issue> findByIdAndProject(UUID id, Project project);

    boolean existsByStatus(Status status);

    boolean existsByType(IssueType type);

    @Query("SELECT i FROM Issue i WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priority IS NULL OR i.priority = :priority) " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findByProjectFiltered(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priority") IssuePriority priority);
}
