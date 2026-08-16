package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Label;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-scoped persistence for {@link Label} (HD-30). <strong>No method takes a
 * bare id</strong> — every lookup is bound to a {@link Workspace}, the tenant
 * boundary, so a foreign id resolves to empty (→ 404 on a direct read, 422 inside an
 * issue payload) and can never leak the existence of another tenant's vocabulary
 * (proposal §3.1).
 */
public interface LabelRepository extends JpaRepository<Label, UUID> {

    /**
     * All labels of a workspace, ordered by {@code lower(name)} (the picker/settings
     * order). {@code includeArchived = false} hides archived rows.
     */
    @Query("""
            SELECT l FROM Label l
            LEFT JOIN FETCH l.createdBy
            WHERE l.workspace = :workspace
              AND (:includeArchived = true OR l.archivedAt IS NULL)
            ORDER BY lower(l.name) ASC
            """)
    List<Label> findAllByWorkspace(@Param("workspace") Workspace workspace,
                                   @Param("includeArchived") boolean includeArchived);

    /**
     * {@code (id, name)} rows for the workspace's non-archived labels — the HQL
     * name-resolution feed ({@code ResolutionContextFactory}). Deliberately a
     * projection and NOT {@link #findAllByWorkspace}: resolution must stay unbounded
     * (any name typed in HQL has to resolve), but hydrating every label entity plus
     * its {@code createdBy} user on every {@code /search}, {@code /schema} and
     * {@code /suggest} was pure waste.
     */
    @Query("SELECT l.id, l.name FROM Label l WHERE l.workspace = :workspace AND l.archivedAt IS NULL")
    List<Object[]> findIdAndNameByWorkspace(@Param("workspace") Workspace workspace);

    /** Single label, scoped to the workspace. Empty for a foreign/unknown id. */
    Optional<Label> findByIdAndWorkspace(UUID id, Workspace workspace);

    /** Catalog size guard — {@code app.classification.max-labels-per-workspace}. */
    long countByWorkspace(Workspace workspace);

    /**
     * Resolve a batch of ids <em>within</em> one workspace (issue payloads). Ids from
     * another tenant simply don't come back, so the caller's size check turns them
     * into a 422 "Unknown label" without disclosing anything.
     */
    @Query("SELECT l FROM Label l WHERE l.workspace = :workspace AND l.id IN :ids")
    List<Label> findAllByIdInAndWorkspace(@Param("ids") Collection<UUID> ids,
                                          @Param("workspace") Workspace workspace);

    /**
     * Case-insensitive name lookup inside one workspace — backs the duplicate-name
     * 409 (+ {@code existingId}) and the concurrent-create recovery, matching the
     * {@code labels_workspace_name_uk} functional unique index.
     */
    @Query("SELECT l FROM Label l WHERE l.workspace = :workspace AND lower(l.name) = lower(:name)")
    Optional<Label> findByWorkspaceAndNameIgnoreCase(@Param("workspace") Workspace workspace,
                                                     @Param("name") String name);

    /**
     * Id-only variant of the above, keyed by workspace id — used by
     * {@code LabelConflictLookup} on a fresh transaction after the unique index has
     * arbitrated a concurrent create (the losing transaction is aborted and can no
     * longer query). Still workspace-scoped: never a name-only lookup.
     */
    @Query("SELECT l.id FROM Label l WHERE l.workspace.id = :workspaceId AND lower(l.name) = lower(:name)")
    Optional<UUID> findIdByWorkspaceIdAndNameIgnoreCase(@Param("workspaceId") UUID workspaceId,
                                                        @Param("name") String name);

    /**
     * Bounded prefix/substring typeahead for {@code /search/suggest?field=label}
     * (§3.5). Non-archived only — archived labels are excluded from name resolution.
     *
     * <p>Containment is expressed with {@code LOCATE}, NOT {@code LIKE '%'||:q||'%'}:
     * a LIKE pattern would treat {@code %} and {@code _} typed by the user as
     * wildcards (typing {@code _} matched any character, and {@code %} matched every
     * label — a correctness bug and a cheap scan amplifier). {@code LOCATE} is a plain
     * substring search, so every character is literal and no {@code ESCAPE} clause —
     * whose backslash literal is ambiguous in HQL — is needed.
     */
    @Query("""
            SELECT l FROM Label l
            WHERE l.workspace = :workspace
              AND l.archivedAt IS NULL
              AND (:q = '' OR LOCATE(:q, lower(l.name)) > 0)
            ORDER BY lower(l.name) ASC
            """)
    List<Label> suggestByWorkspace(@Param("workspace") Workspace workspace,
                                   @Param("q") String lowercaseQuery,
                                   Pageable pageable);
}
