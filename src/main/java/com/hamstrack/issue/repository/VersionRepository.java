package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Version;
import com.hamstrack.project.entity.Project;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Project-scoped persistence for {@link Version} (HD-32). <strong>No method takes
 * a bare id</strong> — every lookup is bound to a {@link Project} the caller has
 * already resolved through the workspace→project membership chain, so a foreign id
 * resolves to empty (→ 404 on a direct read, 422 inside an issue payload) and can
 * never leak the existence of another tenant's release plan (proposal §3.1).
 *
 * <p>The two lifecycle mutators ({@link #markReleased}, {@link #markUnreleased})
 * are keyed by id, <strong>project</strong> AND the current {@code released} state.
 * Their callers already resolve the row {@code …AndProject} first, so the project
 * predicate is belt-and-braces — but "no method takes a bare id" is the invariant that
 * stops a future caller from skipping that resolution, and a bare id is exactly the
 * shape that produced this epic's earlier scoping defect.
 *
 * <p>The two HQL feeds ({@link #findIdAndNameByProjectIds},
 * {@link #suggestByProjects}) are keyed by the actor's <em>visible project ids</em>
 * — the set {@code SearchScope} computes — so name resolution can never reach
 * through a project the search scope would hide.
 */
public interface VersionRepository extends JpaRepository<Version, UUID> {

    /**
     * The project's versions in the Releases-page order (§6.1): unreleased first,
     * then by planned {@code release_date} (undated last), then by
     * {@code lower(name)}. Derived — there is deliberately no {@code position}
     * column in MVP (open question 6).
     *
     * <p>{@code released ASC} puts unreleased first because PostgreSQL orders
     * {@code false} before {@code true}.
     */
    @Query("""
            SELECT v FROM Version v
            WHERE v.project = :project
              AND (:includeArchived = true OR v.archivedAt IS NULL)
              AND (:includeReleased = true OR v.released = false)
            ORDER BY v.released ASC, v.releaseDate ASC NULLS LAST, lower(v.name) ASC
            """)
    List<Version> findAllByProject(@Param("project") Project project,
                                   @Param("includeArchived") boolean includeArchived,
                                   @Param("includeReleased") boolean includeReleased);

    /** Single version, scoped to its project. Empty for a foreign/unknown id. */
    Optional<Version> findByIdAndProject(UUID id, Project project);

    /**
     * Catalog size of one project — backs the
     * {@code app.classification.max-versions-per-project} cap (422 when full),
     * mirroring {@code ComponentRepository.countByProject}. Counts ARCHIVED and
     * RELEASED rows too: they still hold a unique name slot and are still loaded by
     * {@code findAllByProject(includeArchived = true, includeReleased = true)}, so
     * excluding them would leave the ceiling bypassable by create → archive → repeat.
     */
    long countByProject(Project project);

    /**
     * Resolve a batch of ids <em>within</em> one project (issue payloads). Ids from
     * another project or another tenant simply don't come back, so the caller's size
     * check turns them into a 422 "Unknown version" without disclosing anything.
     */
    @Query("SELECT v FROM Version v WHERE v.project = :project AND v.id IN :ids")
    List<Version> findAllByIdInAndProject(@Param("ids") Collection<UUID> ids,
                                          @Param("project") Project project);

    /**
     * Case-insensitive name lookup inside one project — backs the duplicate-name 409
     * and the demo seeder's pre-check, matching the {@code versions_project_name_uk}
     * functional unique index.
     */
    @Query("SELECT v FROM Version v WHERE v.project = :project AND lower(v.name) = lower(:name)")
    Optional<Version> findByProjectAndNameIgnoreCase(@Param("project") Project project,
                                                     @Param("name") String name);

    /**
     * <strong>Release, conditionally</strong> (§6.4 trap T1). The {@code AND
     * v.released = false} guard is the concurrency arbiter: when two users release
     * the same version, exactly one statement affects a row and the other gets
     * {@code 0}, which the service turns into a <strong>409 "already released"</strong>.
     * A read-then-write would let BOTH succeed and run a destructive
     * {@code moveUnresolvedToVersionId} twice. The added {@code v.project.id} predicate
     * does not weaken that arbiter: for a caller that resolved the row through its
     * project (all of them do), it can only ever be true, so 0 rows still means
     * "already released" and never "wrong project".
     *
     * <p>{@code clearAutomatically} is required because the caller has ALREADY
     * materialized this row (it had to, to scope it to the project and to default
     * {@code releaseDate}): a bulk UPDATE does not refresh the L1 copy, so without
     * the clear the response would echo the pre-release state and a later flush
     * could write it back. The caller re-reads immediately afterwards.
     *
     * <p>{@code flushAutomatically} is required because {@code clearAutomatically}
     * would otherwise {@code em.clear()} away any inserts still queued in the same
     * transaction — the documented "workspace vanishes" trap (CLAUDE.md). The demo
     * seeder releases a version inside the one big seeding transaction, which is
     * exactly that shape.
     *
     * <p>{@code updated_at} is refreshed by the {@code trg_versions_updated_at}
     * trigger (a bulk UPDATE bypasses {@code @LastModifiedDate}), so the re-read
     * carries the correct audit time.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Version v
               SET v.released = true, v.releasedAt = :releasedAt, v.releaseDate = :releaseDate
             WHERE v.id = :id AND v.project.id = :projectId AND v.released = false
            """)
    int markReleased(@Param("id") UUID id,
                     @Param("projectId") UUID projectId,
                     @Param("releasedAt") OffsetDateTime releasedAt,
                     @Param("releaseDate") LocalDate releaseDate);

    /**
     * <strong>Un-release, conditionally</strong> — the mirror of
     * {@link #markReleased}, and 0 rows means "wasn't released" → 409. Clears
     * {@code released}/{@code released_at} and deliberately <strong>preserves
     * {@code release_date}</strong>: it stays the plan, so the round-trip loses no
     * data (§6.1, the story's reversibility criterion).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Version v
               SET v.released = false, v.releasedAt = null
             WHERE v.id = :id AND v.project.id = :projectId AND v.released = true
            """)
    int markUnreleased(@Param("id") UUID id, @Param("projectId") UUID projectId);

    /**
     * {@code (id, name)} rows for the non-archived versions of the actor's VISIBLE
     * projects — the HQL name-resolution feed ({@code ResolutionContextFactory}).
     * A name maps to a <em>list</em> of ids on purpose: two projects may each own a
     * "2.4.0", and both must match (§3.5).
     *
     * <p>Deliberately a projection rather than {@link #findAllByProject}: resolution
     * must stay unbounded (any name typed in HQL has to resolve), but hydrating
     * every version entity on every {@code /search}, {@code /schema} and
     * {@code /suggest} would be pure waste. Never call with an empty collection —
     * an empty {@code IN} list is invalid in JPQL.
     */
    @Query("SELECT v.id, v.name FROM Version v "
            + "WHERE v.project.id IN :projectIds AND v.archivedAt IS NULL")
    List<Object[]> findIdAndNameByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Bounded prefix/substring typeahead for
     * {@code /search/suggest?field=fixVersion|affectsVersion} (§3.5), over the
     * actor's visible projects only. Non-archived only — archived versions are
     * excluded from name resolution.
     *
     * <p>Containment is expressed with {@code LOCATE}, NOT {@code LIKE '%'||:q||'%'}:
     * a LIKE pattern would treat {@code %} and {@code _} typed by the user as
     * wildcards (a correctness bug and a cheap scan amplifier). {@code LOCATE} is a
     * plain substring search, so every character is literal.
     */
    @Query("""
            SELECT v.name FROM Version v
            WHERE v.project.id IN :projectIds
              AND v.archivedAt IS NULL
              AND (:q = '' OR LOCATE(:q, lower(v.name)) > 0)
            ORDER BY lower(v.name) ASC
            """)
    List<String> suggestByProjects(@Param("projectIds") Collection<UUID> projectIds,
                                   @Param("q") String lowercaseQuery,
                                   Pageable pageable);
}
