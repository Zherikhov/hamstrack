package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.WorkspaceRole;

import java.util.UUID;

/**
 * An immutable snapshot of a {@link Role} — what {@code RolePermissionCache} hands out
 * and what the request contexts carry.
 *
 * <p>Deliberately <strong>not</strong> the entity. A cached JPA entity would be detached
 * across the cache TTL and one careless {@code save()} away from being written back; and
 * navigating it ({@code role.getPermissions()}, {@code role.getWorkspace()}) would fire
 * lazy loads on the authorization path, which is exactly the constant-cost property §9.2
 * promises. Everything a caller needs is copied here at load time, so a {@code RoleView}
 * is safe to share across threads, requests and transactions.
 *
 * @param workspaceId the owning workspace, or {@code null} for a built-in template
 */
public record RoleView(
        UUID id,
        UUID workspaceId,
        RoleScope scope,
        String key,
        String name,
        boolean builtIn,
        PermissionSet permissions
) {

    public static RoleView of(Role role) {
        var grants = role.getPermissions().stream()
                // A null permission is a key this build does not know: PermissionConverter
                // logs it and returns null rather than throwing, because throwing here would
                // take down every authenticated request naming a workspace (see its javadoc).
                // Dropping the grant can only ever NARROW the role — PermissionSet.of only
                // ever adds — so the fail-closed direction is the safe one.
                .filter(rp -> rp.getPermission() != null)
                .map(rp -> new PermissionSet.Grant(rp.getPermission(), rp.isOwnOnly()))
                .toList();
        return new RoleView(role.getId(), role.getWorkspaceId(), role.getScope(),
                role.getKey(), role.getName(), role.isBuiltIn(), PermissionSet.of(grants));
    }

    /**
     * Legacy bridge: the {@link WorkspaceRole} this role's key names.
     *
     * <p>Exists only so the ~42 authorization call sites that have not moved yet (S2/S3)
     * keep compiling and behaving identically. It is safe <em>only</em> for the three
     * built-in workspace roles, whose keys ARE the enum names. Never add a new caller;
     * S3 deletes every existing one.
     *
     * <p><strong>Why this checks {@code builtIn} and not just the key.</strong>
     * {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT (workspace_id, scope,
     * key)}, so a custom role keyed {@code "ADMIN"} in workspace X is perfectly legal
     * alongside the built-in {@code (NULL, WORKSPACE, ADMIN)}. Relying on
     * {@code valueOf} to throw would therefore protect only custom roles whose key happens
     * <em>not</em> to collide with an enum name — the exact opposite of the dangerous
     * case. Once S4 ships the role editor, a custom workspace role keyed {@code ADMIN}
     * with <strong>zero permissions</strong> would sail past the §11.2 grant ceiling
     * (which compares permission sets, and the empty set is a subset of everything) and
     * then satisfy every surviving {@code isAtLeast(ADMIN)} bridge. Same shape for a
     * project role keyed {@code MANAGER} against {@code requireProjectAdmin},
     * {@code requireProjectCurator}, {@code IssueService.delete} and
     * {@code AttachmentService.delete}. The invariant belongs here, not in slice ordering.
     *
     * @throws IllegalStateException if this is a custom role
     */
    @Deprecated(since = "HD-123 S1")
    public WorkspaceRole asWorkspaceRole() {
        requireBuiltIn();
        return WorkspaceRole.valueOf(key);
    }

    /**
     * Legacy bridge: the {@link ProjectRole} this role's key names. Same contract and
     * same expiry as {@link #asWorkspaceRole()}.
     *
     * @throws IllegalStateException if this is a custom role
     */
    @Deprecated(since = "HD-123 S1")
    public ProjectRole asProjectRole() {
        requireBuiltIn();
        return ProjectRole.valueOf(key);
    }

    private void requireBuiltIn() {
        if (!builtIn) {
            throw new IllegalStateException(
                    "Legacy role bridge called for custom role " + id + " (key=" + key
                    + ", workspace=" + workspaceId + "). An ordinal role ladder cannot rank a "
                    + "custom role, and a custom role may legally reuse a built-in key — so "
                    + "answering would mean granting whatever that key ranks as. Convert the "
                    + "call site to ctx.permissions().require(...) (HD-123 S2/S3).");
        }
    }
}
