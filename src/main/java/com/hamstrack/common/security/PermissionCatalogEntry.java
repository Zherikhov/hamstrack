package com.hamstrack.common.security;

/**
 * One row of {@code GET /api/permissions} — the product metadata the role editor renders
 * and the reference a client uses to make sense of a {@code myPermissions} string.
 *
 * <p>It is generated from {@link Permission} on every call rather than stored, so the
 * catalog a client sees and the catalog the server enforces cannot drift.
 *
 * @param key          the stored/wire key, e.g. {@code "sprint.manage"}
 * @param scope        {@code WORKSPACE} or {@code PROJECT}
 * @param supportsOwn  whether a grant may be narrowed to objects the actor owns (§6.4)
 * @param ownRequired  whether it may <em>only</em> be granted own-only ({@code comment.edit})
 * @param capability   the delivery capability this is about, or {@code null} — a role-editor
 *                     hint ("sprint planning", "releases"), <strong>never</strong> a check
 *                     (§5.3): a role is workspace-level while capabilities are per-project,
 *                     so this must render as a muted tag and never as a disabled control
 */
public record PermissionCatalogEntry(
        String key,
        RoleScope scope,
        boolean supportsOwn,
        boolean ownRequired,
        CapabilityHint capability
) {
    public static PermissionCatalogEntry of(Permission p) {
        return new PermissionCatalogEntry(
                p.key(), p.scope(), p.supportsOwn(), p.ownRequired(), p.capability());
    }
}
