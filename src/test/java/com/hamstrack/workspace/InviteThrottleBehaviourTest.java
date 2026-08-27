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
 * which a per-workspace cap and HD-133's {@code UNIQUE(workspace_id, email)} both inspect the wrong
 * dimension of, because each workspace holds exactly one invitation. Only a key that ignores the
 * workspace sees it.
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
     * holds one invitation, a cap of 500 is never approached, and a
     * {@code UNIQUE(workspace_id, email)} is satisfied every time.
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
     * The cooldown's message makes a claim about a row, so it is checked against the row
     * (section 8.3). "That invitation is still valid — ask them to check their inbox" is useful and
     * true while the invitation exists, and stale the moment it does not: declining deletes it, and
     * so does removing the invitee as a member ({@code deleteUnacceptedByWorkspaceAndEmail}), which
     * is the workflow — remove, realise the mistake, re-invite — that lands inside the cooldown.
     *
     * <p>Both halves are asserted, because a message that never makes the claim would pass the
     * second half alone and lose the sentence that makes the refusal actionable.
     */
    @Test
    void theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs() throws Exception {
        var sender = user();
        var workspace = workspaceOwnedBy(sender);
        var invitee = address("waiting");

        invite(sender, workspace, invitee).andExpect(status().isCreated());

        assertThat(detailOf(invite(sender, workspace, invitee)))
                .as("the invitation is still sitting in the invitee's inbox, so telling them to "
                    + "look is the one genuinely actionable thing this refusal can offer")
                .contains("still valid");

        var declined = user(invitee);
        workspaceService.declineInvite(declined, onlyInviteIdFor(invitee));

        assertThat(detailOf(invite(sender, workspace, invitee)))
                .as("the row is gone, so the claim is false. A refusal that lies about its own "
                    + "remedy is how a retryable 429 gets read as a wall — and this is the one "
                    + "place in the feature where a refusal can go stale, which is why the "
                    + "sentence is behind a count(*) instead of an assumption")
                .doesNotContain("still valid");
    }

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
        // Ceiling A: the rest of the hourly budget, spent on addresses nobody else touches.
        for (int i = 1; i < SENDER_HOURLY_DEFAULT; i++) {
            invite(stranger, own, address("filler-" + i)).andExpect(status().isCreated());
        }

        // The premise. In a workspace they DO belong to this caller is refused; without it the
        // 404s below could be a broken endpoint refusing everyone.
        invite(stranger, own, victim).andExpect(status().isTooManyRequests());

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

        // Waited for rather than assumed. The invite mail is dispatched @Async — which is
        // load-bearing, because the throttle holds a per-recipient advisory lock to commit and a
        // synchronous SMTP round trip would turn that into a wait one tenant can impose on another
        // — so the send lands on another thread some time after the 201. Letting it land BEFORE
        // clearing the mock is what makes the assertion below a fact rather than a race won.
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
        // 429 is thrown above sendWorkspaceInviteEmail, which is the last statement of
        // inviteMember, and the rolled-back transaction takes the invite row with it.
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
