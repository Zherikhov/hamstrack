package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldSetRepository extends JpaRepository<FieldSet, UUID> {

    List<FieldSet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<FieldSet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<FieldSet> findBySystemDefaultTrue();

    Optional<FieldSet> findByScopeWorkspaceIdIsNullAndName(String name);

    /** Global (both scopes null) lookup by name — safe once project-scoped field sets exist. */
    Optional<FieldSet> findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    /** See {@code WorkflowRepository#findAllBindableForProject}. */
    @Query("select s from FieldSet s where (s.scopeWorkspaceId is null and s.scopeProjectId is null) "
            + "or s.scopeWorkspaceId = :ws or s.scopeProjectId = :proj order by s.name")
    List<FieldSet> findAllBindableForProject(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select s from FieldSet s where s.id = :id "
            + "and ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj)")
    Optional<FieldSet> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select s from FieldSet s where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) order by s.name")
    List<FieldSet> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(s) > 0 then true else false end from FieldSet s "
            + "where ((:ws is null and s.scopeWorkspaceId is null) or s.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and s.scopeProjectId is null) or s.scopeProjectId = :proj) and s.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);
}
