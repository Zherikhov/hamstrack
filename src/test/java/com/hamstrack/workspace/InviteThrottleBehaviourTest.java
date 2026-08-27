package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The invitation mailer is no longer an open relay</strong> (HD-190,
 * {@code docs/design/invite-budget-proposal.md} section 15).
 *
 * <p>Before this feature, any account that could log in could make Hamstrack send mail to any
 * address on the internet, without limit, by typing that address into the invite box of a workspace
 * it had created seconds earlier. In Cloud that account costs one throwaway mailbox — public signup
 * is on, nothing bounds how many workspaces one user creates — and the mail carries our SPF/DKIM, so
 * a stranger's spam complaints land on the same sending reputation that carries our verification
 * mail. The first symptom is that new users stop receiving their verification links: signup breaks
 * silently, from a cause with no error in it.
 *
 * <p>This file drives the real endpoint. Its centre is
 * {@link #theTicketsAttackOneVictimManyWorkspacesIsRefused()} — the shape the ticket describes,
 * which a per-workspace cap and HD-133's {@code workspace_invites_pending_email_uk} both inspect
 * the wrong dimension of, because each workspace holds exactly one invitation. Only a key that
 * ignores the workspace sees it. That constraint has since shipped (V22, a PARTIAL unique index on
 * {@code (workspace_id, lower(email)) WHERE accepted_at IS NULL}) and it stops none of this: every
 * send in the attack is in a different workspace and therefore a different index key. The two are
 * complements, never substitutes.
 *
 * <p><strong>What HD-133 took out of this file.</strong> The cooldown's optional addendum —
 * <em>"That invitation is still valid — ask them to check their inbox, including spam."</em> — is
 * gone, along with the repository finder behind it and the test that pinned it. Its condition (this
 * workspace, this address, unaccepted, unexpired) is a strict SUBSET of the duplicate refusal's
 * (this workspace, {@code lower()} address, unaccepted), so no request can reach the cooldown with
 * such a row standing: the sentence became unreachable through the API and a sentence that cannot
 * be emitted is a claim a future reader will trust. The property that replaced it —
 * <em>a second invitation to a pending address is refused before these ceilings are consulted</em>
 * — is {@code DuplicateInviteRefusalTest}, which is also where every duplicate-of-an-address case
 * now lives. This file keeps the ceilings, and each of its cases therefore uses either a fresh
 * address or a fresh workspace.
 *
 * <p><strong>The tenancy property is the one to read first.</strong>
 * {@link #aNonMemberOverEveryCeilingGets404AndNever429()} asserts the opposite of what reports and
 * search do: their 429 precedes the 404, so it says nothing about whether the caller can see the
 * resource. Here the 429 is spent <em>inside the service, after the workspace is resolved</em>, so
 * it is only ever seen by a proven member — and it has to be, because a recipient-keyed refusal
 * spent any earlier would answer a non-member a question about invitation traffic elsewhere in the
 * instance. That ordering is held today by nothing but the order of statements in one method.
 *
 * <p><strong>Tokens are minted, not logged in for.</strong> {@code app.rate-limit.enabled} is
 * {@code true} here — it has to be, that is the feature — which also arms the per-IP auth budget at
 * 15/minute, and every MockMvc request comes from one address. Twenty logins would exhaust an
 * unrelated limiter and the failure would look like this one.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InviteThrottleBehaviourTest {

    /** {@code app.invites.max-per-sender-per-hour}'s shipped default, spelled out. */
    private static final int SENDER_HOURLY_DEFAULT = 20;

    /** {@code app.invites.max-per-recipient-per-day}'s shipped default. */
    private static final int RECIPIENT_DAILY_DEFAULT = 5;

    /**
     * Mail never leaves the process. Two reasons, and the second is the one that matters: CI has no
     * SMTP server, so a real attempt is a five-second connect timeout per invite on a test that
     * sends twenty; and a mock is the only way to assert that a <em>refused</em> invite sends
     * nothing at all.
     */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceService workspaceService;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    // ================================================================ the attack

    /**
     * <strong>The ticket's attack, and its test.</strong> One abuser presses "invite" at one victim
     * from a succession of workspaces they create. Workspace creation is free and unbounded, so
     * every per-workspace control is inspecting a dimension the attack does not use: each workspace
     * holds one invitation, a cap of 500 is never approached, and
     * {@code workspace_invites_pending_email_uk} — which is per {@code (workspace_id,
     * lower(email))} — is satisfied every time.
     *
     * <p>The refusal names the address — the caller's own past action, so it discloses nothing — and
     * <strong>never names a workspace</strong>, because the earlier invitation may have come from
     * one the caller can no longer see.
     */
    @Test
    void theTicketsAttackOneVictimManyWorkspacesIsRefused() throws Exception {
        var abuser = user();
        var first = workspaceOwnedBy(abuser);
        var second = workspaceOwnedBy(abuser);
        var victim = address("victim");

        invite(abuser, first, victim).andExpect(status().isCreated());

        invite(abuser, second, victim)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(victim)));

        var detail = detailOf(invite(abuser, workspaceOwnedBy(abuser), victim));
        assertThat(detail)
                .as("the cooldown ignores workspaces, so the earlier send may have come from one "
                    + "the caller can no longer see — naming it would report a membership event "
                    + "they no longer have access to")
                .doesNotContain(first.getName())
                .doesNotContain(second.getName())
                .doesNotContain(first.getId().toString())
                .doesNotContain(second.getId().toString());
    }

    /**
     * <strong>The victim's own "decline" must not unlock the attacker's next send.</strong> This is
     * the reason the cooldown's state is a separate append-only table rather than being derived from
     * {@code workspace_invites} (ADR-0015): {@code declineInvite} DELETEs the row, so a derived
     * cooldown would be reset by the exact action the product asks the victim to take — a throttle
     * the victim can clear by doing the thing it exists to protect them from, which is worse than
     * none because it reads as protection.
     */
    @Test
    void decliningTheInvitationDoesNotLiftTheCooldown() throws Exception {
        var abuser = user();
        var victimAddress = address("declines");
        var victim = user(victimAddress);
        var first = workspaceOwnedBy(abuser);

        invite(abuser, first, victimAddress).andExpect(status().isCreated());

        var inviteId = onlyInviteIdFor(victimAddress);
        workspaceService.declineInvite(victim, inviteId);
        assertThat(inviteRowsFor(victimAddress))
                .as("the premise, asserted rather than assumed: declining really does DELETE the "
                    + "row a derived cooldown would have been reading. If this ever stops being "
                    + "true, the assertion below quietly stops exercising the defect")
                .isZero();

        invite(abuser, workspaceOwnedBy(abuser), victimAddress)
                .andExpect(status().isTooManyRequests());
    }

    /**
     * <strong>The administrator's own withdrawal must not unlock their next send either</strong>
     * (HD-158 AC 10 and AC 11) — the headline of the ticket that made this file's premise a live
     * question again.
     *
     * <p>{@code V21}'s header named this exact hazard: <em>correctness that depends on the
     * continued ABSENCE of a delete endpoint breaks silently in a future ticket</em>.
     * {@code DELETE /api/workspaces/{ws}/invites/{id}} is that endpoint. If withdrawing refunded a
     * ceiling, {@code invite -> revoke -> invite} would defeat the whole control with two
     * legitimate calls, no exploit and one extra HTTP request per message — and it would do it
     * from a door the product hands to the sender, unlike the decline above, which at least
     * belongs to the victim.
     *
     * <p>Four assertions, and the middle two are the ones that would still be true of a broken
     * build if only the status were checked: the invitation row is really gone (so the refusal is
     * not coming from the row's continued existence), the {@code mail_send_events} row is still
     * there, and <strong>it still carries its original {@code created_at}</strong> — a refund
     * implemented as "touch the row forward" would leave the count intact and reset the cooldown
     * anyway. The rule, stated as a property because a list of deletion paths goes stale one path
     * before it does: <em>a revocation may free stock, never flow. Deleting the record of an offer
     * does not delete the record of a delivery.</em>
     *
     * <p>The last assertion is AC 11 and it is about wording, not about a ceiling: the refusal
     * keeps refusing but does not tell the admin to go and look for an invitation that no longer
     * exists.
     *
     * <p><strong>What holds that has changed, and the old reason is no longer true.</strong> It
     * used to be a supplier at {@code inviteMember} that asked the table whether a live row was
     * there, so the sentence suppressed itself. HD-133 removed the sentence outright: its condition
     * is a strict subset of the duplicate refusal's, so it could no longer be reached through the
     * API at all, and a claim that cannot be emitted is one a reader will still trust. So this
     * assertion is now a guard rather than a probe — it is what fails if a future path revives an
     * addendum without also reviving the check that a claim about a row is made only while the row
     * is there. Kept for that, and because the sequence it drives (withdraw, then re-invite the
     * same address in the same workspace) is the one place in this file where the duplicate check
     * runs and finds nothing: the withdrawal really did free the uniqueness slot, so what answers
     * is still the ceiling.
     */
    @Test
    void withdrawingTheInvitationDoesNotLiftTheCooldownAndTheEventRowKeepsItsTimestamp()
            throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var mistyped = address("withdrawn");

        invite(admin, workspace, mistyped).andExpect(status().isCreated());
        var eventsAfterSend = eventRowsFor(mistyped);
        var sentAt = firstEventInstantFor(mistyped);

        mockMvc.perform(delete("/api/workspaces/" + workspace.getId() + "/invites/"
                               + onlyInviteIdFor(mistyped))
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(admin)))
                .andExpect(status().isNoContent());

        assertThat(inviteRowsFor(mistyped))
                .as("the premise: withdrawing really does DELETE the row. If it ever stops "
                    + "deleting, the assertions below stop exercising the defect")
                .isZero();
        assertThat(eventRowsFor(mistyped))
                .as("the message is already in the mailbox and cannot be unsent, so the record of "
                    + "the delivery survives the deletion of the offer. A refund here would also "
                    + "be a cross-tenant write: the daily cap counts one INBOX instance-wide, so "
                    + "this workspace would be deciding how much mail another may send a stranger")
                .isEqualTo(eventsAfterSend);
        assertThat(firstEventInstantFor(mistyped))
                .as("same row, same timestamp: a 'refund' written as touching created_at forward "
                    + "would leave every count in this file intact and reset the cooldown anyway")
                .isEqualTo(sentAt);

        var refusal = invite(admin, workspace, mistyped)
                .andExpect(status().isTooManyRequests());
        assertThat(refusal.andReturn().getResponse().getStatus())
                .as("the withdrawal freed the UNIQUENESS slot, so the duplicate check finds "
                    + "nothing and the request reaches the ceilings — which is what makes the "
                    + "429 above a statement about the cooldown rather than about the row. A "
                    + "409 here would mean withdrawal had stopped deleting")
                .isEqualTo(429);
        assertThat(detailOf(refusal))
                .as("AC 11: the ceiling still bites, and it does not send the admin looking for an "
                    + "invitation they themselves withdrew — a refusal prescribing an action its "
                    + "reader cannot perform. Since HD-133 no invite refusal can say this at all "
                    + "(the addendum was removed as unreachable), so this is the guard that a "
                    + "revived supplier does not bring back the claim without the check")
                .doesNotContain("still valid");
    }

    /**
     * <strong>And withdrawing N invitations does not restore N slots of the recipient's daily
     * ceiling</strong> (HD-158 AC 10, second half).
     *
     * <p>The cooldown above is per (sender, inbox); this is the instance-wide per-inbox cap, and it
     * is the half a refund would break for somebody who is not even a party to the withdrawal.
     * Five distinct senders each invite one victim and each immediately withdraw, leaving the
     * table with <strong>no invitation rows at all</strong> — the state a derived ceiling would
     * read as "nobody has ever mailed this person". The sixth sender is still refused.
     */
    @Test
    void withdrawingEveryInvitationDoesNotRestoreTheRecipientsDailySlots() throws Exception {
        var victim = address("daily-cap");

        for (int i = 0; i < RECIPIENT_DAILY_DEFAULT; i++) {
            var sender = user();
            var workspace = workspaceOwnedBy(sender);
            invite(sender, workspace, victim).andExpect(status().isCreated());
            mockMvc.perform(delete("/api/workspaces/" + workspace.getId() + "/invites/"
                                   + onlyInviteIdFor(victim))
                            .header("Authorization",
                                    "Bearer " + jwtService.generateAccessToken(sender)))
                    .andExpect(status().isNoContent());
        }

        assertThat(inviteRowsFor(victim))
                .as("every invitation to this address has been withdrawn — which is precisely the "
                    + "state a ceiling derived from workspace_invites would read as 'never mailed'")
                .isZero();
        assertThat(eventRowsFor(victim)).isEqualTo(RECIPIENT_DAILY_DEFAULT);

        var newcomer = user();
        invite(newcomer, workspaceOwnedBy(newcomer), victim)
                .andExpect(status().isTooManyRequests());
    }

    /*
     * REMOVED BY HD-133, AND THE REPLACEMENT IS IN ANOTHER FILE.
     *
     * theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs asserted that a second
     * invitation to a pending address in the SAME workspace produced a 429 whose detail contained
     * "still valid", and that declining removed the sentence. The first half is now a 409: the
     * duplicate refusal's matching set (this workspace, lower(email), unaccepted, expiry
     * irrelevant) is a strict SUPERSET of the addendum's (this workspace, this address,
     * unaccepted, unexpired), so every request that could have produced that sentence is refused
     * one step earlier by one that says the same thing better and names a remedy its reader can
     * perform. The supplier and its finder were deleted rather than documented as dead.
     *
     * Re-baselining it to expect the 409 would have kept a test that no longer says anything: it
     * would assert a status without asserting the ORDER that status proves. The stronger property
     * — refused BEFORE the ceilings are consulted, recording no mail_send_events row — is
     * DuplicateInviteRefusalTest, together with the cross-sender case where answering with a
     * ceiling would be a free probe of a stranger's daily cap.
     */

    // ================================================================ tenancy

    /**
     * <strong>404 before 429, even for a caller who is over every ceiling</strong> (acceptance
     * criterion 9) — the tenancy-critical property of the whole feature, and the reason these
     * ceilings could not be a {@code PrincipalThrottleInterceptor}.
     *
     * <p>{@code PerPrincipalMinuteBudget} is spent before the controller resolves anything, and its
     * javadoc argues that this is safe precisely because the key is the caller: "the 429 is
     * identical for a real workspace, a nonexistent one and somebody else's". A
     * <strong>recipient</strong>-keyed refusal does not have that property. Spent early it would
     * answer, to a caller who is not a member of the workspace in the path, a question about
     * invitation traffic elsewhere in the instance — and would do it while the tenancy contract
     * requires 404.
     *
     * <p>The caller here is genuinely over all three: the hourly sender budget is exhausted, the
     * address is inside their own cooldown, and it has taken its whole daily allowance from other
     * senders. Every one of those would produce a 429 in their own workspace. Against a workspace
     * they cannot see, and against one that does not exist, both must be an indistinguishable 404.
     *
     * <p><strong>Each ceiling is demonstrated where it is the one that can answer, which HD-133
     * made a distinction that matters.</strong> The recipient half is now unreachable in a
     * workspace where the address is already pending — the duplicate 409 pre-empts it — so the
     * cooldown is exercised in a second workspace of the caller's own, where the address is free
     * and the only thing left to refuse is the ceiling. Asserting it in a workspace holding the
     * row would still be a 429 today (the sender budget answers first once it is full), and would
     * be a 429 that says nothing about the ceilings this file is named after.
     */
    @Test
    void aNonMemberOverEveryCeilingGets404AndNever429() throws Exception {
        var stranger = user();
        var own = workspaceOwnedBy(stranger);
        var victim = address("over-every-ceiling");

        // Ceilings B and C, on one address: the stranger's own send (which arms their cooldown)
        // plus enough distinct other senders to fill the global daily cap. The cap counts the
        // caller's own sends one each and every OTHER sender once, so the stranger's own send is
        // one of the five slots and four strangers fill the rest — the sixth distinct account is
        // the one that trips it, which is section 6.3's arithmetic exactly.
        invite(stranger, own, victim).andExpect(status().isCreated());
        for (int i = 1; i < RECIPIENT_DAILY_DEFAULT; i++) {
            var otherSender = user();
            invite(otherSender, workspaceOwnedBy(otherSender), victim)
                    .andExpect(status().isCreated());
        }

        // Premise 1, the RECIPIENT ceilings, in a second workspace of the caller's own — where
        // this address has never been invited, so nothing but the cooldown and the daily cap can
        // refuse it. (In `own` the address is pending, and HD-133 answers 409 before the ceilings
        // are consulted at all.) This refused request still costs a unit of sender volume, which
        // the arithmetic below accounts for.
        invite(stranger, workspaceOwnedBy(stranger), victim)
                .andExpect(status().isTooManyRequests());

        // Ceiling A: the rest of the hourly budget, spent on addresses nobody else touches. Two
        // units are already gone — the accepted send and the refused one above.
        for (int i = 2; i < SENDER_HOURLY_DEFAULT; i++) {
            invite(stranger, own, address("filler-" + i)).andExpect(status().isCreated());
        }

        // Premise 2, the SENDER budget, on an address that is free everywhere — so this 429 can
        // only be the caller's own volume. Without both premises the 404s below could be a broken
        // endpoint refusing everyone.
        invite(stranger, own, address("one-too-many")).andExpect(status().isTooManyRequests());

        // A caller who is not a member must not learn, from a 429, that this address has been
        // invited elsewhere in the instance — nor that this workspace exists. Non-existence and
        // non-membership are one answer: 404.
        var somebodyElse = user();
        invite(stranger, workspaceOwnedBy(somebodyElse), victim)
                .andExpect(status().isNotFound());

        mockMvc.perform(inviteRequest(stranger, UUID.randomUUID(), victim))
                .andExpect(status().isNotFound());
    }

    // ================================================================ what a refusal costs

    /**
     * <strong>A refusal writes nothing</strong> (acceptance criterion 8): no {@code workspace_invites}
     * row, no {@code mail_send_events} row, no mail.
     *
     * <p>That is what makes a probe free — which is the property that made the daily cap's
     * {@code Retry-After} leak reachable in the first place, and it is deliberate: a refusal that
     * spent the ceiling it just refused would let an attacker exhaust a <em>victim's</em> allowance
     * with requests that never send mail. Under-counting is the safe side to err on for a control
     * whose ceilings sit well above honest use.
     *
     * <p><strong>The two halves are held by different mechanisms, and this was measured rather than
     * assumed.</strong> The row half is held by <em>transaction rollback</em>: moving
     * {@code record(...)} above the ceiling checks leaves this test green, because the refusal
     * unwinds the transaction and takes the row with it. What the ORDERING actually buys is the mail
     * — {@code sendWorkspaceInviteEmail} is {@code @Async}, so a send dispatched before the refusal
     * is <em>gone</em>, rollback or not, and moving the throttle below it turns this test red on
     * {@code verifyNoInteractions}. Worth knowing in both directions: a future refusal added on this
     * path cannot leak a row, and a future mail dispatch moved above the throttle absolutely can.
     */
    @Test
    void aRefusedInviteWritesNothingAndSendsNothing() throws Exception {
        var sender = user();
        var invitee = address("refused");

        invite(sender, workspaceOwnedBy(sender), invitee).andExpect(status().isCreated());
        var invitesAfterFirst = inviteRowsFor(invitee);
        var eventsAfterFirst = eventRowsFor(invitee);

        // Waited for rather than assumed: the send is registered on AfterCommit and dispatched
        // @Async, so it lands on another thread some time after the 201. Letting it land BEFORE
        // clearing the mock is what makes the assertion below a fact rather than a race won.
        //
        // This comment used to say @Async was "load-bearing" for keeping an SMTP round trip out of
        // the per-recipient advisory lock. It never was, and HD-181 corrected it in the source:
        // mailExecutor is bounded with a caller-runs policy, so a full queue runs the send INLINE
        // on this thread — under exactly the load where a cross-tenant lock hold would hurt most.
        // What keeps the send outside the lock is the ORDERING (registered on the commit that
        // releases it), asserted in MailFollowsTheCommitTest against the lock itself.
        awaitOneMailAttempt();
        clearInvocations(mailSender);

        invite(sender, workspaceOwnedBy(sender), invitee)
                .andExpect(status().isTooManyRequests());

        assertThat(inviteRowsFor(invitee))
                .as("a refused invitation must not leave a pending invite behind")
                .isEqualTo(invitesAfterFirst);
        assertThat(eventRowsFor(invitee))
                .as("a refused send must not be counted as a send. If it were, an attacker could "
                    + "exhaust a stranger's daily cap with requests that send no mail — spending "
                    + "somebody else's allowance for free is the exact inversion this ceiling "
                    + "exists to prevent. It is also what makes a probe free, which is why the "
                    + "daily cap's Retry-After has to be coarsened at all")
                .isEqualTo(eventsAfterFirst);

        // Generous enough that a dispatched send would have landed. Nothing was dispatched: the
        // 429 is thrown above the AfterCommit.run that registers the send, and — since HD-181 —
        // even a refusal thrown BELOW it would deliver nothing, because the rollback the 429 takes
        // cancels the registered effect along with the invite row.
        Thread.sleep(300);
        verifyNoInteractions(mailSender);
    }

    // ================================================================ the sender volume budget

    /**
     * The per-sender volume budget at its shipped default of 20 an hour (acceptance criterion 1).
     *
     * <p>Its refusal has a different reader from the other two: an admin in the middle of
     * onboarding somebody. The only thing they can do is wait and continue, so the message names
     * both allowances and the wait — and deliberately does <strong>not</strong> say "ask an
     * administrator to raise the limit", because on Cloud the reader has no administrator to ask and
     * on DC they may not be the operator. The last sentence is the one that matters in practice: it
     * stops them re-sending the invitations that already went out.
     */
    @Test
    void thePerSenderHourlyBudgetRefusesWithAMessageItsReaderCanActOn() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);

        for (int i = 0; i < SENDER_HOURLY_DEFAULT; i++) {
            invite(admin, workspace, address("colleague-" + i)).andExpect(status().isCreated());
        }

        var refusal = invite(admin, workspace, address("one-too-many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(detailOf(refusal))
                .as("the reader is mid-onboarding: name both allowances so they can size the day, "
                    + "and tell them the invitations already sent are unaffected so they do not "
                    + "re-send them")
                .contains(String.valueOf(SENDER_HOURLY_DEFAULT))
                .contains("100")
                .contains("already sent are unaffected");
        assertThat(detailOf(refusal))
                .as("a refusal may only prescribe an action its reader can perform, and 'ask an "
                    + "administrator' is not one on Cloud")
                .doesNotContain("administrator");
    }

    // ================================================================ fixture

    /**
     * Blocks until the {@code mailExecutor} has actually attempted a send, so a later
     * {@code verifyNoInteractions} is about the request under test and not about a thread that had
     * not got there yet. Fails loudly rather than timing out silently: "no mail was ever sent" is
     * itself a finding, and one that would otherwise make the refusal assertion pass for the wrong
     * reason.
     */
    private void awaitOneMailAttempt() throws InterruptedException {
        for (int i = 0; i < 100 && mockingDetails(mailSender).getInvocations().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertThat(mockingDetails(mailSender).getInvocations())
                .as("the ACCEPTED invitation never reached the mail sender within 5s, so this test "
                    + "cannot tell 'the refusal sent nothing' from 'nothing sends anything'")
                .isNotEmpty();
    }

    private ResultActions invite(User sender, Workspace workspace, String email) throws Exception {
        return mockMvc.perform(inviteRequest(sender, workspace.getId(), email));
    }

    private org.springframework.test.web.servlet.RequestBuilder inviteRequest(
            User sender, UUID workspaceId, String email) {
        return post("/api/workspaces/" + workspaceId + "/invites")
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}");
    }

    private static String detailOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    /** Unique per test run, so tests never share a recipient key or a users row. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private long inviteRowsFor(String email) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(i) FROM WorkspaceInvite i WHERE i.email = :email", Long.class)
                .setParameter("email", email).getSingleResult());
    }

    /**
     * Counted through the recipient KEY, which is what the ceilings count — asserting on the
     * submitted address would pass while the row was written under a key nothing matches.
     */
    private long eventRowsFor(String email) {
        var key = com.hamstrack.common.mail.MailAddresses.throttleKey(email);
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(e) FROM MailSendEvent e WHERE e.recipientKey = :key", Long.class)
                .setParameter("key", key).getSingleResult());
    }

    /**
     * The oldest {@code mail_send_events} timestamp for this inbox — the value both ceilings
     * measure from. Asserted across a withdrawal because the row surviving is not enough on its
     * own: a refund written as "move created_at forward" leaves every count in this file intact
     * and hands the cooldown back anyway.
     */
    private java.time.Instant firstEventInstantFor(String email) {
        var key = com.hamstrack.common.mail.MailAddresses.throttleKey(email);
        return transactions.execute(status -> em.createQuery(
                        "SELECT min(e.createdAt) FROM MailSendEvent e WHERE e.recipientKey = :key",
                        java.time.Instant.class)
                .setParameter("key", key).getSingleResult());
    }

    private UUID onlyInviteIdFor(String email) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT i.id FROM WorkspaceInvite i WHERE i.email = :email", UUID.class)
                .setParameter("email", email).getSingleResult());
    }

    private User user() {
        return user(address("user"));
    }

    private User user(String email) {
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Invite budget");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Invite budget " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("ib-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(owner);
        var saved = workspaceRepository.save(w);
        var member = new WorkspaceMember();
        member.setWorkspace(saved);
        member.setUser(owner);
        member.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(member);
        return saved;
    }
}
