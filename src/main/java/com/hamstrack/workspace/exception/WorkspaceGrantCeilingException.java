package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — the grant ceiling at workspace scope (roles-permissions-proposal
 * §11.2): nobody may hand out, or act on a member holding, a workspace role that grants a
 * permission they do not hold themselves.
 *
 * <p>The workspace twin of {@code ProjectGrantCeilingException}, and since HD-126 (S3) it
 * compares the same thing the project one does — <em>permission sets</em>
 * ({@code PermissionSet.firstNotCovered}) rather than role ordinals. The ladder it used to
 * compare is deleted: a custom role has no ordinal, and ranking one by its key would make
 * a workspace-minted role keyed {@code ADMIN} outrank its holder's real grants.
 *
 * <p>Behaviour-neutral against the built-ins by construction — Owner and Admin hold the
 * same set and Member holds strictly less — with the built-in Owner handled separately
 * ({@link OwnerIsNotGrantableException}), because sets cannot express a guardrail that is
 * deliberately not a capability. It bites the moment a workspace mints a role that can
 * manage members but not, say, edit the workspace.
 *
 * <p>The detail names the offending permission on purpose — the same field-debugging
 * argument as {@code MissingPermissionException}: "insufficient role" tells an admin
 * nothing they can act on.
 */
public class WorkspaceGrantCeilingException extends AppException {

    public WorkspaceGrantCeilingException(String roleName, Permission missing) {
        super("You cannot assign or administer the role \"" + roleName + "\", which includes "
                + missing.key() + " — a permission you do not hold in this workspace",
                HttpStatus.FORBIDDEN);
    }
}
