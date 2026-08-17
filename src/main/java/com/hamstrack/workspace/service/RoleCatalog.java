package com.hamstrack.workspace.service;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.WorkspaceRole;
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

    /** The cached snapshot of a built-in template. */
    public RoleView builtIn(RoleScope scope, String key) {
        return cache.byId(BuiltInRoles.id(scope, key));
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

    /** Legacy bridge — deleted with the enum in S3. */
    @Deprecated(since = "HD-123 S1")
    @SuppressWarnings("deprecation")
    public Role reference(WorkspaceRole role) {
        return reference(BuiltInRoles.id(role));
    }

    /** Legacy bridge — deleted with the enum in S3. */
    @Deprecated(since = "HD-123 S1")
    @SuppressWarnings("deprecation")
    public Role reference(ProjectRole role) {
        return reference(BuiltInRoles.id(role));
    }
}
