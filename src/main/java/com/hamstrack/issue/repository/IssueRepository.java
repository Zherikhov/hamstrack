package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Component;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.entity.VersionLinkType;
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

    // ---- Components (HD-31 §5.3/§5.4) ----
    //
    // Every method takes the resolved Component entity (never a bare id) — the caller
    // has already scoped it through findByIdAndProject, so the tenant boundary is
    // carried by the argument type itself.

    /** Usage count for one component (delete-guard + {@code /usage}). */
    long countByComponent(Component component);

    /**
     * Usage counts for a batch of components in ONE grouped query —
     * {@code (componentId, count)}.
     *
     * <p><strong>Callers must chunk</strong> ({@code UsageCounts.countIn}): the
     * {@code IN} list binds one JDBC parameter per component, and PostgreSQL rejects a
     * statement above 65 535 parameters outright.
     */
    @Query("SELECT i.component.id, count(i) FROM Issue i "
            + "WHERE i.component IN :components GROUP BY i.component.id")
    List<Object[]> countsByComponents(@Param("components") Collection<Component> components);

    /**
     * Force-delete a component: null it on every issue carrying it BEFORE the row is
     * deleted (spec §5.2 trap). The FK is {@code ON DELETE SET NULL (component_id)},
     * which clears the column behind JPA's back — a managed, now-stale {@code Issue}
     * flushed later in the same transaction would write the old id back (the
     * {@code issue_seq} clobber class of bug).
     *
     * <p>{@code clearAutomatically} evicts any {@code Issue} this transaction may have
     * loaded so a later read sees the null; {@code flushAutomatically} writes pending
     * changes first so this bulk UPDATE can't discard them. Safe here because
     * {@code ComponentService.delete} has no other pending inserts — do NOT reuse this
     * shape in a method that does.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Issue i SET i.component = null WHERE i.component = :component")
    int clearComponent(@Param("component") Component component);

    // ---- Board / backlog filtering (server-side, HD-30/31/32 §3.6) ----
    //
    // componentId (HD-31) is a plain ToOne column, so it needs no sub-select — just an
    // ANDed `:componentId IS NULL OR i.component.id = :componentId`, composing with
    // statusId/assigneeId/priorityId and the label predicate below.
    //
    // fixVersionId (HD-32) IS many-valued, so it compiles to a correlated EXISTS over
    // IssueVersionLink filtered by link_type = FIX — an issue can ship in several
    // releases, and only the FIX role belongs on a "fix version" filter. The link type
    // is bound as a parameter (:fixType) rather than written as a literal so the enum
    // stays the single source of truth. A NULL fixVersionId short-circuits before the
    // sub-select is evaluated, exactly like the label predicate's :labelCount = 0.
    //
    // The label predicate is a correlated sub-select rather than a join so it can
    // express BOTH match modes with one shape:
    //     labelCount = 0            → the filter is OFF (first disjunct short-circuits)
    //     requiredMatches = 1       → "any" (OR)
    //     requiredMatches = count   → "all" (AND)
    //
    // TRAP: an EMPTY `IN` list is invalid in JPQL/Hibernate. When no labels are
    // selected the service MUST pass labelIds = List.of(new UUID(0,0)) — a sentinel
    // that matches nothing — together with labelCount = 0. Never pass an empty list.

    // NOTE: the uncapped `findByProjectFiltered` was removed in the HD-30 fix round —
    // HD-79 replaced its only caller with the capped variant below, leaving an
    // unbounded full-project fetch sitting in the API surface waiting to be re-used.
    //
    // Capped board fetch (HD-79): bounded by the Pageable (the service passes
    // size = cap+1 to detect truncation in one query). Fetch-joins the ToOne
    // associations IssueResponse.of renders so a board/backlog page is one query
    // instead of 1 + ~5N (parent is not fetched — only its id is read, which a lazy
    // proxy already carries; the shared project is the query param). ToOne fetch joins
    // → LIMIT in SQL; ordering from the Pageable is overridden below to keep the
    // board's canonical (position ASC, createdAt DESC).
    @Query("SELECT i FROM Issue i " +
           "LEFT JOIN FETCH i.type " +
           "LEFT JOIN FETCH i.status " +
           "LEFT JOIN FETCH i.priority " +
           "LEFT JOIN FETCH i.assignee " +
           "LEFT JOIN FETCH i.reporter " +
           "LEFT JOIN FETCH i.component " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType)) " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findByProjectFilteredCapped(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId,
            @Param("componentId") UUID componentId,
            @Param("labelIds") Collection<UUID> labelIds,
            @Param("labelCount") int labelCount,
            @Param("requiredMatches") long requiredMatches,
            @Param("fixVersionId") UUID fixVersionId,
            @Param("fixType") VersionLinkType fixType,
            Pageable pageable);

    // Filtered count for the board's totalAvailable (same predicate, no fetch/order).
    @Query("SELECT count(i) FROM Issue i " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType))")
    long countByProjectFiltered(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId,
            @Param("componentId") UUID componentId,
            @Param("labelIds") Collection<UUID> labelIds,
            @Param("labelCount") int labelCount,
            @Param("requiredMatches") long requiredMatches,
            @Param("fixVersionId") UUID fixVersionId,
            @Param("fixType") VersionLinkType fixType);

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
           "LEFT JOIN FETCH i.component " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType)) " +
           "AND (:excludeDone = false OR i.status.category <> :doneCategory)",
           countQuery = "SELECT count(i) FROM Issue i " +
           "WHERE i.project = :project " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType)) " +
           "AND (:excludeDone = false OR i.status.category <> :doneCategory)")
    Page<Issue> findByProjectFilteredPaged(
            @Param("project") Project project,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId,
            @Param("componentId") UUID componentId,
            @Param("labelIds") Collection<UUID> labelIds,
            @Param("labelCount") int labelCount,
            @Param("requiredMatches") long requiredMatches,
            @Param("fixVersionId") UUID fixVersionId,
            @Param("fixType") VersionLinkType fixType,
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
           "LEFT JOIN FETCH i.component " +
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

    // HQL search: resolve a `parent = "KEY-42"` operand to an issue id within the
    // workspace, scoped to the visible projects (case-insensitive project key). The
    // caller (HqlParentResolver) always passes the actor's visible project ids, so
    // this can never resolve a parent outside the tenant boundary.
    @Query("SELECT i.id FROM Issue i WHERE i.workspace.id = :wsId "
            + "AND i.project.id IN :projectIds "
            + "AND upper(i.project.key) = upper(:projectKey) AND i.number = :number")
    Optional<UUID> findIdByWorkspaceAndKey(@Param("wsId") UUID wsId,
                                           @Param("projectIds") Collection<UUID> projectIds,
                                           @Param("projectKey") String projectKey,
                                           @Param("number") long number);
}
