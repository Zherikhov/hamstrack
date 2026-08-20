package com.hamstrack.issue.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Component;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
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

    /**
     * Resolve a batch of issue ids <em>within</em> one project (bulk sprint moves). Ids
     * from another project or another tenant simply don't come back, so the caller's
     * size check turns them into a 422 without disclosing anything.
     */
    @Query("SELECT i FROM Issue i WHERE i.project = :project AND i.id IN :ids")
    List<Issue> findAllByIdInAndProject(@Param("ids") Collection<UUID> ids,
                                        @Param("project") Project project);

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

    // ---- Workspace member removal (HD-132) ----

    /**
     * The {@code (id, number)} pairs of every issue in ONE workspace currently assigned to
     * one user — read <em>before</em> {@link #unassignAllInWorkspace} so the removal can
     * write an {@code assignee} history row per issue.
     *
     * <p>A scalar projection for exactly the reason
     * {@link #findUnfinishedRefsBySprint} is one: materializing the entities and then
     * running the bulk UPDATE below would leave stale managed copies whose later flush
     * writes the departed assignee back (the documented "bulk JPQL UPDATE desyncs
     * already-loaded entities" trap). Nothing enters the L1 cache, so the bulk update is
     * safe and the history rows can be built against {@code getReferenceById} proxies.
     *
     * <p><strong>Scoped by workspace, not by user alone.</strong> A {@code User} is global
     * — the same account is a member of several workspaces — so an unscoped
     * {@code WHERE assignee = :user} would unassign a departing member's work in every
     * tenant they belong to. That is the project's top bug class wearing a very ordinary
     * disguise. {@code idx_issues_assignee} serves the predicate.
     */
    @Query("SELECT i.id, i.number FROM Issue i "
            + "WHERE i.workspace = :workspace AND i.assignee = :user ORDER BY i.number ASC")
    List<Object[]> findAssignedRefsInWorkspace(@Param("workspace") Workspace workspace,
                                               @Param("user") User user);

    /**
     * Unassign every issue of ONE workspace held by a member who is being removed (HD-132).
     * An assignee is a statement about <em>current</em> responsibility, so leaving it
     * pointing at someone who no longer has access is how work goes missing.
     *
     * <p>Bulk rather than N entity saves: a large workspace can have thousands of issues on
     * one assignee, and the row set is never materialized (see
     * {@link #findAssignedRefsInWorkspace}).
     *
     * <p>Plain {@code @Modifying}, deliberately. There are no managed {@code Issue} copies
     * to clear (the caller only read scalar refs), and {@code clearAutomatically} here would
     * risk discarding the rest of the removal transaction's pending writes for no benefit —
     * the {@code WorkspaceService.create} trap.
     *
     * <p><strong>{@code UPDATE VERSIONED} is load-bearing — do not "simplify" it away.</strong>
     * A bulk UPDATE normally leaves {@code @Version} alone, and that is what
     * {@link #moveUnfinishedOutOfSprint} does. Here it would be a silent-revert bug, because
     * {@code Issue} carries no {@code @DynamicUpdate} (the annotation appears nowhere in this
     * codebase): every flush writes <em>every</em> column from the snapshot Hibernate loaded
     * when that transaction first read the row — {@code assignee_id} included, whether or not
     * the editor touched it. So an editor who opened the issue <em>before</em> the removal and
     * then saves an unrelated title change would write the departed assignee straight back,
     * and — with the version left untouched — their optimistic-lock check would still match,
     * so nothing would notice. {@code issue_history} would say the member was unassigned while
     * the row said otherwise. {@code VERSIONED} increments {@code version}, so that edit is
     * rejected and the user re-reads reality instead of overwriting it.
     *
     * <p><strong>Which 409 they get depends on the timing, and both exist.</strong> If the
     * removal committed before the editor's PATCH started, {@code IssueService.update}'s
     * pre-check sees the stale {@code version} in the body and answers 409 itself. If it
     * commits <em>after</em> that read — or the client sent no {@code version} at all, so the
     * pre-check never ran — the conflict surfaces at flush as
     * {@code ObjectOptimisticLockingFailureException}, which
     * {@code GlobalExceptionHandler.handleOptimisticLock} maps to the same 409. Before that
     * handler existed the second case was a 500, i.e. exactly the window this method opens
     * was the one the pre-check could not cover.
     *
     * <p>The trade-off is deliberate and asymmetric with the sprint carry-over: invalidating
     * in-flight edits is a real cost, and it is worth paying only where the bulk write and a
     * concurrent edit contend over the <em>same column</em>. A sprint move does not touch
     * {@code assignee_id}; this does.
     *
     * <p>{@code updated_at} is stamped by the {@code set_updated_at()} trigger, which is
     * correct — losing your assignee is a real change to the issue, not a re-spacing, so the
     * rank rebalance's {@code skip_updated_at} opt-out must NOT be used here.
     */
    @Modifying
    @Query("UPDATE VERSIONED Issue i SET i.assignee = null "
            + "WHERE i.workspace = :workspace AND i.assignee = :user")
    int unassignAllInWorkspace(@Param("workspace") Workspace workspace,
                               @Param("user") User user);

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
           "AND (:sprintId IS NULL OR i.sprint.id = :sprintId) " +
           "AND (:noSprint = false OR i.sprint IS NULL) " +
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
            @Param("sprintId") UUID sprintId,
            @Param("noSprint") boolean noSprint,
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
           "                           AND vl.linkType = :fixType)) " +
           "AND (:sprintId IS NULL OR i.sprint.id = :sprintId) " +
           "AND (:noSprint = false OR i.sprint IS NULL)")
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
            @Param("fixType") VersionLinkType fixType,
            @Param("sprintId") UUID sprintId,
            @Param("noSprint") boolean noSprint);

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
           "AND (:sprintId IS NULL OR i.sprint.id = :sprintId) " +
           "AND (:noSprint = false OR i.sprint IS NULL) " +
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
           "AND (:sprintId IS NULL OR i.sprint.id = :sprintId) " +
           "AND (:noSprint = false OR i.sprint IS NULL) " +
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
            @Param("sprintId") UUID sprintId,
            @Param("noSprint") boolean noSprint,
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

    // ---- Sprints & backlog rank (HD-22, agile-sprints-proposal §4.6) ----
    //
    // A "section" is (project, sprint) where a NULL sprint IS the backlog. Sections
    // share ONE rank space (issues.position): sprint_id is a FILTER, not a separate
    // order, so ranks interleave across sections on purpose — moving an issue out of a
    // sprint keeps its relative place in the backlog.
    //
    // Every method takes the resolved Project/Sprint entity (never a bare id), so the
    // tenant boundary is carried by the argument type itself.

    /**
     * The project's highest rank — {@code IssueService.create} appends at
     * {@code maxPosition + RANK_STEP} so a newly filed issue lands at the BOTTOM of
     * the backlog (filing an issue is not a priority statement). Served by
     * {@code idx_issues_project_position}. Returns 0 for an empty project.
     */
    @Query("SELECT coalesce(max(i.position), 0) FROM Issue i WHERE i.project = :project")
    long maxPosition(@Param("project") Project project);

    /**
     * Highest / lowest rank inside ONE section — the append target of a bulk
     * "move to sprint (BOTTOM / TOP)". A NULL {@code sprintId} means the backlog
     * section; the predicate spells that out rather than relying on
     * {@code = NULL} never matching.
     */
    @Query("SELECT coalesce(max(i.position), 0) FROM Issue i WHERE i.project = :project "
            + "AND ((:sprintId IS NULL AND i.sprint IS NULL) OR i.sprint.id = :sprintId)")
    long maxPositionInSection(@Param("project") Project project, @Param("sprintId") UUID sprintId);

    @Query("SELECT coalesce(min(i.position), 0) FROM Issue i WHERE i.project = :project "
            + "AND ((:sprintId IS NULL AND i.sprint IS NULL) OR i.sprint.id = :sprintId)")
    long minPositionInSection(@Param("project") Project project, @Param("sprintId") UUID sprintId);

    /**
     * The row immediately BELOW a given rank inside one section (smallest position
     * strictly greater), excluding the issue being moved — the missing "before"
     * neighbour when a drag only supplied {@code afterIssueId}. Index-served by
     * {@code idx_issues_sprint} / {@code idx_issues_project_position}; the caller
     * passes a {@code PageRequest.of(0, 1)}.
     */
    @Query("SELECT i FROM Issue i WHERE i.project = :project "
            + "AND ((:sprintId IS NULL AND i.sprint IS NULL) OR i.sprint.id = :sprintId) "
            + "AND i.position > :position AND i.id <> :excludeId "
            + "ORDER BY i.position ASC")
    List<Issue> findNextInSection(@Param("project") Project project,
                                  @Param("sprintId") UUID sprintId,
                                  @Param("position") long position,
                                  @Param("excludeId") UUID excludeId,
                                  Pageable pageable);

    /** The row immediately ABOVE a given rank inside one section — the mirror of {@link #findNextInSection}. */
    @Query("SELECT i FROM Issue i WHERE i.project = :project "
            + "AND ((:sprintId IS NULL AND i.sprint IS NULL) OR i.sprint.id = :sprintId) "
            + "AND i.position < :position AND i.id <> :excludeId "
            + "ORDER BY i.position DESC")
    List<Issue> findPreviousInSection(@Param("project") Project project,
                                      @Param("sprintId") UUID sprintId,
                                      @Param("position") long position,
                                      @Param("excludeId") UUID excludeId,
                                      Pageable pageable);

    /**
     * One planning section's rows, capped by the {@link Pageable} (the service passes
     * {@code cap + 1} to detect truncation in one query) and fetch-joining exactly the
     * ToOne block the board list already does, so a section stays ONE query instead of
     * 1 + ~6N. Ordering is the canonical rank order and is NOT taken from the Pageable.
     *
     * <p>{@code excludeDone} is the backlog section's "hide done work" switch; sprint
     * sections always pass {@code false} — a sprint's DONE issues are its record of
     * what it delivered.
     */
    @Query("SELECT i FROM Issue i " +
           "LEFT JOIN FETCH i.type " +
           "LEFT JOIN FETCH i.status " +
           "LEFT JOIN FETCH i.priority " +
           "LEFT JOIN FETCH i.assignee " +
           "LEFT JOIN FETCH i.reporter " +
           "LEFT JOIN FETCH i.component " +
           "LEFT JOIN FETCH i.sprint " +
           "WHERE i.project = :project " +
           "AND ((:sprintId IS NULL AND i.sprint IS NULL) OR i.sprint.id = :sprintId) " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType)) " +
           "AND (:excludeDone = false OR i.status.category <> :doneCategory) " +
           "ORDER BY i.position ASC, i.createdAt DESC")
    List<Issue> findSectionIssues(
            @Param("project") Project project,
            @Param("sprintId") UUID sprintId,
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

    /**
     * <strong>ONE grouped stats query for the ENTIRE planning view</strong> (§4.6) —
     * never one per section. Grouped by {@code i.sprint.id}, where the NULL group IS
     * the backlog, and carrying the same filter predicate the sections do.
     *
     * <p>Rows: {@code (sprintId, count, doneCount, points, donePoints, unestimated,
     * unestimatedDone)}. Both the "done" and the "done-only" aggregates come back so
     * the backlog section can derive its {@code includeDone = false} numbers by
     * subtraction instead of paying a second query — the totals stay honest even when
     * the section is truncated, because this query never sees the cap.
     *
     * <p>{@code sprintIds} must be non-empty (an empty {@code IN} list is invalid in
     * JPQL); the caller substitutes a sentinel UUID no row can carry, which leaves only
     * the NULL/backlog group.
     */
    @Query("SELECT i.sprint.id, count(i), " +
           "  sum(case when i.status.category = :done then 1 else 0 end), " +
           "  sum(i.storyPoints), " +
           "  sum(case when i.status.category = :done then i.storyPoints end), " +
           "  sum(case when i.storyPoints is null then 1 else 0 end), " +
           "  sum(case when i.storyPoints is null and i.status.category = :done then 1 else 0 end) " +
           "FROM Issue i " +
           "WHERE i.project = :project " +
           "AND (i.sprint IS NULL OR i.sprint.id IN :sprintIds) " +
           "AND (:statusId IS NULL OR i.status.id = :statusId) " +
           "AND (:assigneeId IS NULL OR i.assignee.id = :assigneeId) " +
           "AND (:priorityId IS NULL OR i.priority.id = :priorityId) " +
           "AND (:componentId IS NULL OR i.component.id = :componentId) " +
           "AND (:labelCount = 0 OR (SELECT count(DISTINCT il.label.id) FROM IssueLabel il " +
           "                         WHERE il.issue = i AND il.label.id IN :labelIds) >= :requiredMatches) " +
           "AND (:fixVersionId IS NULL OR EXISTS (SELECT 1 FROM IssueVersionLink vl " +
           "                         WHERE vl.issue = i AND vl.version.id = :fixVersionId " +
           "                           AND vl.linkType = :fixType)) " +
           "GROUP BY i.sprint.id")
    List<Object[]> planningStats(
            @Param("project") Project project,
            @Param("sprintIds") Collection<UUID> sprintIds,
            @Param("statusId") UUID statusId,
            @Param("assigneeId") UUID assigneeId,
            @Param("priorityId") UUID priorityId,
            @Param("componentId") UUID componentId,
            @Param("labelIds") Collection<UUID> labelIds,
            @Param("labelCount") int labelCount,
            @Param("requiredMatches") long requiredMatches,
            @Param("fixVersionId") UUID fixVersionId,
            @Param("fixType") VersionLinkType fixType,
            @Param("done") StatusCategory done);

    /**
     * Unfiltered per-sprint roll-up for a batch of sprints in ONE grouped query — the
     * {@code GET /sprints} page's counters and the completion preview (the
     * {@code VersionService.progress} pattern). Same row shape as
     * {@link #planningStats}.
     *
     * <p><strong>Callers must chunk</strong> ({@code UsageCounts.rowsIn}): the
     * {@code IN} list binds one JDBC parameter per sprint, and PostgreSQL rejects a
     * statement above 65 535 parameters outright.
     */
    @Query("SELECT i.sprint.id, count(i), " +
           "  sum(case when i.status.category = :done then 1 else 0 end), " +
           "  sum(i.storyPoints), " +
           "  sum(case when i.status.category = :done then i.storyPoints end), " +
           "  sum(case when i.storyPoints is null then 1 else 0 end), " +
           "  sum(case when i.storyPoints is null and i.status.category = :done then 1 else 0 end) " +
           "FROM Issue i WHERE i.sprint IN :sprints GROUP BY i.sprint.id")
    List<Object[]> statsBySprints(@Param("sprints") Collection<Sprint> sprints,
                                  @Param("done") StatusCategory done);

    /** Issues currently in a sprint — the delete guard ("still holds issues" → 409 without force). */
    long countBySprint(Sprint sprint);

    /**
     * The {@code (id, number, storyPoints)} rows of every issue currently in a sprint,
     * in rank order — the membership a {@code start} COMMITS (HD-137).
     *
     * <p>The only remaining pure-read write-list: the two bulk doors now take theirs from
     * their own {@code UPDATE … RETURNING} (see below), because a separate SELECT is a
     * different snapshot. A start needs no such care — it runs after {@code markActive}
     * has already arbitrated, and an issue added a microsecond later is an in-sprint
     * scope change with its own ledger row, not a missing part of the commitment.
     *
     * <p>A scalar projection, deliberately: nothing must put a {@code Sprint} or an
     * {@code Issue} back into a persistence context that {@code markActive}'s
     * {@code clearAutomatically} has just cleared.
     */
    @Query("SELECT i.id, i.number, i.storyPoints FROM Issue i WHERE i.sprint = :sprint "
            + "ORDER BY i.position ASC")
    List<Object[]> findRefsBySprint(@Param("sprint") Sprint sprint);

    /**
     * Move every UNFINISHED issue of a completing sprint to {@code targetId}
     * ({@code null} = the backlog) <strong>and return the rows it actually moved</strong>
     * — {@code (id, number, storyPoints)}, in rank order. DONE issues deliberately keep
     * their {@code sprint_id} (that is the sprint's record of what it delivered) and the
     * rank is NOT rewritten, so carried-over items keep their relative order (§4.5).
     *
     * <p><strong>The write-list comes out of the UPDATE, not out of a SELECT before
     * it</strong> (HD-137 review R2-5). The two used to be separate statements, and under
     * READ COMMITTED they are two different snapshots: a concurrent
     * {@code POST /sprints/{id}/issues} landing between them either hides a real
     * membership change from {@code issue_history} and the scope ledger — an
     * {@code ADDED} with no matching {@code REMOVED}, so the issue stays in the completed
     * sprint's scope forever while its {@code sprint_id} points somewhere else — or
     * invents a {@code REMOVED} for an issue that was never moved and double-decrements
     * the scope line. Both records would agree with each other and both be wrong, which
     * is the failure mode with no symptom. {@code UPDATE … RETURNING} is the same
     * technique {@link com.hamstrack.project.repository.ProjectRepository#incrementAndGetIssueSeq}
     * uses for the same class of reason.
     *
     * <p>Wrapped in a data-modifying CTE purely so the rows can come back {@code ORDER BY
     * position}: bare {@code RETURNING} has no defined order, and the caller's SSE
     * fan-out and history rows were ordered by rank before this change.
     *
     * <p>Native, and therefore NOT {@code @Modifying}: Spring Data's {@code @Modifying}
     * only accepts a row count, and the rows are the point. Hibernate auto-flushes before
     * a native query (it cannot narrow the query spaces, so it flushes everything), which
     * is the {@code flushAutomatically} half of what the old JPQL statement carried. The
     * {@code clearAutomatically} half is deliberately not replaced: it exists to evict
     * stale managed {@code Issue} copies, and both callers read nothing but scalars
     * precisely so that no such copy exists.
     *
     * <p>{@code CAST(:targetId AS uuid)} is required, not decorative: a NULL bind on an
     * untyped native parameter reaches PostgreSQL as {@code bytea} and the statement
     * fails with "column sprint_id is of type uuid".
     */
    @Query(value = """
            WITH moved AS (
                UPDATE issues
                   SET sprint_id = CAST(:targetId AS uuid)
                 WHERE sprint_id = CAST(:sprintId AS uuid)
                   AND EXISTS (SELECT 1 FROM statuses s
                                WHERE s.id = issues.status_id AND s.category <> :done)
             RETURNING id, number, story_points, position
            )
            SELECT id, number, story_points FROM moved ORDER BY position ASC
            """, nativeQuery = true)
    List<Object[]> moveUnfinishedOutOfSprint(@Param("sprintId") UUID sprintId,
                                             @Param("targetId") UUID targetId,
                                             @Param("done") String done);

    /**
     * Detach every issue from a sprint that is about to be deleted, and return the rows
     * it detached — {@code (id, number, storyPoints)}, in rank order (§4.3 trap).
     *
     * <p>The FK is {@code ON DELETE SET NULL (sprint_id)}, which clears the column behind
     * JPA's back — a managed, now-stale {@code Issue} flushed later in the same
     * transaction would write the old id back (the {@code issue_seq}-clobber class of
     * bug), so the detach is done explicitly and first.
     *
     * <p>{@code RETURNING} for the same reason as
     * {@link #moveUnfinishedOutOfSprint(UUID, UUID, String)}: the audit rows and the scope
     * ledger must describe the issues this statement moved, not the ones a SELECT saw a
     * moment earlier. See there for the CTE, the cast and why this is not
     * {@code @Modifying}.
     */
    @Query(value = """
            WITH detached AS (
                UPDATE issues
                   SET sprint_id = NULL
                 WHERE sprint_id = CAST(:sprintId AS uuid)
             RETURNING id, number, story_points, position
            )
            SELECT id, number, story_points FROM detached ORDER BY position ASC
            """, nativeQuery = true)
    List<Object[]> clearSprint(@Param("sprintId") UUID sprintId);

    /**
     * Opt this transaction OUT of the {@code set_updated_at()} trigger (V11 §3.3.4)
     * before a bulk rank rewrite: a re-spacing is not an edit, and stamping every issue
     * in the project as "just updated" would poison Home / My work / {@code ORDER BY
     * updated}.
     *
     * <p>{@code set_config(..., is_local = true)} IS {@code SET LOCAL}: it is reverted
     * at COMMIT/ROLLBACK and therefore cannot leak back into the pooled connection. The
     * name and the value are compile-time constants — no user input reaches the GUC.
     *
     * <p><strong>Transaction-wide, not statement-wide.</strong> The guard stays on until
     * the transaction ends, so any bulk/native UPDATE that ran after it would silently
     * lose its {@code updated_at} stamp too. Callers must therefore pair it with
     * {@link #restoreUpdatedAtForThisTransaction()} immediately after the rewrite it
     * covers, and must run inside a transaction — a plain {@code SELECT} forces none of
     * its own, and on a non-transactional (or auto-commit) path {@code SET LOCAL}
     * degrades to a no-op with no error. {@code IssueRankService.resolve} is
     * {@code Propagation.MANDATORY} for exactly that reason.
     */
    @Query(value = "SELECT set_config('hamstrack.skip_updated_at', 'on', true)", nativeQuery = true)
    String suppressUpdatedAtForThisTransaction();

    /**
     * Turn the {@code set_updated_at()} guard back ON for the rest of this transaction —
     * the mandatory counterpart of {@link #suppressUpdatedAtForThisTransaction()}.
     *
     * <p>Without it the opt-out would live until COMMIT, so a bulk or native UPDATE
     * added later in the same transaction (a future feature, not today's code) would
     * silently stop stamping {@code updated_at}. Scoping the suppression to the one
     * statement that needs it keeps that from ever becoming a debugging session.
     */
    @Query(value = "SELECT set_config('hamstrack.skip_updated_at', 'off', true)", nativeQuery = true)
    String restoreUpdatedAtForThisTransaction();

    /**
     * Whole-project rank rebalance (§3.3.4) — run when a drag's gap is exhausted
     * ({@code before.position - after.position <= 1}, i.e. ~26 successive midpoints
     * into the SAME gap). Renumbers every issue of the project in ONE native statement
     * at {@code row_number() * RANK_STEP}, preserving the existing order exactly.
     *
     * <p><strong>Native and deliberately outside JPA:</strong> it must not touch
     * {@code version} (so it cannot invalidate anybody's optimistic lock on an unrelated
     * edit) and must not touch {@code updated_at} (hence the
     * {@link #suppressUpdatedAtForThisTransaction()} call that has to precede it in the
     * same transaction).
     *
     * <p>{@code clearAutomatically} is required — the caller has already materialized
     * the moved issue and its anchors, and their positions are now stale; it re-reads
     * them immediately afterwards and retries the midpoint exactly once.
     * {@code flushAutomatically} pairs with it so the clear cannot discard pending
     * writes. This is why the rebalance runs BEFORE any entity mutation in the rank
     * transaction (the documented "clearAutomatically wipes pending inserts" trap).
     *
     * <p>Cost: {@code O(issues in project)} in one statement — milliseconds at our scale
     * and rare. Past ~200k issues in one project this should become a
     * neighbourhood-scoped renumber; recorded, not built. {@code IssueRankService}
     * throttles it per project so a member cannot drive one rewrite per handful of
     * cheap requests.
     *
     * <p><strong>Tenancy:</strong> the outer {@code UPDATE} repeats
     * {@code project_id = :projectId} even though the join to {@code ranked} already
     * implies it. It is the one statement in this feature where the tenant boundary
     * would otherwise be inherited rather than stated, and an explicit predicate cannot
     * be lost to a future edit of the subquery.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE issues SET position = ranked.rn * :step
              FROM (SELECT id, row_number() OVER (ORDER BY position ASC, created_at DESC) AS rn
                      FROM issues WHERE project_id = :projectId) ranked
             WHERE issues.id = ranked.id AND issues.project_id = :projectId
            """, nativeQuery = true)
    int rebalancePositions(@Param("projectId") UUID projectId, @Param("step") long step);
}
