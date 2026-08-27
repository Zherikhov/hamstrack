package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The two ceilings an invitation call site spends (HD-190 §4.2), and the guarantee that they are
 * spent in the right order.
 *
 * <h2>Order, and why it is a property rather than a preference</h2>
 * The <strong>sender</strong> budget first: it is in memory, costs nothing, and refusing there
 * avoids taking a row-level advisory lock and running a statement on behalf of a caller who is
 * already over their own allowance. The <strong>recipient</strong> ceilings second, because they
 * are the expensive and the exact ones. A sender-budget refusal therefore never consumes a
 * <em>victim's</em> daily allowance — which would let an attacker burn a stranger's quota with
 * requests that send no mail.
 *
 * <h2>TWO METHODS AND NOT ONE, AND THAT IS LOAD-BEARING (HD-133)</h2>
 * This used to be a single {@code require} that spent both halves. It cannot be, because the two
 * halves obey <em>opposite</em> placement rules relative to a refusal that can roll the
 * transaction back:
 * <ul>
 *   <li>{@link #requireSenderVolume} is an <strong>in-memory</strong> counter (ADR-0015). Nothing
 *       gives it back — not a rollback, not a refusal raised after it — so spending it
 *       <em>above</em> a check that may refuse costs the caller a unit for a request that was
 *       going to fail. That is the point: it is what makes a refusal cost the caller something,
 *       and this endpoint has no {@link PrincipalThrottleInterceptor} to charge them instead.</li>
 *   <li>{@link #requireRecipientCeilings} <strong>writes a row</strong> in this transaction. A
 *       rollback afterwards unwrites it while the caller has already observed the refusal, which
 *       is a free way to probe a stranger's ceilings. So it goes <em>below</em> every check that
 *       can refuse.</li>
 * </ul>
 * Recombining them forces one of those two rules to lose. It has already happened once: when
 * HD-133's duplicate check landed above the combined {@code require}, a repeat POST to a pending
 * address stopped costing the caller anything at all, and an account holding
 * {@code workspace.member.manage} could loop unbounded cheap 409s. The split restores "every
 * refusal costs the caller something" without moving the recorded event above anything.
 *
 * <h2>Where these must be called from</h2>
 * <strong>Both halves after tenancy and authorization, never before.</strong> The order inside
 * {@code WorkspaceService.inviteMember} is: workspace resolved (404 for a non-member and for an
 * unknown workspace alike) → {@code workspace.member.manage} (403) → role assignable / OWNER guard /
 * grant ceiling (422, 403) → already-a-member (409) → <em>{@link #requireSenderVolume}</em> (429) →
 * a pending invitation to this address (409) → <em>{@link #requireRecipientCeilings}</em> (429) →
 * write the invite and send. Refusals above the sender budget cost nothing, so a caller can exhaust
 * neither their own budget nor a victim's cap with requests that never reach it. Refusals below it
 * are not free in the same way and should not be described as if they were: one unit of the
 * caller's own sender volume is already spent by then and no rollback returns it. That cost is
 * self-inflicted, keyed on the caller and ages out with the caller's own window; the one that would
 * matter is a victim's, and the order above is what keeps it unspent.
 *
 * <p><strong>And every new refusal on this path belongs BEFORE the recipient half, not after
 * it.</strong> That half writes its {@code mail_send_events} row in this transaction; anything that
 * rolls the transaction back afterwards — a constraint violation, a late 409, a new validation —
 * unwrites that row and hands callers a free way to observe the ceilings without ever spending
 * them. The inverse used to travel with it and was worse — the invitation mail was dispatched
 * {@code @Async} from inside the transaction, so the same rollback could leave the message SENT
 * with its ceiling UNSPENT and its invite row absent. HD-181 removed that half: the send is
 * registered on {@code AfterCommit} and a rollback delivers nothing. The free probe above is
 * untouched and is still the reason for the ordering rule. HD-133 was the concrete instance: its
 * partial unique index on {@code workspace_invites} can fail an insert made after this ran, so its
 * duplicate check sits ABOVE {@link #requireRecipientCeilings}. That is where any future refusal
 * goes too, and the note is repeated at the call site in fuller form, because that is the file it
 * will edit. <strong>A new refusal placed there is also the reason the sender budget is spent
 * higher up</strong> — the rule "everything that can refuse goes above the recorded event" would
 * otherwise keep making refusals cheaper as the list of them grows.
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
     * <strong>Half one — the caller's own volume, and the half that must be spent ABOVE every
     * refusal on the path rather than below them</strong> (HD-133 round 2).
     *
     * <p>It is an in-memory hourly/daily counter keyed on the sender (ADR-0015), so it is not
     * transactional and a rollback does not return it. That is precisely what makes it safe here
     * and unsafe below: the probe hazard the ordering rule at the top of this class exists for is
     * about <em>transactional</em> state a rollback can unwrite, and there is none of that here.
     * Spending it above a refusal charges the caller for a request that failed, which is the whole
     * of the intent — {@code POST /workspaces/{id}/invites} carries no
     * {@link PrincipalThrottleInterceptor}, so this counter is the only thing standing between an
     * account with {@code workspace.member.manage} and an unbounded loop of cheap refusals.
     *
     * <p>Deliberately not {@code @Transactional}: it touches no database. Do not add
     * {@code MANDATORY} for symmetry with the other half — that annotation is there to guarantee
     * the recipient advisory lock is held to commit, and it means nothing for a map.
     *
     * @param senderUserId the inviting principal; {@code null} is treated as unthrottled by
     *                     {@link InviteSenderVolumeBudget} and cannot occur on an authenticated path
     */
    public void requireSenderVolume(UUID senderUserId) {
        senderBudget.require(senderUserId);
    }

    /**
     * <strong>Half two — the recipient's ceilings, which RECORD, and therefore go below everything
     * that can refuse this request.</strong> See the class javadoc for why the two halves are
     * separate methods and what recombining them costs.
     *
     * @param senderUserId     the inviting principal
     * @param recipientEmail   the invited address as submitted, lower-cased at the boundary. It is
     *                         stored verbatim and echoed by the refusal; what the ceilings
     *                         <em>count</em> is the inbox key {@link RecipientMailThrottle} derives
     *                         from it, so a call site cannot key them on a spelling by accident
     * @param workspaceId      forensic breadcrumb on the recorded event; written, never queried
     * @param cooldownAddendum an extra sentence for the cooldown refusal, evaluated <strong>only if
     *                         that refusal happens</strong>, and <strong>{@code null} from every
     *                         caller in this build</strong> — see the no-addendum overload below
     *                         for why, and use it rather than passing {@code null} here. The
     *                         mechanism exists because the natural sentence there — "that
     *                         invitation is still valid, ask them to check their inbox" — is a
     *                         CLAIM ABOUT A ROW, and rows are deleted by paths the refusal knows
     *                         nothing about ({@code deleteUnacceptedByWorkspaceAndEmail}, HD-132).
     *                         A claim about a row is checked against the row, or it goes stale in
     *                         the one place a user is already annoyed. And it must be checked
     *                         against a row the reader may see: this cooldown ignores workspaces,
     *                         so it can fire on a send from one the caller has since been removed
     *                         from, and describing that row's present state would report a
     *                         membership event they no longer have access to. Both rules bind any
     *                         future supplier
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRecipientCeilings(UUID senderUserId, String recipientEmail, UUID workspaceId,
                                         Supplier<String> cooldownAddendum) {
        recipientThrottle.requireAndRecord(EmailType.INVITE, recipientEmail, senderUserId,
                workspaceId, cooldownAddendum);
    }

    /**
     * <strong>Overload for a path with no row to make a claim about</strong> — mirrors
     * {@code RecipientMailThrottle.requireAndRecord}'s own, and is what {@code inviteMember} calls.
     *
     * <p>Every invite path uses this one today, and the reason is a consequence of HD-133 rather
     * than an omission. The addendum's condition (a live unaccepted invitation to this address in
     * this workspace) is a strict SUBSET of what the duplicate refusal now rejects one step
     * earlier (unaccepted, expiry irrelevant), so nothing can reach the cooldown with such a row
     * standing. The sentence became unreachable and was removed; the mechanism did not, because it
     * is sound and because the condition under which an addendum becomes reachable again is
     * nameable: <em>any narrowing of the duplicate refusal</em>, and HD-202's own flows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRecipientCeilings(UUID senderUserId, String recipientEmail, UUID workspaceId) {
        requireRecipientCeilings(senderUserId, recipientEmail, workspaceId, null);
    }
}
