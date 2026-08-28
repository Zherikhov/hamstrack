package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code INVITE_ALREADY_ACCEPTED}</strong> — the invitation being withdrawn was
 * accepted (HD-158 §4.4b).
 *
 * <p>Reachable only as a race: the invitee accepts between the list rendering and the click, or
 * between the click and the row lock. The row exists, it belongs to this workspace, and it is not
 * withdrawable — withdrawal deletes an <em>offer</em> and this one has already been taken up.
 *
 * <p><strong>This is the one refusal on that endpoint that names a remedy, and the rule it obeys
 * is that a refusal may only prescribe an action its reader can perform.</strong> Here they can:
 * "this person now has access" is exactly the problem withdrawal was aimed at, and removing a
 * member needs {@code workspace.member.manage} — the permission the caller proved moments ago to
 * reach this at all. (Removal has guards of its own — the grant ceiling, last-Owner, stranded
 * projects — each with its own actionable message; this 409 promises a door, not that nothing lies
 * behind it. An invitation can never have carried the built-in Owner
 * ({@link OwnerIsNotGrantableException}), so the common case is clean.)
 *
 * <p><strong>Why not the 404 the already-withdrawn case gets.</strong> That state is physically
 * identical to "never existed"; this one is not. Answering 404 here would turn a real, actionable
 * state into a phantom and leave the administrator with no direction — and the client treats 404
 * from this endpoint as success, so the refusal would be rendered as a withdrawal that never
 * happened. The two states get different codes precisely because the client must branch on them
 * differently, which is what {@code errorType} is for.
 *
 * <p>The member is named rather than described. The address is one the caller submitted and can
 * already read from the list they clicked; the display name is the same one the roster beside it
 * shows.
 */
public class InviteAlreadyAcceptedException extends AppException {

    /** @see #getErrorType() */
    public static final String INVITE_ALREADY_ACCEPTED = "INVITE_ALREADY_ACCEPTED";

    public InviteAlreadyAcceptedException(String memberName) {
        super("That invitation was accepted — " + memberName + " is now a member of this "
              + "workspace. Withdrawing it would change nothing. Remove them from People if that "
              + "was not intended.", HttpStatus.CONFLICT);
    }

    /** @see RoleInUseException#getErrorType() */
    public String getErrorType() {
        return INVITE_ALREADY_ACCEPTED;
    }
}
