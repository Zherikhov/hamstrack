package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * {@code DELETE /workspaces/{ws}/members/{userId}} is the <strong>administrative</strong>
 * path — one person removing another. Leaving a workspace yourself is a different feature
 * with a different shape (confirmation, where the user lands afterwards, what happens when
 * it was their only workspace and the onboarding flow has to take over), and it is not
 * built yet (HD-132).
 *
 * <p>Without this guard the grant ceiling passes trivially when actor and target are the
 * same person, so an ADMIN — or any OWNER who is not the last one — could delete their own
 * membership through a door the docs say does not exist. Refusing makes the documented
 * contract true rather than aspirational.
 *
 * <p><strong>422, not 403.</strong> The caller's permissions are not in question; the
 * request is a well-formed thing this endpoint does not do. Per CLAUDE.md, business-rule
 * rejections use {@code UNPROCESSABLE_CONTENT} (the RFC 9110 name — {@code
 * UNPROCESSABLE_ENTITY} is deprecated in Boot 4).
 *
 * <p>Note the deliberate asymmetry with {@code PATCH}: self-<em>demotion</em> stays legal.
 * An owner stepping down to admin while another owner exists is an ordinary handover, and
 * {@link LastWorkspaceOwnerException} already refuses the case that would orphan the
 * workspace.
 */
public class CannotRemoveSelfException extends AppException {
    public CannotRemoveSelfException() {
        super("Leaving a workspace is not this endpoint — an administrator removes other members",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
