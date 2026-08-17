package com.hamstrack.common.security;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — the caller is a member of the workspace (so they already know
 * the resource exists) but their role does not grant the permission the call site
 * requires. Thrown only by {@link PermissionSet#require}.
 *
 * <p><strong>Never use this for a tenancy failure.</strong> A missing workspace, a
 * missing project and a non-member all yield <strong>404</strong>, resolved earlier by
 * {@code WorkspaceAccessService} — the project's top bug class is a 403 that discloses
 * a resource exists in another tenant (roles-permissions-proposal §12). By construction
 * that cannot happen here: a {@code PermissionSet} only exists once resolution has
 * already proved membership.
 *
 * <p>The detail names the exact permission ({@code "Requires permission: sprint.manage"})
 * and reaches the SPA because {@code spring.mvc.problemdetails.enabled=true}. That is
 * deliberate and is the field-debugging tool for the drift this epic exists to remove:
 * when a control that should have been hidden is clicked, the response says which
 * predicate the UI and the server disagreed about, instead of a bare 403.
 */
public class MissingPermissionException extends AppException {

    private final transient Permission permission;

    public MissingPermissionException(Permission permission) {
        super("Requires permission: " + permission.key(), HttpStatus.FORBIDDEN);
        this.permission = permission;
    }

    public Permission getPermission() {
        return permission;
    }
}
