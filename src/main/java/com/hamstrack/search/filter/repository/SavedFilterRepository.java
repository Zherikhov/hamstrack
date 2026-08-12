package com.hamstrack.search.filter.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.search.filter.entity.SavedFilter;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-scoped persistence for {@link SavedFilter} (HD-26). Every query is bound
 * to a single {@link Workspace} — the tenant boundary. Visibility ("own + shared")
 * is applied in the query itself so a non-owner can never see another member's
 * private filter, and a foreign-workspace id resolves to empty (→ 404 in the
 * service, never a cross-tenant read).
 */
public interface SavedFilterRepository extends JpaRepository<SavedFilter, UUID> {

    /**
     * List the filters visible to {@code owner} in {@code workspace}: their own
     * filters plus every shared filter in the same workspace. Owner + workspace are
     * fetch-joined for the response mapping (ownerId/ownerName) with no lazy load.
     */
    @Query("""
            SELECT f FROM SavedFilter f
            JOIN FETCH f.owner
            WHERE f.workspace = :workspace
              AND (f.owner = :owner OR f.shared = true)
            ORDER BY f.name ASC
            """)
    List<SavedFilter> findVisible(@Param("workspace") Workspace workspace, @Param("owner") User owner);

    /** Fetch a filter by id, scoped to the workspace, with owner eagerly loaded. */
    @Query("""
            SELECT f FROM SavedFilter f
            JOIN FETCH f.owner
            WHERE f.id = :id AND f.workspace = :workspace
            """)
    Optional<SavedFilter> findByIdAndWorkspace(@Param("id") UUID id, @Param("workspace") Workspace workspace);

    /** Duplicate-name guard for create/update: unique per (workspace, owner). */
    boolean existsByWorkspaceAndOwnerAndName(Workspace workspace, User owner, String name);
}
