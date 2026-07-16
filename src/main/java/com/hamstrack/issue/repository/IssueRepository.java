package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    boolean existsByPriority(Priority priority);

    long countByStatus(Status status);

    long countByType(IssueType type);

    long countByPriority(Priority priority);

    // Bulk remaps for admin delete-with-remap; clearAutomatically so stale
    // entities don't linger in the persistence context (see CLAUDE.md gotchas)
    @Modifying(clearAutomatically = true)
    @Query("update Issue i set i.status = :to where i.status = :from")
    int remapStatus(@Param("from") Status from, @Param("to") Status to);

    @Modifying(clearAutomatically = true)
    @Query("update Issue i set i.priority = :to where i.priority = :from")
    int remapPriority(@Param("from") Priority from, @Param("to") Priority to);

    @Modifying(clearAutomatically = true)
    @Query("update Issue i set i.type = :to where i.type = :from")
    int remapType(@Param("from") IssueType from, @Param("to") IssueType to);

    // Integrity guards for admin workflow edits: issues sitting in a status a
    // change would orphan (board renders only workflow statuses)
    @Query("select count(i) from Issue i where i.project = :project and i.status not in :statuses")
    long countByProjectAndStatusNotIn(@Param("project") Project project,
                                      @Param("statuses") java.util.Collection<Status> statuses);

    @Query("select count(i) from Issue i where i.status = :status and "
            + "(i.project.workflow = :workflow or (:isDefault = true and i.project.workflow is null))")
    long countByStatusInWorkflowProjects(@Param("status") Status status,
                                         @Param("workflow") com.hamstrack.issue.entity.Workflow workflow,
                                         @Param("isDefault") boolean isDefault);

    @Query("SELECT i FROM Issue i WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findByProjectFiltered(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId);
}
