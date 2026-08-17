package com.hamstrack.workspace.service;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
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
}
