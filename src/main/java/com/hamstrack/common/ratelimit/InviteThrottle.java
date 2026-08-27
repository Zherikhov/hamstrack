package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one line an invitation call site writes (HD-190 §4.2), and the guarantee that the two halves
 * are spent in the right order.
 *
 * <h2>Order, and why it is a property rather than a preference</h2>
 * The <strong>sender</strong> budget first: it is in memory, costs nothing, and refusing there
 * avoids taking a row-level advisory lock and running a statement on behalf of a caller who is
 * already over their own allowance. The <strong>recipient</strong> ceilings second, because they
 * are the expensive and the exact ones. A sender-budget refusal therefore never consumes a
 * <em>victim's</em> daily allowance — which would let an attacker burn a stranger's quota with
 * requests that send no mail.
 *
 * <h2>Where this must be called from</h2>
 * <strong>After tenancy and authorization, never before.</strong> The order inside
 * {@code WorkspaceService.inviteMember} is: workspace resolved (404 for a non-member and for an
 * unknown workspace alike) → {@code workspace.member.manage} (403) → role assignable / OWNER guard /
 * grant ceiling (422, 403) → already-a-member (409) → <em>this</em> (429) → write the invite and
 * send. Refusals before this one cost nothing, so a caller can exhaust neither their own budget nor
 * a victim's cap with requests that never reach this line. A refusal <em>from</em> this line is not
 * free in the same way and should not be described as if it were: the recipient half runs second,
 * so by the time it refuses, one unit of the caller's own sender budget is already spent and no
 * rollback returns it. That cost is self-inflicted and keyed on the caller; the one that would
 * matter is a victim's, and the order above is what keeps it unspent.
 *
 * <p><strong>And every new refusal on this path belongs BEFORE this line, not after it.</strong>
 * The recipient half writes its {@code mail_send_events} row here, in this transaction; anything
 * that rolls the transaction back afterwards — a constraint violation, a late 409, a new validation
 * — unwrites that row and hands callers a free way to observe the ceilings without ever spending
 * them. The inverse is worse and travels with it: the invitation mail is dispatched
 * {@code @Async} and is not ordered after the commit, so the same rollback can leave the message
 * SENT with its ceiling UNSPENT and its invite row absent. HD-133's
 * {@code UNIQUE(workspace_id, email)} is the concrete instance waiting to happen, and the note is
 * repeated at the call site, in fuller form, because that is the file it will edit.
 *
 * <p>These ceilings are deliberately not a {@link PrincipalThrottleInterceptor}: a recipient-keyed
 * refusal spent before the controller resolves anything would answer a cross-tenant question to a
 * non-member — a 429 where this project requires a 404. See {@link RecipientMailThrottle}.
 */
@Service
@RequiredArgsConstructor
public class InviteThrottle {

    private final InviteSenderVolumeBudget senderBudget;
    private final RecipientMailThrottle recipientThrottle;

    /**
     * @param senderUserId     the inviting principal
     * @param recipientEmail   the invited address as submitted, lower-cased at the boundary. It is
     *                         stored verbatim and echoed by the refusal; what the ceilings
     *                         <em>count</em> is the inbox key {@link RecipientMailThrottle} derives
     *                         from it, so a call site cannot key them on a spelling by accident
     * @param workspaceId      forensic breadcrumb on the recorded event; written, never queried
     * @param cooldownAddendum an extra sentence for the cooldown refusal, evaluated <strong>only if
     *                         that refusal happens</strong>. It exists because the natural sentence
     *                         there — "that invitation is still valid, ask them to check their
     *                         inbox" — is a claim about a row, and member removal deletes exactly
     *                         that row ({@code deleteUnacceptedByWorkspaceAndEmail}). A claim about
     *                         a row is checked against the row, or it goes stale in the one place a
     *                         user is already annoyed. The caller checks a row <em>in the workspace
     *                         being invited into</em>: this cooldown ignores workspaces, so it can
     *                         fire on a send from one the caller has since been removed from, and
     *                         describing that row's present state would report a membership event
     *                         they no longer have access to
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void require(UUID senderUserId, String recipientEmail, UUID workspaceId,
                        Supplier<String> cooldownAddendum) {
        senderBudget.require(senderUserId);
        recipientThrottle.requireAndRecord(EmailType.INVITE, recipientEmail, senderUserId,
                workspaceId, cooldownAddendum);
    }
}
