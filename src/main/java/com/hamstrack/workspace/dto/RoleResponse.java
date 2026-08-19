package com.hamstrack.workspace.dto;

import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Role;

import java.util.List;
import java.util.UUID;

/**
 * One role, as the Roles screen and the role editor see it (§7.1).
 *
 * @param builtIn     built-in templates are visible to every workspace (they are product
 *                    metadata) and are neither editable nor deletable — 409, keyed on this
 *                    column and never on the key string, since a workspace may legally own
 *                    a custom role keyed {@code ADMIN}
 * @param version     the {@code @Version} a subsequent PATCH echoes back for the
 *                    modified-by-someone-else 409
 * @param permissions the object form ({@link RolePermissionEntry}), not
 *                    {@code myPermissions}' flat {@code "key:own"} strings
 * @param assignment  the derived ceiling feedback (§5). Computed for built-ins too: it
 *                    costs nothing (every set is cached) and an asymmetric response would
 *                    push the branch into the SPA
 * @param usage       only on {@code GET /roles/{id}/usage} and on the list with
 *                    {@code includeUsage=true} + {@code workspace.role.manage}; otherwise
 *                    {@code null}, because the counts are the sensitive half and the list
 *                    itself is open to every member
 */
public record RoleResponse(
        UUID id,
        RoleScope scope,
        String key,
        String name,
        String description,
        boolean builtIn,
        short position,
        long version,
        List<RolePermissionEntry> permissions,
        RoleAssignmentView assignment,
        RoleUsageResponse usage
) {
    public static RoleResponse of(Role role, PermissionSet permissions,
                                  RoleAssignmentView assignment, RoleUsageResponse usage) {
        return new RoleResponse(role.getId(), role.getScope(), role.getKey(), role.getName(),
                role.getDescription(), role.isBuiltIn(), role.getPosition(), role.getVersion(),
                entries(permissions), assignment, usage);
    }

    /**
     * Rendered from the resolved {@link PermissionSet} rather than from the stored
     * {@code role_permissions} rows, so a grant this build no longer understands is dropped
     * here exactly as it is dropped on the authorization path — the editor shows what the
     * role actually <em>does</em>, not what the table happens to hold. Catalog order, so the
     * body is stable and diffable across requests and installs.
     */
    private static List<RolePermissionEntry> entries(PermissionSet permissions) {
        return permissions.asWireStrings().stream()
                .map(s -> s.endsWith(PermissionSet.OWN_SUFFIX)
                        ? new RolePermissionEntry(
                                s.substring(0, s.length() - PermissionSet.OWN_SUFFIX.length()), true)
                        : new RolePermissionEntry(s, false))
                .toList();
    }
}
