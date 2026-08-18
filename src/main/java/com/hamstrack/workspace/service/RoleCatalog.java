package com.hamstrack.workspace.service;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.exception.RoleNotFoundException;
import com.hamstrack.workspace.exception.RoleSelectionException;
import com.hamstrack.workspace.exception.UnknownRoleException;
import com.hamstrack.workspace.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The read side of the role model: turns a role <em>id</em> into either a
 * {@link RoleView} (what may this role do?) or a managed {@link Role} reference (assign
 * this role to a member).
 *
 * <p>A thin facade over {@link RolePermissionCache} rather than more methods on it,
 * because {@code @Cacheable} only works through the proxy: anything that wants to call a
 * cached method <em>and</em> do something else has to live in a different bean or the
 * cache is bypassed.
 *
 * <p><strong>Every method here costs zero queries on a warm cache.</strong>
 * {@link #reference} in particular never SELECTs: built-in ids are compile-time constants
 * ({@link BuiltInRoles}) and {@code getReferenceById} returns a proxy whose only use is
 * to have its foreign key written. That is what lets {@code WorkspaceService.create} and
 * {@code ProjectService.create} assign a role without adding a round trip.
 */
@Service
@RequiredArgsConstructor
public class RoleCatalog {

    private final RoleRepository roleRepository;
    private final RolePermissionCache cache;

    // ------------------------------------------------------------------ views

    /** The cached snapshot of a role. */
    public RoleView view(UUID roleId) {
        return cache.byId(roleId);
    }

    /** The cached snapshot of a built-in template. Keys here are code, not user input. */
    public RoleView builtIn(RoleScope scope, String key) {
        return cache.byId(BuiltInRoles.id(scope, key));
    }

    /**
     * <strong>The one entry point for a role key that came off the wire</strong> — the
     * {@code role} field of an invite, a workspace role change or an add-member request.
     *
     * <p>Answers <strong>422 "Unknown role"</strong> for anything this build does not
     * ship in that scope, which is §12's verdict for every unresolvable role reference:
     * not 404 (which would be a statement about existence), not 400 (the request is
     * well-formed; the <em>value</em> is not), and never silently accepted. Scope is
     * required for the reason {@link BuiltInRoles#find} gives — {@code MEMBER} names a
     * different role in each scope, and a {@link com.hamstrack.common.security.PermissionSet}
     * does not remember which one its grants came from.
     *
     * <p><strong>S4 must change this signature, not just this body.</strong> Custom roles
     * are resolved by {@code RoleRepository.findAssignable(id, workspaceId, scope)}, and
     * the workspace id is <em>required</em> there (§12: a role id is resolvable from a
     * workspace-scoped path, so every read of one by id must be scoped — never a bare
     * {@code findById}, which is why {@code RoleRepository} does not extend
     * {@code JpaRepository}). This overload has no workspace id to pass, and it is only
     * sound today because built-in templates belong to no workspace. So S4 adds the
     * workspace and updates the three call sites — {@code WorkspaceService.inviteMember},
     * {@code WorkspaceMemberService.updateRole}, {@code ProjectService.addMember}. Do not
     * read "only this body changes" as licence for an unscoped lookup.
     */
    public RoleView requireAssignable(RoleScope scope, String key) {
        return cache.byId(BuiltInRoles.find(scope, key).orElseThrow(() -> new UnknownRoleException(key)));
    }

    /**
     * The default project role every workspace and project falls back to when neither
     * names one: the built-in <strong>Contributor</strong> (§5.2). Its permission set is
     * verbatim what a workspace member can do in a project today, which is the whole
     * mechanism behind the no-op upgrade.
     */
    public RoleView defaultProjectRole() {
        return cache.byId(BuiltInRoles.PROJECT_MEMBER);
    }

    // ------------------------------------------------------- assignable references

    /**
     * A managed reference to a built-in role, suitable for
     * {@code member.setRole(...)}. No query: the id is a constant and the returned proxy
     * is only ever dereferenced as a foreign key.
     */
    public Role reference(UUID roleId) {
        return roleRepository.getReferenceById(roleId);
    }

    /** A managed reference to a built-in role by scope + key. No query. */
    public Role reference(RoleScope scope, String key) {
        return reference(BuiltInRoles.id(scope, key));
    }

    /**
     * <strong>The one entry point for a role ID that came off the wire</strong> — the
     * {@code roleId} field of an invite, a workspace role change, an add- or
     * change-project-member request, or a delete-with-reassign target (S4).
     *
     * <p>Resolved through {@link RoleRepository#findAssignable}, which asks both halves of
     * the question a role id raises: <em>whose workspace</em> (one of this workspace's own,
     * or a built-in template) and <em>which scope</em>. Neither half is optional. A
     * {@link com.hamstrack.common.security.PermissionSet} is a flat {@code EnumSet} with no
     * memory of where its grants came from, so a WORKSPACE role accepted into
     * {@code project_members.role_id} would put {@code workspace.edit} and
     * {@code workspace.member.manage} straight into that member's {@code ProjectContext} —
     * a privilege escalation assembled entirely from legitimate ids that no workspace-id
     * check would catch, because the role does belong to the right workspace.
     *
     * <p><strong>422 for every unusable id</strong>, indistinguishably: foreign,
     * wrong-scope and nonsense all answer the same {@link UnknownRoleException}. Two
     * distinguishable codes here would be an oracle for whether a role id exists in another
     * tenant. Contrast the <em>path</em>-addressed reads ({@code GET/PATCH/DELETE
     * /roles/{id}}, the duplicate source), which are 404 through
     * {@link RoleRepository#findInWorkspace} — an address, not a value.
     *
     * <p>Costs one indexed lookup plus a cache hit. Do not "optimise" it to a bare
     * {@link #view} plus an in-code scope check: the check belongs in the query, which is
     * why {@code RoleRepository} does not extend {@code JpaRepository} in the first place.
     */
    public RoleView requireAssignable(RoleScope scope, UUID workspaceId, UUID roleId) {
        if (roleId == null) {
            throw new UnknownRoleException("null");
        }
        var role = roleRepository.findAssignable(roleId, workspaceId, scope)
                .orElseThrow(() -> new UnknownRoleException(roleId.toString()));
        return cache.byId(role.getId());
    }

    /**
     * A role this workspace may <strong>address</strong> — by path, never from a body.
     * 404 through {@link RoleNotFoundException} for unknown or foreign; see that class for
     * why this and {@link #requireAssignable} must not be unified.
     */
    public Role requireAddressable(UUID workspaceId, UUID roleId) {
        return roleRepository.findInWorkspace(roleId, workspaceId)
                .orElseThrow(RoleNotFoundException::new);
    }

    /**
     * <strong>The transitional two-field door</strong> (§7.2): every membership endpoint
     * accepts a new {@code roleId} <em>and</em> a legacy {@code role} key, and requires
     * exactly one.
     *
     * <p><strong>Both present is refused, not resolved by precedence.</strong> The two fields
     * do not mean the same thing — the key resolves built-ins only and, on the project side,
     * still carries the {@code VIEWER → Contributor} translation, while the id addresses any
     * assignable role verbatim. A silent winner would store a role the caller did not ask
     * for, in the one part of the product where that is a privilege change.
     *
     * @param legacyKey already translated by the caller where a translation applies
     *                  ({@code ProjectService.storedRole}); this method does not know about it
     */
    public RoleView requireAssignable(RoleScope scope, UUID workspaceId,
                                      UUID roleId, String legacyKey) {
        var hasId = roleId != null;
        var hasKey = legacyKey != null && !legacyKey.isBlank();
        if (hasId && hasKey) {
            throw RoleSelectionException.ambiguous();
        }
        if (!hasId && !hasKey) {
            throw RoleSelectionException.required();
        }
        return hasId ? requireAssignable(scope, workspaceId, roleId)
                     : requireAssignable(scope, legacyKey);
    }
}
