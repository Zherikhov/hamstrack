package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusRepository extends JpaRepository<Status, UUID> {

    // Global catalog (both scopes NULL); kept for seeding/issue lookups by name
    List<Status> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<Status> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Status> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    // ---- exact-scope queries for the delegated catalog console ----
    // A null id param means "that scope column IS NULL"; global = (null, null).

    @Query("select s from Status s where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) order by s.position")
    List<Status> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(s) > 0 then true else false end from Status s "
            + "where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) and s.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select s from Status s where s.id = :id "
            + "and ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj)")
    Optional<Status> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** By id, visible to a scope: global ∪ that workspace ∪ that project. */
    @Query("select s from Status s where s.id = :id and ((s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj)")
    Optional<Status> findByIdVisibleTo(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** All visible to a scope (delegated console list): global ∪ workspace ∪ project. */
    @Query("select s from Status s where (s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj order by s.position")
    List<Status> findAllVisibleTo(@Param("ws") UUID ws, @Param("proj") UUID proj);

    /**
     * A name already visible to a scope (global ∪ workspace ∪ project). Used on
     * create/rename so a delegated admin reuses an inherited (e.g. system) status
     * instead of minting a scoped duplicate of the same name.
     */
    @Query("select case when count(s) > 0 then true else false end from Status s "
            + "where ((s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj) and s.name = :name")
    boolean existsVisibleToAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);
}
