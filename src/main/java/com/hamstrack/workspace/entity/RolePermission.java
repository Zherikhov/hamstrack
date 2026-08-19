package com.hamstrack.workspace.entity;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One grant on a {@link Role}: a permission, optionally narrowed to objects the actor
 * owns (roles-permissions-proposal §6.4).
 *
 * <p>A <strong>value</strong>, not an entity — it has no identity of its own beyond
 * {@code (role_id, permission)}, which is the composite primary key of the
 * {@code role_permissions} table. That is why {@code equals}/{@code hashCode} are
 * generated over {@code permission} <em>only</em>: within one role's collection a
 * permission appears at most once, and including {@code ownOnly} in equality would let a
 * {@code Set} hold both the unrestricted and the own-only form of the same key — a state
 * the database's primary key forbids.
 *
 * <p><strong>The consequence, which will bite the S4 role editor:</strong> because
 * equality ignores {@code ownOnly}, {@code permissions.add(new RolePermission(ISSUE_EDIT,
 * true))} on a set that already holds {@code ISSUE_EDIT} unrestricted is a <em>silent
 * no-op</em> — {@code Set.add} returns false and the own-only toggle is simply not
 * persisted, with no error anywhere. Toggling {@code own} must therefore either mutate the
 * element that is already there ({@code setOwnOnly(true)} on the existing instance) or
 * replace the collection wholesale ({@code clear()} + {@code addAll(...)}, see
 * {@link Role}). Never {@code add()} a permission the role may already hold.
 *
 * <p>The permission is mapped through {@link PermissionConverter} rather than
 * {@code @Enumerated(STRING)}: the column stores {@code "sprint.manage"}, not
 * {@code "SPRINT_MANAGE"}.
 *
 * <p><strong>{@code permission} may be null in memory</strong>, despite the {@code NOT
 * NULL} column: that is how {@link PermissionConverter} reports a stored key this build
 * does not know (it logs and drops rather than throwing on every authenticated request —
 * see its javadoc). {@code RoleView.of} filters those out. A side effect worth knowing:
 * since equality is on {@code permission} alone, two unknown keys in one role collapse to
 * one element in the {@code Set} — harmless, because both are discarded, but do not build
 * a "list the broken rows" feature on this collection. Query the table.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "permission")
public class RolePermission {

    @Convert(converter = PermissionConverter.class)
    @Column(name = "permission", nullable = false, length = 64)
    private Permission permission;

    /**
     * Narrows the grant to objects the actor owns — the issue's reporter, the comment's
     * author, the attachment's uploader, the label's creator. Only meaningful for
     * permissions where {@link Permission#supportsOwn()}, and forced true for those where
     * {@link Permission#ownRequired()}.
     */
    @Column(name = "own_only", nullable = false)
    private boolean ownOnly;
}
