package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code ROLE_LIMIT_REACHED}</strong> — this workspace already holds
 * {@code app.roles.max-custom-per-workspace} custom roles.
 *
 * <p><strong>A sprawl guard, never a licence check</strong> (§9/§13). Custom roles are a
 * product feature and not a plan feature: the number is identical in {@code dc} and
 * {@code cloud} and must never become profile-gated. If custom roles were ever limited per
 * deployment it would be by lowering this value, never by a second code path.
 *
 * <p>Counted across <strong>both scopes</strong>, {@code built_in = false}, per workspace —
 * built-in templates belong to no workspace and never count.
 *
 * <p><strong>{@code errorType} is on the wire, not just in this javadoc.</strong> Round 2
 * found the name existed only server-side: the spec, this class and the review all called
 * the refusal {@code ROLE_LIMIT_REACHED} while the response was a bare {@code ProblemDetail}
 * a client could not tell from any other 409 on the same endpoint. {@code POST
 * /roles/{id}/duplicate} answers 409 for three unrelated reasons — this one, a display-name
 * conflict, and a lock-wait timeout from the sprawl-cap row lock (which is separable because
 * it alone carries {@code Retry-After}) — and each wants different client behaviour: delete
 * a role, choose another name, retry. Same convention as {@link RoleInUseException} and
 * {@link SelfHeldRoleException}. {@link RoleNameConflictException} still has none; its
 * detail names the offending name, so it is separable by content rather than by
 * discriminator, and adding one is a follow-up rather than a round-2 finding.
 */
public class RoleLimitReachedException extends AppException {

    /** @see #getErrorType() */
    public static final String ROLE_LIMIT_REACHED = "ROLE_LIMIT_REACHED";

    public RoleLimitReachedException(int max) {
        super("This workspace already has the maximum of " + max + " custom roles — "
              + "delete one you no longer use first", HttpStatus.CONFLICT);
    }

    /** @see RoleInUseException#getErrorType() */
    public String getErrorType() {
        return ROLE_LIMIT_REACHED;
    }
}
