package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Component;
import com.hamstrack.project.entity.Project;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Project-scoped persistence for {@link Component} (HD-31). <strong>No method takes
 * a bare id</strong> — every lookup is bound to a {@link Project} the caller has
 * already resolved through the workspace→project membership chain, so a foreign id
 * resolves to empty (→ 404 on a direct read, 422 inside an issue payload) and can
 * never leak the existence of another tenant's modules (proposal §3.1).
 *
 * <p>The two HQL feeds ({@link #findIdAndNameByProjectIds},
 * {@link #suggestByProjects}) are keyed by the actor's <em>visible project ids</em>
 * — the set {@code SearchScope} computes — so name resolution can never reach
 * through a project the search scope would hide.
 */
public interface ComponentRepository extends JpaRepository<Component, UUID> {

    /**
     * All components of a project, ordered by {@code lower(name)} (the picker /
     * settings order, §5.3). {@code includeArchived = false} hides archived rows.
     */
    @Query("""
            SELECT c FROM Component c
            LEFT JOIN FETCH c.lead
            WHERE c.project = :project
              AND (:includeArchived = true OR c.archivedAt IS NULL)
            ORDER BY lower(c.name) ASC
            """)
    List<Component> findAllByProject(@Param("project") Project project,
                                     @Param("includeArchived") boolean includeArchived);

    /** Single component, scoped to its project. Empty for a foreign/unknown id. */
    Optional<Component> findByIdAndProject(UUID id, Project project);

    /**
     * Catalog size of one project — backs the
     * {@code app.classification.max-components-per-project} cap (422 when full),
     * mirroring {@code LabelRepository.countByWorkspace}. Counts ARCHIVED rows too:
     * they still hold a unique name slot and are still loaded by
     * {@code findAllByProject(includeArchived = true)}, so excluding them would leave
     * the ceiling bypassable by create → archive → repeat.
     */
    long countByProject(Project project);

    /**
     * Case-insensitive name lookup inside one project — backs the duplicate-name 409
     * and the demo seeder's pre-check, matching the
     * {@code components_project_name_uk} functional unique index.
     */
    @Query("SELECT c FROM Component c WHERE c.project = :project AND lower(c.name) = lower(:name)")
    Optional<Component> findByProjectAndNameIgnoreCase(@Param("project") Project project,
                                                       @Param("name") String name);

    /**
     * {@code (id, name)} rows for the non-archived components of the actor's VISIBLE
     * projects — the HQL name-resolution feed ({@code ResolutionContextFactory}).
     * A name maps to a <em>list</em> of ids on purpose: two projects may each own a
     * "Billing", and both must match (§3.5).
     *
     * <p>Deliberately a projection rather than {@link #findAllByProject}: resolution
     * must stay unbounded (any name typed in HQL has to resolve), but hydrating every
     * component entity plus its lead on every {@code /search}, {@code /schema} and
     * {@code /suggest} would be pure waste. Never call with an empty collection — an
     * empty {@code IN} list is invalid in JPQL.
     */
    @Query("SELECT c.id, c.name FROM Component c "
            + "WHERE c.project.id IN :projectIds AND c.archivedAt IS NULL")
    List<Object[]> findIdAndNameByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Bounded prefix/substring typeahead for {@code /search/suggest?field=component}
     * (§3.5), over the actor's visible projects only. Non-archived only — archived
     * components are excluded from name resolution.
     *
     * <p>Containment is expressed with {@code LOCATE}, NOT {@code LIKE '%'||:q||'%'}:
     * a LIKE pattern would treat {@code %} and {@code _} typed by the user as
     * wildcards (a correctness bug and a cheap scan amplifier). {@code LOCATE} is a
     * plain substring search, so every character is literal.
     */
    @Query("""
            SELECT c.name FROM Component c
            WHERE c.project.id IN :projectIds
              AND c.archivedAt IS NULL
              AND (:q = '' OR LOCATE(:q, lower(c.name)) > 0)
            ORDER BY lower(c.name) ASC
            """)
    List<String> suggestByProjects(@Param("projectIds") Collection<UUID> projectIds,
                                   @Param("q") String lowercaseQuery,
                                   Pageable pageable);
}
