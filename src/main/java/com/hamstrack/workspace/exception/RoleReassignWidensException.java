package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — a bulk reassign at PROJECT scope may <em>narrow or preserve,
 * never widen</em> (HD-127, security review round 2).
 *
 * <p><strong>What it stops.</strong> {@code DELETE /roles/{id}?reassignToRoleId=} moves
 * every holder of one role onto another in a single {@code UPDATE}, in every project of the
 * workspace at once. {@link SelfHeldRoleException} closes the half of that where the actor
 * points it at <em>themselves</em>; this closes the half where they point it at everybody
 * else. Without it a workspace Admin needs one call to promote every holder of a narrow
 * custom role to the built-in Project admin — twenty project permissions, in projects the
 * actor is not a member of, with no {@code project.member.manage} required anywhere. That
 * bypasses the project grant ceiling (which is per assignment, and this is not an
 * assignment), the §4 escape's {@code target != actor} constraint, and HD-136's safety case
 * for {@code ProjectAdminGuard.adoptAll} simultaneously.
 *
 * <p><strong>Why the rule is containment rather than the actor's own set.</strong> A
 * PROJECT-scoped role has no single workspace-level comparand — the actor's project
 * permissions differ per project, and there may be no project in which they hold any. So
 * the comparand is the role <em>being deleted</em>: the target must be covered by it, i.e.
 * every holder ends up with the same grants or fewer. A reassign then cannot create
 * authority that did not already exist somewhere, which is the property the per-assignment
 * ceiling gives and a bulk statement otherwise throws away.
 *
 * <p>The workspace Owner is exempt, for the reason
 * {@code WorkspaceMemberService.requireWithinGrantCeiling} gives at length: inside one
 * workspace they are the root of trust, and the ceiling exists to stop escalation
 * <em>past</em> whoever is ultimately responsible.
 *
 * <p>At WORKSPACE scope this exception is not used at all — that path reuses
 * {@code WorkspaceMemberService.requireWithinGrantCeiling} verbatim, so it inherits the
 * Owner exemption and {@link OwnerIsNotGrantableException} rather than re-deriving them.
 *
 * <p>The detail names the offending permission, like both grant-ceiling exceptions: "you
 * cannot do this" tells an administrator nothing they can act on.
 */
public class RoleReassignWidensException extends AppException {

    public RoleReassignWidensException(String fromName, String toName, Permission missing) {
        super("Reassigning \"" + fromName + "\" to \"" + toName + "\" would widen every "
              + "holder's access: the replacement includes " + missing.key()
              + ", which \"" + fromName + "\" does not grant. Choose a replacement that is "
              + "no wider than the role being deleted", HttpStatus.FORBIDDEN);
    }
}
