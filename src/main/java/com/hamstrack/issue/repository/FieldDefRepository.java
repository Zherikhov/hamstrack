package com.hamstrack.issue.repository;

import com.hamstrack.common.security.CalledOnlyFrom;
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
     * <p><strong>Deliberately unscoped, and that is not an oversight.</strong> What makes it safe
     * is that its caller is a process rather than a request: a scan that runs as the operator on
     * the operator's own instance and emits ids, keys, names and scope ids to the application log
     * — never issue data and never field values. Nothing request-scoped may call it; every
     * request-scoped surface builds its shadowed list from the caller's own
     * {@code ResolutionContext} instead, which costs no query and cannot cross a tenant boundary.
     *
     * <p><strong>That sentence is a declaration now, not a description</strong> (HD-340). The
     * admissible callers are named in the {@code @CalledOnlyFrom} below, and
     * {@code CalledOnlyFromSealTest} derives the real ones from production bytecode and refuses
     * any that is not declared. Adding a caller is an edit of that annotation, in the same commit,
     * and a caller that is a web bean — or a class a web bean dispatches to directly — is a red
     * build even when somebody declares it.
     *
     * <p><strong>Be exact about the reach of that guarantee, because the paragraph above is the
     * strong form.</strong> The seal refuses a declared caller at ONE hop from a web bean, not at
     * every depth: reachability at any depth holds most of this tree and would refuse the
     * legitimate callers too, so it is not the predicate. A would-be caller sitting two hops out —
     * a service a controller reaches through another service — is caught by the first rule
     * (it is not declared) and not by the second (it is not refused once it is). That residual is
     * stated on the seal and repeated here so a reader of this file is not left with the strong
     * sentence alone.
     *
     * <p>Archived defs are excluded here rather than at the caller because they are out of
     * resolution entirely and therefore not shadowed — {@code ShadowedFields.shadowing} says the
     * same thing about a single row. Every clean instance has three archived claimed-key rows
     * (V3's {@code labels}/{@code sprint}/{@code components} placeholders, archived by V8/V11/V9),
     * so a scan that forgot this predicate would warn on every boot of every instance.
     */
    @CalledOnlyFrom(
            callers = "com.hamstrack.search.ShadowedFieldStartupScan",
            because = "it answers with every tenant's live definitions, so a request-scoped caller "
                      + "would show one tenant another tenant's fields",
            instead = "build the shadowed list from the caller's own ResolutionContext, which costs "
                      + "no query, or read findAllVisibleTo(ws, project), which is scoped")
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
