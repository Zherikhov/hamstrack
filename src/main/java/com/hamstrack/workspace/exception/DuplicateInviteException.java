package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code DUPLICATE_INVITE}</strong> — this workspace already holds an unaccepted
 * invitation to this address (HD-133 §4.5).
 *
 * <p><strong>Refuse rather than replace, and that decision is about what "invite" is allowed to
 * do silently.</strong> Replacing the standing row would revoke an outstanding grant with no
 * confirmation, no {@code hamstrack.invites.revoked} metric and no audit line — three things
 * HD-158 deliberately attached to withdrawal, because withdrawing a standing grant is a
 * security-relevant act. It would also refresh a seven-day TTL that is itself the bound on how
 * long anyone-holding-this-link-and-that-mailbox stands, under a verb not named "refresh". And it
 * would buy nothing where it is wanted: a duplicate is refused ABOVE the mail ceilings, so the
 * honest cases — a double submit, a "did you get it?", two admins — all sit inside
 * {@code app.invites.recipient-cooldown-minutes} (default 60), where replacing would have sent
 * mail and therefore earned a 429 instead. The two only differ OUTSIDE that window, which is
 * "I have changed my mind about the role" and "please resend" — and both are already served by
 * withdraw-then-invite: two clicks, on the screen beneath this very form, with the revocation
 * recorded and metered.
 *
 * <p><strong>Two wordings, chosen from the blocking row's expiry, and the lapsed one exists
 * because an expired invitation does block.</strong> That is not a choice: a partial index
 * predicate must be {@code IMMUTABLE} and {@code now()} is not, so {@code accepted_at IS NULL} is
 * the only enforceable form (V22). It is also the rule this schema already applies to labels,
 * components, versions and sprints — a dead row keeps its slot until somebody clears it. What
 * that costs is discharged by naming the clearing: <strong>a refusal may only prescribe an action
 * its reader can perform</strong>, and this reader can. They hold {@code workspace.member.manage}
 * (they proved it to reach this line), withdrawal accepts expired rows, and the blocking row is
 * listed by {@code findUnacceptedForWorkspace}, whose predicate is the index predicate.
 *
 * <p><strong>The address is echoed and the blocking row's role is not.</strong> The address is one
 * the caller just submitted, so it discloses nothing; the role can be {@code null} on a degraded
 * row ({@code resolveRoleOrDegrade}), the list beside the form already renders it, and a refusal
 * should not grow a field whose absent case needs a second sentence.
 *
 * <p><strong>Nothing here is a cross-tenant disclosure, and the contrast with the neighbouring
 * 429 is the reason to say so.</strong> Uniqueness is scoped to one workspace, so this sentence
 * reports only a row the caller can already read in full through
 * {@code GET /api/workspaces/{id}/invites}. HD-190's daily cap is the opposite case — its refusal
 * necessarily carries one bit about workspaces the caller cannot see — so do not carry that
 * argument across in either direction.
 */
public class DuplicateInviteException extends AppException {

    /** @see #getErrorType() */
    public static final String DUPLICATE_INVITE = "DUPLICATE_INVITE";

    private DuplicateInviteException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    /** The blocking invitation has not expired yet — its link still works. */
    public static DuplicateInviteException live(String email) {
        return new DuplicateInviteException(
                "There is already an invitation to " + email + " waiting in this workspace. Ask "
                + "them to check their inbox, including spam. To change the role or send a fresh "
                + "link, withdraw it under Workspace settings → People and invite again.");
    }

    /**
     * The blocking invitation has lapsed. It still holds the slot (see the class javadoc), so the
     * only thing this sentence may do is point at the one control that frees it.
     */
    public static DuplicateInviteException lapsed(String email) {
        return new DuplicateInviteException(
                "An earlier invitation to " + email + " in this workspace has lapsed and is still "
                + "on file. Withdraw it under Workspace settings → People, then invite again.");
    }

    /** @see RoleInUseException#getErrorType() */
    public String getErrorType() {
        return DUPLICATE_INVITE;
    }
}
