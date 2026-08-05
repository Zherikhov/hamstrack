package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.PrioritySet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrioritySetRepository extends JpaRepository<PrioritySet, UUID> {

    List<PrioritySet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<PrioritySet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<PrioritySet> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    /** See {@code WorkflowRepository#findAllBindableForProject}. */
    @Query("select s from PrioritySet s where (s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj order by s.name")
    List<PrioritySet> findAllBindableForProject(@Param("ws") UUID ws, @Param("proj") UUID proj);

    // ---- exact-scope queries for the delegated set console ----

    @Query("select s from PrioritySet s where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) order by s.name")
    List<PrioritySet> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(s) > 0 then true else false end from PrioritySet s "
            + "where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) and s.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select s from PrioritySet s where s.id = :id "
            + "and ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj)")
    Optional<PrioritySet> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);
}
