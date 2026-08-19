package com.hamstrack.workspace.repository;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Role;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Roles are read on the authorization path, so every finder here is written to be
 * <em>one</em> statement.
 *
 * <p><strong>Tenancy (§12): never add a bare {@code findById}.</strong> {@code roles.id}
 * is a UUID that arrives from a workspace-scoped path, so it is the one genuinely new
 * cross-tenant vector in this epic — a role id belonging to another workspace must be
 * rejected as <strong>422 "Unknown role"</strong>, never 404-with-detail and never
 * accepted. {@link #findByIdWithPermissions} exists only for
 * {@code RolePermissionCache}, which is fed ids the resolver has already proved are
 * reachable (a built-in constant, or the {@code role_id} on a row the caller's own
 * membership query returned). Anything that resolves a role from <em>request input</em>
 * must go through the scoped finder {@link #findAssignable} instead.
 *
 * <p><strong>This deliberately does NOT extend {@code JpaRepository}.</strong> Doing so
 * would inherit {@code findById}, {@code findAll} and {@code getReferenceById(UUID)} —
 * unscoped by construction — into the one repository whose javadoc says not to have them,
 * and a future {@code roleRepository.findById(req.roleId())} would then compile, pass
 * review as "it's just a lookup", and hand a caller a role from another tenant. Spring
 * Data's "selectively expose CRUD methods" pattern is used instead: extend
 * {@link Repository} and re-declare only what is wanted. Everything below is either
 * scoped, or fed an id the resolver has already proved reachable. Adding a method here is
 * the moment to re-read §12.
 */
public interface RoleRepository extends Repository<Role, UUID> {

    /**
     * One statement, permissions initialised — the only load behind
     * {@code RolePermissionCache}. {@code LEFT JOIN} because a role granting nothing is
     * legal and expected (the built-in Viewer is exactly that, §11.4).
     */
    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.id = :id")
    Optional<Role> findByIdWithPermissions(@Param("id") UUID id);

    /**
     * A managed reference for writing a foreign key — no SELECT. Re-declared from
     * {@code JpaRepository} rather than inherited, so that it is the <em>only</em>
     * id-without-a-scope method on this interface and every caller is visible in one
     * grep. Legitimate uses take a compile-time built-in constant
     * ({@code BuiltInRoles}); anything resolving a role from request input must go
     * through {@link #findAssignable} first and pass <em>that</em> role's id.
     */
    Role getReferenceById(UUID id);

    /**
     * A role the given workspace may actually assign <strong>at the given scope</strong>:
     * one of its own, or a built-in template. This is the scoped finder §12 requires — the
     * single entry point for any role id that came from a request body.
     *
     * <p><strong>{@code scope} is required, not optional.</strong> Tenancy is only half
     * the question a role id raises; the other half is whether it is even the right kind
     * of role. A {@code PermissionSet} is a flat {@code EnumSet} with no memory of where
     * its grants came from, so a WORKSPACE role accepted as a project member's role would
     * put {@code workspace.edit} and {@code workspace.member.manage} into that member's
     * {@code ProjectContext.permissions()} — a privilege escalation assembled entirely
     * from legitimate ids, and one that no workspace-id check would catch because the role
     * belongs to the right workspace.
     */
    @Query("""
            SELECT r FROM Role r
             WHERE r.id = :id
               AND r.scope = :scope
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
            """)
    Optional<Role> findAssignable(@Param("id") UUID id,
                                  @Param("workspaceId") UUID workspaceId,
                                  @Param("scope") RoleScope scope);

    /**
     * <strong>Which roles actually carry one permission</strong> — built-ins plus this
     * workspace's own, at one scope. The lookup behind {@code ProjectAdminGuard}: the
     * last-administrator invariant is "at least one member who can manage members", and
     * the only honest way to ask that is to name the role ids that grant
     * {@code project.member.manage} rather than one hardcoded built-in id (HD-136).
     *
     * <p>Scoped, per §12, and for the same reason {@link #findAssignable} is: a WORKSPACE
     * role granting {@code project.member.manage} cannot exist (the catalog fixes each
     * permission's scope), but a role of <em>another</em> workspace could, and a guard
     * that counted holders of a foreign role would be reading another tenant's data to
     * decide this one's invariant.
     *
     * <p><strong>{@code own_only} grants do not count.</strong> A permission narrowed to
     * objects the actor owns is not the permission: {@code PermissionSet.has(p)} answers
     * false for an own-only holder, so counting one here would make the guard protect
     * somebody the rest of the codebase agrees cannot administer anything — and, since
     * HD-136’s adoption path, would let a project be judged stranded (or not) on a grant
     * that grants nothing. Unreachable today ({@code project.member.manage} is
     * {@code Own.NONE} and no write path can set the flag), which is exactly when a
     * predicate like this is cheap to add.
     *
     * <p>Ids, not entities: the caller only ever feeds them to an {@code IN} predicate, and
     * a projection cannot accidentally be mutated or lazily navigated.
     */
    @Query("""
            SELECT r.id FROM Role r JOIN r.permissions p
             WHERE r.scope = :scope
               AND p.permission = :permission
               AND p.ownOnly = false
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
            """)
    List<UUID> findIdsGranting(@Param("workspaceId") UUID workspaceId,
                               @Param("scope") RoleScope scope,
                               @Param("permission") Permission permission);

    /** Built-ins plus this workspace's custom roles, for the Roles screen (S4). */
    @Query("""
            SELECT r FROM Role r
             WHERE r.scope = :scope
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
             ORDER BY r.builtIn DESC, r.position ASC, r.name ASC
            """)
    List<Role> findAvailable(@Param("workspaceId") UUID workspaceId, @Param("scope") RoleScope scope);

    // ---------------------------------------------------------- S4: the roles screen
    //
    // Every method below is a §12 review moment. Two shapes only: workspace-filtered, or
    // fed an id that a workspace-filtered read already proved reachable. Nothing here
    // resolves a bare id.

    /**
     * A role this workspace may <strong>address as a resource</strong>: one of its own, or
     * a built-in template. No scope parameter, because the caller is naming a specific row
     * rather than choosing one to assign.
     *
     * <p><strong>Must never be reached from a request BODY.</strong> That is
     * {@link #findAssignable}'s job, and the difference is not stylistic: a role id in a
     * path is an <em>address</em>, so "not addressable here" is 404 and the namespace stays
     * opaque; a role id in a body is a <em>value</em>, so every unusable one — foreign,
     * wrong-scope, nonsense — must collapse into a single 422 or the pair of codes becomes
     * an oracle for whether a role exists in another tenant ({@code UnknownRoleException}
     * explains the shape). This finder cannot answer the scope half of that question at
     * all, which is exactly why it is not allowed near an assignment.
     */
    @Query("""
            SELECT r FROM Role r
             WHERE r.id = :id
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
            """)
    Optional<Role> findInWorkspace(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

    /**
     * {@link #findAvailable} with the grants attached — one statement for the whole Roles
     * screen instead of 1 + N.
     *
     * <p>The N+1 it removes is not theoretical: every role response carries a derived
     * {@code assignment} block computed against every other role of the same scope, so a
     * lazily-fetched {@code permissions} collection would be initialised for all of them
     * on every list. Hibernate 6+ de-duplicates {@code JOIN FETCH} results for entity
     * queries, so no {@code DISTINCT} is needed (and adding one would push a needless
     * sort onto the database).
     *
     * <p>{@code scopes} is a collection rather than a single value because
     * {@code GET /roles} takes an <em>optional</em> {@code scope} filter, and the absent
     * case must stay one query rather than two.
     */
    @Query("""
            SELECT r FROM Role r LEFT JOIN FETCH r.permissions
             WHERE r.scope IN :scopes
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
             ORDER BY r.scope ASC, r.builtIn DESC, r.position ASC, r.name ASC
            """)
    List<Role> findAvailableWithPermissions(@Param("workspaceId") UUID workspaceId,
                                            @Param("scopes") Collection<RoleScope> scopes);

    /**
     * How many custom roles this workspace already owns, across <strong>both</strong>
     * scopes — the {@code app.roles.max-custom-per-workspace} bound. Built-in templates
     * have {@code workspace_id IS NULL} and are shared by every workspace, so they can
     * never be counted here.
     */
    long countByWorkspaceIdAndBuiltInFalse(UUID workspaceId);

    /**
     * The display-name uniqueness guard for {@code (workspace, scope)}. Application-only:
     * a race yields two same-named roles, which is cosmetic, and a partial unique index on
     * {@code LOWER(name)} would need a migration this slice deliberately does not have.
     * Built-in names are checked separately, in code, because their rows belong to no
     * workspace and so are invisible to this predicate.
     */
    boolean existsByWorkspaceIdAndScopeAndNameIgnoreCase(UUID workspaceId, RoleScope scope, String name);

    /**
     * Whether a generated key is already taken in {@code (workspace, scope)} — the
     * collision half of key generation. Built-in keys are excluded by the same
     * {@code workspace_id} filter and are guarded in code instead, against
     * {@code BuiltInRoles}: {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT},
     * so the database would happily accept a workspace-owned role keyed {@code ADMIN}
     * beside the built-in one.
     */
    boolean existsByWorkspaceIdAndScopeAndKey(UUID workspaceId, RoleScope scope, String key);

    /** Next display position within a scope. Null when the workspace owns none yet. */
    @Query("""
            SELECT MAX(r.position) FROM Role r
             WHERE r.workspaceId = :workspaceId AND r.scope = :scope
            """)
    Short maxPosition(@Param("workspaceId") UUID workspaceId, @Param("scope") RoleScope scope);

    /**
     * Re-declared from {@code JpaRepository} rather than inherited — that is the point of
     * this interface. Only {@code RoleService} calls them, and only on a {@link Role} that
     * {@link #findInWorkspace} already proved this workspace may address.
     */
    Role save(Role role);

    /** @see #save */
    void delete(Role role);

    /**
     * Flush pending role writes.
     *
     * <p>Needed by delete-with-reassign for the ordinary Hibernate reason rather than the
     * documented "replace children wholesale" one: the bulk reassign {@code UPDATE}s and
     * the role {@code DELETE} must reach the database in that order, and JPQL bulk
     * statements bypass the persistence context, so an unflushed {@code delete} would
     * otherwise be ordered by the {@code ActionQueue} rather than by the code.
     */
    void flush();
}
