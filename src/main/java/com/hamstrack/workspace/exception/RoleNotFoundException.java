package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>404</strong> — a role addressed <em>by path</em> that this workspace cannot see:
 * unknown, or owned by another workspace.
 *
 * <p><strong>The 404/422 split with {@link UnknownRoleException} is a rule, not a
 * taste.</strong> A role id in a <em>path</em> is an address, so it answers 404 like every
 * other addressed resource (labels, components, versions) and the namespace stays opaque. A
 * role id in a <em>body</em> is a value, so it answers 422 — one indistinguishable code for
 * foreign, wrong-scope and nonsense — because two distinguishable codes there would be an
 * oracle for whether a role exists in another tenant. A builder must not unify them: they
 * are different questions that happen to share a type.
 *
 * <p>The detail names nothing. There is no safe way to describe a role the caller may not
 * see, and "Role not found" is exactly as informative as the situation permits.
 */
public class RoleNotFoundException extends AppException {

    public RoleNotFoundException() {
        super("Role not found", HttpStatus.NOT_FOUND);
    }
}
