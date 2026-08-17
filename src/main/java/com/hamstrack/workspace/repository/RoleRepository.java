package com.hamstrack.workspace.repository;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Role;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

    /** Built-ins plus this workspace's custom roles, for the Roles screen (S4). */
    @Query("""
            SELECT r FROM Role r
             WHERE r.scope = :scope
               AND (r.workspaceId IS NULL OR r.workspaceId = :workspaceId)
             ORDER BY r.builtIn DESC, r.position ASC, r.name ASC
            """)
    List<Role> findAvailable(@Param("workspaceId") UUID workspaceId, @Param("scope") RoleScope scope);
}
