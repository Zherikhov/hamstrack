package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueTypeSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTypeSetRepository extends JpaRepository<IssueTypeSet, UUID> {

    List<IssueTypeSet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<IssueTypeSet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<IssueTypeSet> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    /** See {@code WorkflowRepository#findAllBindableForProject}. */
    @Query("select s from IssueTypeSet s where (s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj order by s.name")
    List<IssueTypeSet> findAllBindableForProject(@Param("ws") UUID ws, @Param("proj") UUID proj);

    // ---- exact-scope queries for the delegated set console ----

    @Query("select s from IssueTypeSet s where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) order by s.name")
    List<IssueTypeSet> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(s) > 0 then true else false end from IssueTypeSet s "
            + "where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) and s.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select s from IssueTypeSet s where s.id = :id "
            + "and ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj)")
    Optional<IssueTypeSet> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);
}
