package com.hamstrack.common.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps {@link Permission} to the {@code role_permissions.permission} VARCHAR(64) column
 * by its {@link Permission#key()} ({@code "sprint.manage"}), not by {@link Enum#name()}
 * ({@code "SPRINT_MANAGE"}).
 *
 * <p>This is why the field is <strong>not</strong> {@code @Enumerated(EnumType.STRING)}:
 * that would silently store the Java identifier, and the identifier is not the product's
 * vocabulary. The stored value is the same string the SPA receives in
 * {@code myPermissions} and the same string {@code V13__roles.sql} seeds, so a grant is
 * greppable from psql to the browser console.
 *
 * <p><strong>An unknown key is dropped and logged at ERROR — it does not throw.</strong>
 * A row this build does not understand means the database and the catalog have diverged,
 * and there are only two ways to answer that on the <em>read</em> path:
 *
 * <ul>
 *   <li><strong>Throw</strong> — which is fail-closed and <em>down</em>. This converter
 *       runs inside {@code RoleView.of} ← {@code RolePermissionCache.byId} ←
 *       {@code WorkspaceAccessService.requireMember}, i.e. on every authenticated request
 *       that names a workspace. A bad row on a <em>built-in</em> role would therefore
 *       break every workspace in the instance, including for the administrator who has to
 *       fix it. And it is reachable without anybody doing anything exotic: release N+1
 *       adds a permission constant, an admin's role editor writes the new key into
 *       {@code role_permissions}, N+1 gets rolled back.</li>
 *   <li><strong>Drop the grant</strong> — fail-closed and <em>up</em>. This is what we do.
 *       Dropping an input grant can only ever <em>narrow</em> the resulting role: the
 *       whole of {@code PermissionSet.of} only ever {@code add}s to the two sets, and its
 *       one {@code removeAll} takes from {@code own} what is already in
 *       {@code unrestricted}. There is no path by which a missing input produces an extra
 *       output permission — including for a renamed key, which simply yields the narrower
 *       role until the rename's migration runs.</li>
 * </ul>
 *
 * <p>So the loud-but-degraded option strictly dominates, and the ERROR log (with the key
 * and the row's role) is what makes it non-silent — the §18 risk is silence, not
 * narrowing. {@code RoleView.of} filters the resulting nulls before they reach
 * {@code PermissionSet.of}.
 *
 * <p><strong>The hard rejection belongs on the write path</strong> (S4): a role editor
 * that is handed a key this build does not know must answer <strong>422 "Unknown
 * permission"</strong>, because there the divergence is user input and refusing it costs
 * one request instead of the instance.
 */
@Converter(autoApply = false)
public class PermissionConverter implements AttributeConverter<Permission, String> {

    private static final Logger log = LoggerFactory.getLogger(PermissionConverter.class);

    @Override
    public String convertToDatabaseColumn(Permission attribute) {
        return attribute == null ? null : attribute.key();
    }

    @Override
    public Permission convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        var permission = Permission.byKey(dbData).orElse(null);
        if (permission == null) {
            log.error("role_permissions holds an unknown permission key '{}'. The database and "
                      + "com.hamstrack.common.security.Permission have diverged — most likely a "
                      + "downgrade below the release that added it. THE GRANT IS BEING DROPPED, so "
                      + "roles holding it are narrower than intended until this is resolved: "
                      + "restore the constant (upgrade back), or delete the row in a migration.",
                    dbData);
        }
        return permission;
    }
}
