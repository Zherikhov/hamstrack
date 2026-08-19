package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.service.RoleView;

import java.time.Instant;
import java.util.UUID;

/**
 * @param roleId the member's role <strong>identity</strong> (HD-129, S6) — the id of the
 *     {@code roles} row this membership points at, and the only field here a client may
 *     use to match a member against the role catalog ({@code GET /workspaces/{id}/roles}).
 *     <p><strong>{@link #role} is a key, and a key is not an identity.</strong> Keys are
 *     unique per {@code (workspace, scope)} only — {@code roles_scope_key_uk} is
 *     {@code UNIQUE NULLS NOT DISTINCT (workspace_id, scope, key)} — so {@code MEMBER}
 *     already names two different built-ins (workspace Member and project Contributor),
 *     and the schema permits a workspace-owned role keyed {@code ADMIN} beside the
 *     built-in one. {@code RoleService.generateKey} suffixes on collision against both
 *     {@code BuiltInRoles} and the workspace's own rows, so a duplicate <em>within</em> a
 *     scope is not creatable through the API today; this field exists because resolving an
 *     identity through a display-shaped string is wrong whether or not the collision is
 *     currently reachable — not because there is a live bug.
 * @param role the member's role <em>key</em> ({@code "OWNER"}/{@code "ADMIN"}/
 *     {@code "MEMBER"} — the same strings the deleted enum serialised as). Kept beside
 *     {@code roleId} rather than replaced by it: S5's screens are live and read it, and
 *     each slice has to be independently deployable — the same rule {@code roleId}-beside-
 *     {@code role} follows on the request side (S4).
 *
 *     <p><strong>Both are {@code null} when the row's {@code role_id} fails the
 *     scope/ownership assertion</strong> (HD-127 §3b). The member stays in the list — one
 *     corrupt row must not 404 the whole People tab — and the role just refused is
 *     emphatically NOT rendered in its place: that name is the one genuine leak in the
 *     vicinity, and it is the reason this listing runs the assertion at all. {@code roleId}
 *     degrades <em>with</em> the key by construction (they come from one nullable
 *     {@link RoleView}); emitting an id whose key was refused would hand the client the
 *     very thing the degrade withheld — the client would look it up in the catalog and
 *     print the name anyway.
 */
public record WorkspaceMemberResponse(
        UUID userId,
        String email,
        String displayName,
        String avatarUrl,
        UUID roleId,
        String role,
        Instant joinedAt
) {
    /**
     * @param role the resolved role, or {@code null} for the degraded form. Passed in
     *     rather than read off {@code m.getRole()} because that is a {@code roles} row and
     *     translating it needs the cached catalog the caller already has — and because the
     *     caller is the one that ran (or refused) the scope/ownership assertion.
     */
    public static WorkspaceMemberResponse of(WorkspaceMember m, RoleView role) {
        var u = m.getUser();
        return new WorkspaceMemberResponse(
                u.getId(), u.getEmail(), u.getDisplayName(), u.getAvatarUrl(),
                role == null ? null : role.id(),
                role == null ? null : role.key(),
                m.getJoinedAt());
    }
}
