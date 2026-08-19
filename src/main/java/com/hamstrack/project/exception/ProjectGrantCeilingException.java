package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — the grant ceiling at project scope (roles-permissions-proposal
 * §11.2): nobody may hand out, or act on, a project role that holds a permission they do
 * not hold themselves.
 *
 * <p>Without it, {@code project.member.manage} is self-escalation to everything in two
 * calls — remove your own membership row, then add yourself back as Project admin — and
 * both calls pass their own gate.
 *
 * <p><strong>Both scopes compare permission sets</strong>
 * ({@code PermissionSet.firstNotCovered}) since HD-126 (S3) — a custom role has no
 * ordinal, and the ladder the workspace twin
 * ({@code WorkspaceMemberService.requireWithinGrantCeiling}) used to compare is exactly
 * what HD-123 removed. The one thing sets cannot express lives beside that twin rather
 * than inside it: the built-in workspace Owner and Admin are seeded with identical sets on
 * purpose, so "only an Owner may hand out Owner" is its own guardrail
 * ({@code OwnerIsNotGrantableException}). There is no project-scoped equivalent, because
 * no two built-in project roles hold the same set.
 *
 * <p>Behaviour-neutral until S4: today only the built-in Project admin holds
 * {@code project.member.manage}, and it holds every project permission, so its ceiling
 * covers every role it could assign. The rule bites the moment a workspace mints a
 * "Team lead" role that can manage members but not, say, delete issues.
 *
 * <p>The detail names the offending permission on purpose — the same field-debugging
 * argument as {@code MissingPermissionException}: "insufficient role" tells an admin
 * nothing they can act on.
 */
public class ProjectGrantCeilingException extends AppException {

    public ProjectGrantCeilingException(GrantCeilingAction action, String roleName, Permission missing) {
        super(action.phrase() + " \"" + roleName + "\", which includes " + missing.key()
                + " — a permission you do not hold in this project", HttpStatus.FORBIDDEN);
    }
}
