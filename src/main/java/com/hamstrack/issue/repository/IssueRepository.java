package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.project.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    Optional<Issue> findByProjectAndNumber(Project project, long number);

    Optional<Issue> findByIdAndProject(UUID id, Project project);

    long countByProject(Project project);

    boolean existsByStatus(Status status);

    boolean existsByType(IssueType type);

    boolean existsByPriority(Priority priority);

    long countByStatus(Status status);

    long countByType(IssueType type);

    long countByPriority(Priority priority);

    // Scope-filtered issue counts for admin usage display. wsId/projectId = null
    // → whole-install (global/system-admin console); a workspace or project id →
    // a delegated console, so a tenant's usage figure never spans other tenants.
    @Query("select count(i) from Issue i where i.status = :status "
            + "and (:wsId is null or i.workspace.id = :wsId) "
            + "and (:projectId is null or i.project.id = :projectId)")
    long countByStatusScoped(@Param("status") Status status,
                             @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select count(i) from Issue i where i.type = :type "
            + "and (:wsId is null or i.workspace.id = :wsId) "
            + "and (:projectId is null or i.project.id = :projectId)")
    long countByTypeScoped(@Param("type") IssueType type,
                           @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    @Query("select count(i) from Issue i where i.priority = :priority "
            + "and (:wsId is null or i.workspace.id = :wsId) "
            + "and (:projectId is null or i.project.id = :projectId)")
    long countByPriorityScoped(@Param("priority") Priority priority,
                               @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

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

    // Fetch-join the ToOne associations IssueResponse.of renders so a board/backlog
    // page is one query instead of 1 + ~5N (parent is not fetched — only its id is
    // read, which a lazy proxy already carries; the shared project is the query param)
    @Query("SELECT i FROM Issue i " +
           "LEFT JOIN FETCH i.type " +
           "LEFT JOIN FETCH i.status " +
           "LEFT JOIN FETCH i.priority " +
           "LEFT JOIN FETCH i.assignee " +
           "LEFT JOIN FETCH i.reporter " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findByProjectFiltered(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId);

    // Paged variant for the backlog (the board uses the full-list method above).
    // excludeDone applies the backlog's "not in a DONE-category status" filter
    // server-side so page counts are correct. ToOne fetch joins → LIMIT/OFFSET in
    // SQL; ordering comes from the Pageable's Sort.
    @Query(value = "SELECT i FROM Issue i " +
           "LEFT JOIN FETCH i.type " +
           "LEFT JOIN FETCH i.status " +
           "LEFT JOIN FETCH i.priority " +
           "LEFT JOIN FETCH i.assignee " +
           "LEFT JOIN FETCH i.reporter " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:excludeDone = false OR i.status.category <> :doneCategory)",
           countQuery = "SELECT count(i) FROM Issue i " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:excludeDone = false OR i.status.category <> :doneCategory)")
    Page<Issue> findByProjectFilteredPaged(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId,
            @Param("excludeDone") boolean excludeDone,
            @Param("doneCategory") StatusCategory doneCategory,
            Pageable pageable);

    // ---- Issue hierarchy (see issue-hierarchy-proposal §6.3) ----

    // Direct children, fetch-joined like the board list to avoid N+1
    @Query("SELECT i FROM Issue i " +
           "LEFT JOIN FETCH i.type " +
           "LEFT JOIN FETCH i.status " +
           "LEFT JOIN FETCH i.priority " +
           "LEFT JOIN FETCH i.assignee " +
           "LEFT JOIN FETCH i.reporter " +
           "WHERE i.parent = :parent " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findByParent(@Param("parent") Issue parent);

    // Direct children of a single parent — cheap counts for a single-issue GET.
    long countByParent(Issue parent);

    @Query("SELECT count(i) FROM Issue i WHERE i.parent = :parent AND i.status.category = :category")
    long countByParentAndStatusCategory(@Param("parent") Issue parent,
                                        @Param("category") StatusCategory category);

    // Roll-up counts for a set of parent ids in one grouped query (avoids N+1 on
    // the board). Rows: (parentId, total, doneCount). Keyed by id so callers only
    // need the distinct parent ids the lazy proxies already carry.
    @Query("SELECT i.parent.id, count(i), " +
           "sum(case when i.status.category = :done then 1 else 0 end) " +
           "FROM Issue i WHERE i.parent.id IN :parentIds GROUP BY i.parent.id")
    List<Object[]> rollupByParentIds(@Param("parentIds") Collection<UUID> parentIds,
                                     @Param("done") StatusCategory done);

    // Parent display summaries for a batch of parent ids: (id, project.key, number,
    // title, type.id) → IssueResponse.parentKey/parentTitle/parentTypeId without a
    // Cartesian fetch-join on the hot board query.
    @Query("SELECT i.id, i.project.key, i.number, i.title, i.type.id " +
           "FROM Issue i WHERE i.id IN :ids")
    List<Object[]> parentSummaries(@Param("ids") Collection<UUID> ids);
}
