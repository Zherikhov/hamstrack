package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.project.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Project-scoped persistence for {@link Sprint} (HD-22). <strong>No method takes a
 * bare id</strong> — every lookup is bound to a {@link Project} the caller has already
 * resolved through the workspace→project membership chain, so a foreign id resolves to
 * empty (→ 404 on a direct read, 422 inside an issue payload) and can never leak the
 * existence of another tenant's plan (proposal §3.1).
 *
 * <p>The two lifecycle mutators ({@link #markActive}, {@link #markCompleted}) are keyed
 * by id, <strong>project</strong> AND the current state. Their callers already resolve
 * the row {@code …AndProject} first, so the project predicate is belt-and-braces — but
 * "no method takes a bare id" is the invariant that stops a future caller from skipping
 * that resolution.
 *
 * <p>The HQL feed ({@link #findIdAndNameByProjectIds}) is keyed by the actor's
 * <em>visible project ids</em> — the set {@code SearchScope} computes — so a sprint
 * name can never resolve through a project the search scope would hide.
 */
public interface SprintRepository extends JpaRepository<Sprint, UUID> {

    /** Single sprint, scoped to its project. Empty for a foreign/unknown id. */
    Optional<Sprint> findByIdAndProject(UUID id, Project project);

    /**
     * Case-insensitive name lookup inside one project — backs the duplicate-name 409
     * and the demo seeder's pre-check, matching the {@code sprints_project_name_uk}
     * functional unique index.
     */
    @Query("SELECT s FROM Sprint s WHERE s.project = :project AND lower(s.name) = lower(:name)")
    Optional<Sprint> findByProjectAndNameIgnoreCase(@Param("project") Project project,
                                                    @Param("name") String name);

    /**
     * The sprint list, ALWAYS paged (§4.4): open sprints are capped, but COMPLETED ones
     * accumulate for years.
     *
     * <p>Order: ACTIVE first, then FUTURE by {@code sequence ASC}, then COMPLETED by
     * {@code sequence DESC} (newest history first) — expressed as two CASE keys so a
     * single ORDER BY covers all three groups. The caller must pass an
     * <strong>unsorted</strong> {@link Pageable}: a Sort would be appended after this
     * ORDER BY and silently take precedence.
     *
     * <p>{@code states} is never empty — the service substitutes "all states" when the
     * repeatable {@code ?state=} filter is omitted, because an empty {@code IN} list is
     * invalid in JPQL.
     */
    @Query(value = """
            SELECT s FROM Sprint s
            WHERE s.project = :project AND s.state IN :states
            ORDER BY CASE WHEN s.state = com.hamstrack.issue.entity.SprintState.ACTIVE THEN 0
                          WHEN s.state = com.hamstrack.issue.entity.SprintState.FUTURE THEN 1
                          ELSE 2 END ASC,
                     CASE WHEN s.state = com.hamstrack.issue.entity.SprintState.COMPLETED
                          THEN (0 - s.sequence) ELSE s.sequence END ASC
            """,
            countQuery = "SELECT count(s) FROM Sprint s WHERE s.project = :project AND s.state IN :states")
    Page<Sprint> findByProjectAndStates(@Param("project") Project project,
                                        @Param("states") Collection<SprintState> states,
                                        Pageable pageable);

    /**
     * Every OPEN sprint of the project (ACTIVE first, then FUTURE by sequence) — the
     * planning view's section list and the "which sprints can still take issues?"
     * pickers. Unpaged on purpose: the set is bounded by
     * {@code app.agile.max-open-sprints-per-project}.
     */
    @Query("""
            SELECT s FROM Sprint s
            WHERE s.project = :project AND s.state <> com.hamstrack.issue.entity.SprintState.COMPLETED
            ORDER BY CASE WHEN s.state = com.hamstrack.issue.entity.SprintState.ACTIVE THEN 0
                          ELSE 1 END ASC,
                     s.sequence ASC
            """)
    List<Sprint> findOpenByProject(@Param("project") Project project);

    /** The FUTURE sprints of a project, oldest first — the completion dialog's targets. */
    @Query("SELECT s FROM Sprint s WHERE s.project = :project "
            + "AND s.state = com.hamstrack.issue.entity.SprintState.FUTURE ORDER BY s.sequence ASC")
    List<Sprint> findFutureByProject(@Param("project") Project project);

    /** The project's ACTIVE sprint, if any (at most one — {@code sprints_one_active_per_project_uk}). */
    @Query("SELECT s FROM Sprint s WHERE s.project = :project "
            + "AND s.state = com.hamstrack.issue.entity.SprintState.ACTIVE")
    Optional<Sprint> findActiveByProject(@Param("project") Project project);

    /**
     * FUTURE + ACTIVE count — backs the {@code app.agile.max-open-sprints-per-project}
     * cap (422 when full). COMPLETED sprints deliberately do NOT count: they are history
     * and can never be started again, so counting them would make the cap a lifetime
     * quota instead of a concurrency bound.
     */
    @Query("SELECT count(s) FROM Sprint s WHERE s.project = :project "
            + "AND s.state <> com.hamstrack.issue.entity.SprintState.COMPLETED")
    long countOpenByProject(@Param("project") Project project);

    /**
     * The next 1-based ordinal for a new sprint of this project. Not atomic on its own —
     * {@code sprints_project_id_sequence_key} is the arbiter, and the service retries
     * once on a lost race, then 409s.
     */
    @Query("SELECT coalesce(max(s.sequence), 0) + 1 FROM Sprint s WHERE s.project = :project")
    int nextSequence(@Param("project") Project project);

    /**
     * <strong>Start, conditionally</strong> (§4.1, the {@code VersionService.release}
     * trap-T1 pattern). The {@code AND s.state = FUTURE} guard is the concurrency
     * arbiter: starting an already-ACTIVE or COMPLETED sprint affects zero rows, which
     * the service turns into a <strong>409</strong>. A read-then-write would let a
     * double-click through twice.
     *
     * <p>Two <em>different</em> FUTURE sprints started concurrently both pass this
     * guard — the partial unique index {@code sprints_one_active_per_project_uk} then
     * arbitrates, and the loser's {@code DataIntegrityViolationException} becomes a 409
     * "Another sprint is already active".
     *
     * <p>{@code clearAutomatically} is required because the caller has ALREADY
     * materialized this row (it had to, to scope it to the project): a bulk UPDATE does
     * not refresh the L1 copy, so without the clear the response would echo the
     * pre-start state and a later flush could write it back. The caller re-reads
     * immediately afterwards. {@code flushAutomatically} pairs with it so the clear
     * cannot discard inserts queued earlier in the same transaction (the documented
     * "workspace vanishes" trap) — the demo seeder starts a sprint inside one big
     * seeding transaction, which is exactly that shape.
     *
     * <p><strong>Lifecycle columns only.</strong> {@code state} is immune to a stale
     * managed copy by mapping ({@code updatable = false}) and the two dates are written
     * here because {@code sprints_active_ck} requires a start date the moment the state
     * flips. {@code goal} is deliberately NOT in this SET clause: it is an ordinary
     * entity-writable field, and letting a bulk UPDATE write it too would leave "who
     * owns this column" resting on convention alone (migration review M-Low1). The
     * service writes it through the entity before calling this.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Sprint s
               SET s.state = com.hamstrack.issue.entity.SprintState.ACTIVE,
                   s.startAt = :startAt, s.endAt = :endAt
             WHERE s.id = :id AND s.project.id = :projectId
               AND s.state = com.hamstrack.issue.entity.SprintState.FUTURE
            """)
    int markActive(@Param("id") UUID id,
                   @Param("projectId") UUID projectId,
                   @Param("startAt") OffsetDateTime startAt,
                   @Param("endAt") OffsetDateTime endAt);

    /**
     * <strong>Complete, conditionally</strong> — the mirror of {@link #markActive}, and
     * zero rows means "not ACTIVE" → 409. Deliberately NOT idempotent: the completion
     * moves issues, and an idempotent-looking success would hide a double-click that
     * runs that destructive move twice (the {@code VersionService.release} rationale).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Sprint s
               SET s.state = com.hamstrack.issue.entity.SprintState.COMPLETED,
                   s.completedAt = :completedAt
             WHERE s.id = :id AND s.project.id = :projectId
               AND s.state = com.hamstrack.issue.entity.SprintState.ACTIVE
            """)
    int markCompleted(@Param("id") UUID id,
                      @Param("projectId") UUID projectId,
                      @Param("completedAt") OffsetDateTime completedAt);

    /**
     * <strong>Delete, conditionally</strong> — the same affected-row-count arbiter as
     * {@link #markActive}/{@link #markCompleted}, for the same reason (security review
     * L6). Two curators clicking "delete (force)" both pass the service's
     * {@code findByIdAndProject}; {@code delete(entity)} would then have the loser MERGE
     * a row that no longer exists and fail with a 500, where the SPA expects a 404.
     * Zero rows ⇒ somebody else already deleted it ⇒ {@code SprintNotFoundException}.
     *
     * <p>The {@code project.id} predicate keeps the "no method takes a bare id"
     * invariant: even called directly, this cannot delete another tenant's sprint.
     *
     * <p>{@code flushAutomatically} orders the {@code issue_history} rows the
     * force-delete path queues just before it ahead of the DELETE; no
     * {@code clearAutomatically} — nothing is re-read afterwards, and clearing would
     * evict those same pending inserts.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Sprint s WHERE s.id = :id AND s.project.id = :projectId")
    int deleteByIdAndProject(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /**
     * {@code (id, name)} rows for the OPEN sprints of the actor's VISIBLE projects — the
     * HQL name-resolution feed ({@code ResolutionContextFactory}). A name maps to a
     * <em>list</em> of ids on purpose: two projects may each run a "Sprint 7", and both
     * must match (§4.7).
     *
     * <p>COMPLETED sprints are excluded from NAME resolution (they would flood the
     * namespace with years of history and a name that resolves to 40 ids is useless);
     * issues still carrying one match by id. Never call with an empty collection — an
     * empty {@code IN} list is invalid in JPQL.
     */
    @Query("SELECT s.id, s.name FROM Sprint s WHERE s.project.id IN :projectIds "
            + "AND s.state <> com.hamstrack.issue.entity.SprintState.COMPLETED")
    List<Object[]> findIdAndNameByProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}
