package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — only an Owner may hand out the built-in <strong>Owner</strong>
 * role, or administer a member who holds it (roles-permissions-proposal §11.2).
 *
 * <p><strong>Why this is a rule of its own and not a permission comparison.</strong> The
 * built-in Owner and Admin are seeded with <em>identical</em> permission sets, deliberately
 * — V13 says it in as many words: "Owner is a guardrail on assignment, not a bigger role".
 * So the set-containment ceiling ({@link WorkspaceGrantCeilingException}) cannot see any
 * difference between them, and without this an Admin could promote themselves to Owner, or
 * demote the last real one. Encoding the difference as a permission would be dishonest —
 * there is no <em>capability</em> an Owner has and an Admin lacks — so it is encoded where
 * it actually lives: assignment.
 *
 * <p>Together with §11.1 (a workspace must keep one Owner) this is what makes ownership a
 * handover rather than something that can be taken.
 */
public class OwnerIsNotGrantableException extends AppException {

    public OwnerIsNotGrantableException() {
        super("Only an Owner can assign the Owner role or administer another Owner",
                HttpStatus.FORBIDDEN);
    }

    private OwnerIsNotGrantableException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }

    /**
     * <strong>An invitation never grants Owner — not even one issued by an Owner</strong>
     * (HD-127 round-3 review). {@code WorkspaceService.inviteMember} states the rule at the
     * front door: you promote a colleague to owner, you do not invite a stranger as one.
     * {@code RoleService.delete}'s bulk reassign is the back one — repointing a pending
     * invite at the built-in Owner role is issuing exactly that invitation, and the Owner
     * exemption in the grant ceiling returns before this clause can fire, so the rule had a
     * hole that a test asserted as desired behaviour.
     *
     * <p>Refusal rather than a silent purge of the invites: they belong to somebody else,
     * the actor did not ask for them to be destroyed, and the remedy is one move away —
     * reassign to any other role and promote the invitee once they have accepted.
     */
    public static OwnerIsNotGrantableException viaInvite(long pendingInvites) {
        return new OwnerIsNotGrantableException(
                "This role has " + pendingInvites + " pending invitation"
                + (pendingInvites == 1 ? "" : "s")
                + ", and an invitation can never grant the Owner role — ownership is handed "
                + "over to an existing member, not offered to somebody who has not joined "
                + "yet. Reassign to another role, then promote them once they accept");
    }
}
