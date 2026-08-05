package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Priority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriorityRepository extends JpaRepository<Priority, UUID> {

    List<Priority> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<Priority> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Priority> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    // ---- exact-scope queries for the delegated catalog console ----

    @Query("select p from Priority p where ((:ws is null and p.scopeWorkspaceId is null) or p.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and p.scopeProjectId is null) or p.scopeProjectId = :proj) order by p.position")
    List<Priority> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(p) > 0 then true else false end from Priority p "
            + "where ((:ws is null and p.scopeWorkspaceId is null) or p.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and p.scopeProjectId is null) or p.scopeProjectId = :proj) and p.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select p from Priority p where p.id = :id "
            + "and ((:ws is null and p.scopeWorkspaceId is null) or p.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and p.scopeProjectId is null) or p.scopeProjectId = :proj)")
    Optional<Priority> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** By id, visible to a scope: global ∪ that workspace ∪ that project. */
    @Query("select p from Priority p where p.id = :id and ((p.scopeWorkspaceId is null and p.scopeProjectId is null) "
            + "or p.scopeWorkspaceId = :ws or p.scopeProjectId = :proj)")
    Optional<Priority> findByIdVisibleTo(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** All visible to a scope (delegated console list): global ∪ workspace ∪ project. */
    @Query("select p from Priority p where (p.scopeWorkspaceId is null and p.scopeProjectId is null) "
            + "or p.scopeWorkspaceId = :ws or p.scopeProjectId = :proj order by p.position")
    List<Priority> findAllVisibleTo(@Param("ws") UUID ws, @Param("proj") UUID proj);
}
