package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldDefRepository extends JpaRepository<FieldDef, UUID> {

    List<FieldDef> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<FieldDef> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<FieldDef> findByScopeWorkspaceIdIsNullAndKey(String key);

    /** Global (both scopes null) lookup by key — safe once project-scoped fields exist. */
    Optional<FieldDef> findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndKey(String key);

    boolean existsByScopeWorkspaceIdIsNullAndKey(String key);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);

    // ---- exact-scope queries for the delegated field console ----

    @Query("select f from FieldDef f where ((:ws is null and f.scopeWorkspaceId is null) or f.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and f.scopeProjectId is null) or f.scopeProjectId = :proj) order by f.name")
    List<FieldDef> findAllAtScope(@Param("ws") UUID ws, @Param("proj") UUID proj);

    @Query("select case when count(f) > 0 then true else false end from FieldDef f "
            + "where ((:ws is null and f.scopeWorkspaceId is null) or f.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and f.scopeProjectId is null) or f.scopeProjectId = :proj) and f.key = :key")
    boolean existsAtScopeAndKey(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("key") String key);

    @Query("select case when count(f) > 0 then true else false end from FieldDef f "
            + "where ((:ws is null and f.scopeWorkspaceId is null) or f.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and f.scopeProjectId is null) or f.scopeProjectId = :proj) and f.name = :name")
    boolean existsAtScopeAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);

    @Query("select f from FieldDef f where f.id = :id "
            + "and ((:ws is null and f.scopeWorkspaceId is null) or f.scopeWorkspaceId = :ws) "
            + "and ((:proj is null and f.scopeProjectId is null) or f.scopeProjectId = :proj)")
    Optional<FieldDef> findByIdAtScope(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** By id, visible to a scope: global ∪ that workspace ∪ that project. */
    @Query("select f from FieldDef f where f.id = :id and ((f.scopeWorkspaceId is null and f.scopeProjectId is null) "
            + "or f.scopeWorkspaceId = :ws or f.scopeProjectId = :proj)")
    Optional<FieldDef> findByIdVisibleTo(@Param("id") UUID id, @Param("ws") UUID ws, @Param("proj") UUID proj);

    /** All visible to a scope (delegated console list): global ∪ workspace ∪ project. */
    @Query("select f from FieldDef f where (f.scopeWorkspaceId is null and f.scopeProjectId is null) "
            + "or f.scopeWorkspaceId = :ws or f.scopeProjectId = :proj order by f.name")
    List<FieldDef> findAllVisibleTo(@Param("ws") UUID ws, @Param("proj") UUID proj);
}
