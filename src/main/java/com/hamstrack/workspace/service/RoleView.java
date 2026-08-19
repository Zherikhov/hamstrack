package com.hamstrack.workspace.service;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Role;

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
     * <strong>There is deliberately no {@code asWorkspaceRole()}/{@code asProjectRole()}
     * any more</strong> (HD-126, S3). The two ordinal enums those bridges returned are
     * deleted, and with them the last way to ask "is this role at least an ADMIN?" — a
     * question a custom role cannot answer.
     *
     * <p>Keep it that way. {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT
     * DISTINCT (workspace_id, scope, key)}, so once S4 ships the role editor a workspace
     * may own a role keyed {@code ADMIN} beside the built-in one. With <em>zero</em>
     * permissions it passes the §11.2 grant ceiling (which compares permission sets, and
     * the empty set is covered by everything) and would then satisfy any surviving
     * {@code isAtLeast(ADMIN)} check for its holder. The only safe authorization question
     * about a role is what it {@link #permissions() grants}; {@link #key()} is an
     * identity, for display and for the two assignment guardrails that name a specific
     * built-in ({@code BuiltInRoles.WORKSPACE_OWNER}).
     */
    public boolean isBuiltIn(UUID builtInId) {
        return builtIn && id.equals(builtInId);
    }
}
