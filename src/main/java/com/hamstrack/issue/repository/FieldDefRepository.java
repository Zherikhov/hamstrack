package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Every LIVE definition whose key is one of the given (lowercased) keys, across all tenants
     * — the startup shadowed-key scan's one read (HD-275 §8).
     *
     * <p><strong>Deliberately unscoped, and that is not an oversight.</strong> It has exactly one
     * caller, {@code ShadowedFieldStartupScan}, which is a process rather than a request: it runs
     * as the operator on the operator's own instance and emits ids, keys, names and scope ids to
     * the application log — never issue data and never field values. Nothing request-scoped may
     * call it; every request-scoped surface builds its shadowed list from the caller's own
     * {@code ResolutionContext} instead, which costs no query and cannot cross a tenant boundary.
     *
     * <p>Archived defs are excluded here rather than at the caller because they are out of
     * resolution entirely and therefore not shadowed — {@code ShadowedFields.shadowing} says the
     * same thing about a single row. Every clean instance has three archived claimed-key rows
     * (V3's {@code labels}/{@code sprint}/{@code components} placeholders, archived by V8/V11/V9),
     * so a scan that forgot this predicate would warn on every boot of every instance.
     */
    @Query("select f from FieldDef f where f.archivedAt is null and lower(f.key) in :keys")
    List<FieldDef> findAllLiveByKeyIn(@Param("keys") Collection<String> keys);

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

    /**
     * Key/name already visible to a scope (global ∪ workspace ∪ project). Used on
     * create/rename so a delegated admin reuses an inherited (e.g. system) field
     * instead of minting a scoped duplicate of the same key or name.
     */
    @Query("select case when count(f) > 0 then true else false end from FieldDef f "
            + "where ((f.scopeWorkspaceId is null and f.scopeProjectId is null) "
            + "or f.scopeWorkspaceId = :ws or f.scopeProjectId = :proj) and f.key = :key")
    boolean existsVisibleToAndKey(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("key") String key);

    @Query("select case when count(f) > 0 then true else false end from FieldDef f "
            + "where ((f.scopeWorkspaceId is null and f.scopeProjectId is null) "
            + "or f.scopeWorkspaceId = :ws or f.scopeProjectId = :proj) and f.name = :name")
    boolean existsVisibleToAndName(@Param("ws") UUID ws, @Param("proj") UUID proj, @Param("name") String name);
}
