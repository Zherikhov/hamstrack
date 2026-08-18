package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.RoleScope;
import org.springframework.http.HttpStatus;

/**
 * <strong>422</strong> — the write-path half of the unknown-key rule whose read-path half is
 * {@code PermissionConverter}'s log-and-drop.
 *
 * <p>The asymmetry is deliberate and each direction is fail-closed in the way that suits it.
 * On <em>read</em>, the converter runs inside {@code requireMember} on every authenticated
 * request naming a workspace, so throwing would take the instance down for one bad row on a
 * built-in role — and dropping a grant can only ever <em>narrow</em> a role, so it degrades
 * loudly rather than failing. On <em>write</em>, the divergence is user input: refusing costs
 * one request instead of the instance, and silently dropping it would store a role that does
 * not do what the admin just ticked.
 *
 * <p>Covers four rejections, all 422 and all about the <em>value</em> rather than the shape
 * of the request:
 * <ul>
 *   <li>a key this build does not ship;</li>
 *   <li><strong>a permission whose {@code scope()} is not the role's</strong> — the half of
 *       the wrong-scope hole that {@code RoleRepository.findAssignable} cannot see, because
 *       a flat {@code PermissionSet} does not remember where a grant came from, so a
 *       PROJECT-scoped role carrying {@code workspace.member.manage} would put it into every
 *       holder's {@code ProjectContext} with no query, log or test able to notice;</li>
 *   <li>{@code ownOnly} on a permission that does not support it — honouring it silently
 *       would <em>narrow</em> the grant behind the admin's back;</li>
 *   <li>the same key twice in one list, whose two entries could disagree about
 *       {@code ownOnly} and one of which would win invisibly.</li>
 * </ul>
 * {@link com.hamstrack.common.security.Permission#ownRequired()} permissions
 * ({@code comment.edit}) are <em>not</em> refused — they are forced own-only, which
 * {@code PermissionSet.of} already does and the editor renders as a locked toggle.
 */
public class UnknownPermissionException extends AppException {

    private UnknownPermissionException(String detail) {
        super(detail, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    public static UnknownPermissionException unknownKey(String key) {
        return new UnknownPermissionException("Unknown permission: " + key);
    }

    public static UnknownPermissionException wrongScope(String key, RoleScope roleScope) {
        return new UnknownPermissionException(
                "Permission " + key + " cannot be granted by a " + roleScope
                + "-scoped role — a role may only hold permissions of its own scope");
    }

    public static UnknownPermissionException ownNotSupported(String key) {
        return new UnknownPermissionException(
                "Permission " + key + " cannot be narrowed to objects you own");
    }

    public static UnknownPermissionException duplicate(String key) {
        return new UnknownPermissionException("Permission " + key + " is listed twice");
    }
}
