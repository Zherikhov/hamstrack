package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.util.TokenUtils;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>One standing offer per address per workspace</strong> (HD-133,
 * {@code docs/design/invite-uniqueness-proposal.md}).
 *
 * <p>Before this, a workspace could offer the same person access several times over and the offers
 * did not know about each other: two live links at MEMBER and ADMIN made the role a coin flip
 * decided by which one the invitee clicked, withdrawing "the" invitation was meaningless when there
 * were four, and the invitee's onboarding screen listed one workspace several times.
 *
 * <p><strong>The property this file is really about is an ORDER, and an order is not observable
 * from a status code alone.</strong> {@code inviteMember} runs
 * {@code requireSenderVolume} → duplicate check → {@code requireRecipientCeilings}, and every one
 * of those three positions was argued for:
 *
 * <ul>
 *   <li><strong>Above the recipient ceilings</strong>, because that half <em>records</em> a
 *       {@code mail_send_events} row inside this transaction. A refusal raised after it rolls the
 *       row back while the caller has already seen the refusal — invite a victim's address, read
 *       the answer, pay nothing, repeat, and you have mapped a stranger's mail ceilings. Two
 *       tests hold this, and neither of them is the row count on its own: <em>the rollback makes
 *       the count identical either way</em>. What distinguishes the orders is WHICH REFUSAL COMES
 *       BACK, so both tests put the caller in a state where the ceilings would refuse and then
 *       assert a 409.</li>
 *   <li><strong>Below the sender volume budget</strong>, which is
 *       {@link InviteDuplicateCostsTheSenderTest}'s subject: an in-memory counter no rollback
 *       returns, so it is spent above every refusal or each new refusal on this path becomes one
 *       more the caller can repeat for free.</li>
 *   <li><strong>Below the already-a-member check</strong>, because a person can be both a member
 *       and the addressee of a leftover row, and "they are already in this workspace" is the more
 *       useful answer to the more important question.</li>
 * </ul>
 *
 * <p><strong>The pre-check is the sentence; the index is the invariant.</strong> Two concurrent
 * requests both find the address free and both insert — {@code workspace_invites_pending_email_uk}
 * (V22) is what makes one of them fail, and {@link #aLostRaceAnswersTheSame409AndNeverA500()}
 * forces exactly that interleaving rather than hoping for it. The migration's own half — the two
 * cleanup steps and the shape of the index — is {@code V22InviteUniquenessMigrationTest}.
 *
 * <p><strong>Rate limiting is ON here and that is load-bearing rather than incidental.</strong>
 * With {@code app.rate-limit.enabled=false} the recipient ceilings never refuse, so "a 409 rather
 * than a 429" would prove nothing about ordering; the whole file would keep passing with the
 * duplicate check moved to the bottom of the method.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DuplicateInviteRefusalTest {

    /** {@code app.invites.max-per-recipient-per-day}'s shipped default. */
    private static final int RECIPIENT_DAILY_DEFAULT = 5;

    /** No SMTP in CI, and a real attempt is a five-second connect timeout per invitation. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    private final ObjectMapper json = new ObjectMapper();

    // ================================================================ the ordering rule

    /**
     * <strong>The refusal happens before the mail ceilings are consulted</strong> (AC 5 and AC 7) —
     * and this is the test that replaces
     * {@code InviteThrottleBehaviourTest.theCooldownOnlyClaimsTheEarlierInviteIsWaitingWhileItActuallyIs},
     * which asserted the cooldown's optional addendum for this exact call sequence. That sentence
     * ("that invitation is still valid — ask them to check their inbox") is now unreachable through
     * the API: its condition (same workspace, this address, unaccepted, unexpired) is a strict
     * subset of the 409's (same workspace, {@code lower()} address, unaccepted), so every request
     * that could produce it is refused one step earlier by a refusal that says the same thing
     * better and names a remedy its reader can perform. The supplier was removed rather than
     * documented as dead, and this method is the stronger property put in its place.
     *
     * <p><strong>Read the 409 as the assertion about ordering, not the row count.</strong> The
     * caller here is inside {@code app.invites.recipient-cooldown-minutes} (default 60) for this
     * exact inbox — they sent the first invitation seconds ago — so
     * {@code requireRecipientCeilings} <em>would</em> answer 429. It answers 409, which is only
     * possible if the duplicate check ran first. The row count is asserted too, and it is the
     * weaker half deliberately: a refusal raised below the throttle rolls the recorded row back, so
     * the count alone cannot tell the two orders apart. What it does catch is the day somebody
     * makes that append survive its own rollback — the {@code REQUIRES_NEW} the design considered
     * and rejected — at which point a free probe becomes a paid one and this count moves.
     */
    @Test
    void theSecondInvitationIsRefusedBeforeTheCeilingsAreConsultedAndRecordsNoSend() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("waiting");

        invite(admin, workspace, invitee).andExpect(status().isCreated());
        awaitOneMailAttempt();
        clearInvocations(mailSender);
        var eventsAfterTheSend = eventRowsFor(invitee);
        assertThat(eventsAfterTheSend)
                .as("the premise: the accepted invitation really did record a send, so the count "
                    + "below is a count that CAN move")
                .isEqualTo(1);

        var refusal = invite(admin, workspace, invitee).andExpect(status().isConflict());

        assertThat(errorTypeOf(refusal))
                .as("the SPA must be able to tell this 409 from the already-a-member one without "
                    + "parsing prose: it refreshes the pending list on this one, so the row the "
                    + "sentence names is on screen beside it")
                .isEqualTo("DUPLICATE_INVITE");
        assertThat(detailOf(refusal))
                .as("a refusal may only prescribe an action its reader can perform. They hold "
                    + "workspace.member.manage (they proved it to get this far), withdrawal "
                    + "accepts expired rows, and the list is on the same screen as the form — so "
                    + "the sentence names the address they submitted and where to clear it")
                .contains(invitee)
                .contains("withdraw")
                .contains("People");
        assertThat(refusal.andReturn().getResponse().getStatus())
                .as("""
                        THE ORDERING, AS AN OBSERVABLE. This caller sent the first invitation \
                        seconds ago, so they are inside the (sender, recipient) cooldown and \
                        requireRecipientCeilings WOULD refuse with 429. A 409 can only come back \
                        if the duplicate check ran above it. Move that check below the throttle \
                        and this line reads 429 — which is the free probe of a stranger's \
                        ceilings the order exists to prevent.""")
                .isEqualTo(409);
        assertThat(eventRowsFor(invitee))
                .as("no send was recorded for a message that was never sent. Weaker than the "
                    + "status assertion above ON PURPOSE — a refusal below the throttle would "
                    + "roll the row back and leave this count unchanged too — and it is here for "
                    + "the change that would make the append outlive its rollback")
                .isEqualTo(eventsAfterTheSend);
        assertThat(inviteRowsFor(invitee))
                .as("and the refused request left no second offer behind, which is the whole "
                    + "point: the role stops being a coin flip only if there is one row")
                .isEqualTo(1);
        Thread.sleep(300);
        verifyNoInteractions(mailSender);
    }

    /**
     * <strong>The same order, stated where it is a cross-tenant question rather than a
     * self-inflicted one.</strong>
     *
     * <p>The cooldown above keys on the <em>(sender, recipient)</em> pair, so a caller who trips it
     * learns only about their own past action. The daily cap does not: it counts one INBOX across
     * the whole instance, so its refusal necessarily carries one bit about workspaces the caller
     * cannot see. This test puts a <em>second</em> administrator of the same workspace — one who
     * has never mailed this address — in front of an inbox whose daily allowance is already full,
     * and asserts they are told about the row in their own workspace instead.
     *
     * <p>That is the free probe in its sharpest form. Under the wrong order the answer would be a
     * 429 naming a ceiling filled by five strangers, the transaction would roll back, and the
     * caller would have learned "this person's cap had room" (or not) <strong>without spending a
     * slot of it</strong> — repeatable indefinitely against any address they care to name.
     */
    @Test
    void theRefusalNeverReportsWhetherAStrangersDailyCapHadRoom() throws Exception {
        var owner = user();
        var workspace = workspaceOwnedBy(owner);
        var victim = address("cap-is-full");

        // One of the five sends is this workspace's own, so the blocking row lives here.
        invite(owner, workspace, victim).andExpect(status().isCreated());
        for (int i = 1; i < RECIPIENT_DAILY_DEFAULT; i++) {
            var stranger = user();
            invite(stranger, workspaceOwnedBy(stranger), victim).andExpect(status().isCreated());
        }
        var eventsBefore = eventRowsFor(victim);
        assertThat(eventsBefore)
                .as("the premise: the inbox's daily allowance is spent, so requireRecipientCeilings "
                    + "would refuse the next caller whoever they are")
                .isEqualTo(RECIPIENT_DAILY_DEFAULT);

        // A colleague with workspace.member.manage who has never mailed this address, so no
        // (sender, recipient) cooldown applies to them and only the DAILY CAP could refuse.
        var colleague = user();
        addAdmin(workspace, colleague);

        var refusal = invite(colleague, workspace, victim).andExpect(status().isConflict());

        assertThat(errorTypeOf(refusal))
                .as("""
                        this caller is behind a ceiling five strangers filled, in workspaces they \
                        cannot see. Answering 429 here would hand them one bit about a stranger's \
                        traffic AND cost them nothing, because the refusal rolls back the row it \
                        just recorded. The duplicate check above the throttle is what makes the \
                        answer a fact about their own workspace instead.""")
                .isEqualTo("DUPLICATE_INVITE");
        assertThat(eventRowsFor(victim))
                .as("nothing was recorded, and nothing was un-recorded either — the ceilings were "
                    + "never consulted at all")
                .isEqualTo(eventsBefore);
    }

    // ================================================================ what collides

    /**
     * <strong>{@code lower()} in the pre-check is load-bearing, and the WORDING is what proves
     * it.</strong>
     *
     * <p>The blocking row here is stored mixed-case — a spelling {@code inviteMember} cannot
     * produce (it folds with {@code Locale.ROOT} before every insert) and the V22 cleanup deletes,
     * so it is planted directly, standing in for the row a foreign writer leaves behind.
     *
     * <p>Note carefully what a <em>failure of the pre-check to see it</em> would look like: the
     * request would pass the check, record its mail event, insert, collide with the index, and be
     * translated into the same {@code 409 DUPLICATE_INVITE} by the catch — which always uses the
     * LIVE wording, because the winner of a race is live by construction. Same status, same
     * {@code errorType}, rolled-back row count. <strong>The only visible difference is the
     * sentence</strong>, so this test asserts the lapsed one: it is reachable only from the
     * pre-check, which read the blocking row and asked it about its expiry.
     */
    @Test
    void aPendingRowIsFoundByItsFoldedAddressAndTheWordingComesFromThatRow() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("mixed-case");

        plantInvite(workspace, admin, invitee.toUpperCase(java.util.Locale.ROOT),
                Instant.now().minusSeconds(60), null);

        var refusal = invite(admin, workspace, invitee).andExpect(status().isConflict());

        assertThat(errorTypeOf(refusal)).isEqualTo("DUPLICATE_INVITE");
        assertThat(detailOf(refusal))
                .as("""
                        THE LAPSED WORDING IS THE PROOF, NOT THE STATUS. A pre-check written as \
                        equals() against the already-folded Java string — or one that dropped \
                        lower() from the JPQL — would miss this row and let the request reach the \
                        index, which would answer the SAME 409 through the constraint \
                        translation, in the LIVE wording. Reaching it also means the recipient \
                        ceilings were consulted and their row written and rolled back on the way \
                        past, which is precisely the free probe. Java's toLowerCase(Locale.ROOT) \
                        and PostgreSQL's lower() are two different functions and \
                        InviteMemberRequest constrains only the LOCAL PART to ASCII, so this is \
                        not a hypothetical distinction — ask the database the question the index \
                        answers.""")
                .contains("has lapsed")
                .doesNotContain("still valid")
                .contains(invitee);
        assertThat(eventRowsFor(invitee))
                .as("and the ceilings were never reached, so nothing was written and nothing "
                    + "needed rolling back")
                .isZero();
    }

    /**
     * <strong>Case folds; {@code +tag} does not</strong> (§5.1, and both halves are asserted
     * because either one alone reads as an accident).
     *
     * <p>The rule that generates both: <em>fold as far as the harm points. A control that decides
     * who may be REACHED folds onto the inbox; a control that decides which OFFER stands folds
     * onto the address.</em> An offer is redeemed by exact match against {@code users.email}
     * (HD-120), so folding {@code bob+2@} onto {@code bob@} here would refuse an invitation to a
     * genuinely different account — an over-fold is fail-safe for a ceiling and a hole for an
     * offer. The gap that leaves (one workspace, two spellings, two live offers) is covered from
     * the other side by the cooldown, which counts both spellings as one inbox — which is exactly
     * what the second half of this test observes.
     */
    @Test
    void aCaseVariantCollidesAndATaggedVariantIsADifferentOffer() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("folding");

        invite(admin, workspace, invitee).andExpect(status().isCreated());

        var sameOffer = invite(admin, workspace, invitee.toUpperCase(java.util.Locale.ROOT))
                .andExpect(status().isConflict());
        assertThat(errorTypeOf(sameOffer))
                .as("Bob@ and bob@ are one offer. True twice over — the boundary fold makes them "
                    + "the same string before the query runs, and lower() in the index would hold "
                    + "the line even if that fold moved")
                .isEqualTo("DUPLICATE_INVITE");

        var otherOffer = invite(admin, workspace, tagged(invitee))
                .andExpect(status().isTooManyRequests());
        assertThat(errorTypeOf(otherOffer))
                .as("""
                        bob+2@ is a DIFFERENT OFFER — a different account could redeem it — so \
                        the uniqueness constraint must not refuse it. It is the same INBOX, \
                        though, so HD-190's cooldown does, and it does it by counting \
                        MailAddresses.throttleKey rather than the address. Asserting the 429 \
                        rather than merely "not a 409" is what keeps this case honest: the two \
                        controls fold differently ON PURPOSE, and neither is allowed to quietly \
                        adopt the other's key.""")
                .isNotEqualTo("DUPLICATE_INVITE");
        assertThat(inviteRowsFor(tagged(invitee)))
                .as("refused by the cooldown, so no second row — but not by uniqueness")
                .isZero();
    }

    // ================================================================ what does not collide

    /**
     * <strong>An expired invitation blocks, says so in its own words, and is cleared by the
     * control the sentence names</strong> (AC 6 and AC 8).
     *
     * <p>That expired rows block is PostgreSQL's answer rather than a product choice: a partial
     * index predicate must be {@code IMMUTABLE}, {@code now()} is {@code STABLE}, and a predicate
     * over a fixed instant stops being true the next day. It is also the shape this schema already
     * chose four times — {@code labels}, {@code components}, {@code versions}, {@code sprints} all
     * let an archived row keep its name, with a 409 that nudges toward clearing it. <em>A dead row
     * keeps its slot until somebody clears it, and the refusal's job is to say who clears it and
     * where.</em>
     *
     * <p>The withdrawal at the end is the half that makes that honourable, so it is driven through
     * the real endpoint rather than a repository delete. The blocking row is planted (never mailed),
     * which is what lets the re-invitation be a plain <strong>201</strong>: after a real first send
     * the same sequence would be a 429 from the cooldown, and a test that could not tell those two
     * apart would pass with the slot still occupied.
     */
    @Test
    void anExpiredInvitationBlocksWithItsOwnWordingAndWithdrawalFreesTheSlot() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("lapsed");

        var blocking = plantInvite(workspace, admin, invitee, Instant.now().minusSeconds(60), null);

        var refusal = invite(admin, workspace, invitee).andExpect(status().isConflict());
        assertThat(detailOf(refusal))
                .as("the live wording tells the reader to ask the invitee to check their inbox, "
                    + "which for a lapsed link is advice that cannot work. Two wordings off one "
                    + "errorType, chosen from the blocking row's expiry")
                .contains("has lapsed")
                .contains("Withdraw")
                .contains("People");

        mockMvc.perform(delete("/api/workspaces/" + workspace.getId() + "/invites/" + blocking)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        invite(admin, workspace, invitee)
                .andExpect(status().isCreated());
        assertThat(inviteRowsFor(invitee))
                .as("the slot is stock, not flow: withdrawing frees it with no code, and the fresh "
                    + "offer is the only one standing")
                .isEqualTo(1);
    }

    /**
     * <strong>An accepted invitation keeps no slot</strong> — which is the entire reason the index
     * is partial.
     *
     * <p>Invited → joined → removed → invited again is an ordinary sequence and has to stay one. A
     * full {@code UNIQUE(workspace_id, email)} would make the second invitation impossible for ever
     * unless somebody deleted the history of the first, so setting {@code accepted_at} is the
     * fourth way the slot is freed, alongside withdrawal, decline and member removal.
     */
    @Test
    void anAcceptedInvitationKeepsNoSlot() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("rejoins");

        plantInvite(workspace, admin, invitee, Instant.now().plusSeconds(3600), Instant.now());

        invite(admin, workspace, invitee).andExpect(status().isCreated());
        assertThat(inviteRowsFor(invitee))
                .as("both rows stand: the accepted one is history and outside the index, the new "
                    + "one is the standing offer")
                .isEqualTo(2);
    }

    /**
     * <strong>Already a member wins</strong> (AC 12), and the discriminator is what proves which
     * 409 came back.
     *
     * <p>A person can be both a member and the addressee of a leftover unaccepted row — joining
     * does not delete the invitations that were not used, only member removal does (HD-132). "They
     * are already in this workspace" is the more useful answer to the more important question, so
     * the duplicate check sits below it and only one of the two 409s is ever emitted.
     */
    @Test
    void beingAlreadyAMemberOutranksTheDuplicateRefusal() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var colleague = user();
        addAdmin(workspace, colleague);
        plantInvite(workspace, admin, colleague.getEmail(), Instant.now().plusSeconds(3600), null);

        var refusal = invite(admin, workspace, colleague.getEmail())
                .andExpect(status().isConflict());

        assertThat(errorTypeOf(refusal))
                .as("""
                        AlreadyWorkspaceMemberException carries no errorType, deliberately: there \
                        is nothing for the client to do but show it. So the ABSENCE of the \
                        discriminator is how this test knows which of the two 409s answered — a \
                        status assertion alone would pass on either. If the order ever inverted, \
                        an admin trying to fix a stale invitation would be told to withdraw a row \
                        while the real answer is that the person is already inside.""")
                .isNull();
    }

    /**
     * <strong>A pending invitation in workspace B neither blocks nor is named in workspace A, and
     * a non-member gets 404 before any of this is evaluated</strong> (AC 11).
     *
     * <p>Uniqueness is per workspace by definition — the index and the pre-check both lead on
     * {@code workspace_id}, and the pre-check is a two-key query rather than a {@code findByEmail}
     * filtered in Java. The 404 is the older rule and the one that outranks everything here:
     * non-existence and non-membership are one answer, and a caller who is not a member must not
     * learn from a 409 that this workspace holds an offer to an address they guessed.
     */
    @Test
    void anOfferInAnotherWorkspaceNeitherBlocksNorIsNamedHere() throws Exception {
        var stranger = user();
        var theirWorkspace = workspaceOwnedBy(stranger);
        var invitee = address("elsewhere");
        plantInvite(theirWorkspace, stranger, invitee, Instant.now().plusSeconds(3600), null);

        var admin = user();
        var ownWorkspace = workspaceOwnedBy(admin);
        invite(admin, ownWorkspace, invitee)
                .andExpect(status().isCreated());

        mockMvc.perform(inviteRequest(admin, theirWorkspace.getId(), invitee))
                .andExpect(status().isNotFound());
        mockMvc.perform(inviteRequest(admin, UUID.randomUUID(), invitee))
                .andExpect(status().isNotFound());
    }

    // ================================================================ the invariant

    /**
     * <strong>The race the pre-check cannot close answers the same 409, never a 500</strong>
     * (AC 13).
     *
     * <p>Two requests that both find the address free both reach the insert; the index is what
     * arbitrates. That interleaving is <em>forced</em> here rather than hoped for — a competing
     * transaction inserts the row and holds it uncommitted, so the request under test passes a
     * pre-check that cannot see it (READ COMMITTED), blocks at {@code saveAndFlush} on the unique
     * index, and receives the violation the moment the holder commits.
     *
     * <p>What is being asserted is the translation, and its two properties. It is <strong>keyed on
     * the index name</strong> rather than applied to every {@code DataIntegrityViolationException}
     * on the path: {@code workspace_invites} also carries a unique {@code token_hash} and three
     * foreign keys, and turning any of those into a plausible-looking conflict is the shape that
     * makes an incident hard to diagnose. And it produces the <strong>live</strong> wording without
     * re-reading the row: the winner was inserted moments ago with a seven-day TTL, and this
     * transaction is already doomed, so a read here would fail on the broken session rather than
     * answer.
     */
    @Test
    @Timeout(30)
    void aLostRaceAnswersTheSame409AndNeverA500() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var invitee = address("raced");

        var inserted = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var competitor = new Thread(() -> {
            try {
                transactions.executeWithoutResult(status -> {
                    plantInvite(workspace, admin, invitee, Instant.now().plusSeconds(3600), null);
                    em.flush();
                    inserted.countDown();
                    // Long enough for the request under test to reach its own insert and block on
                    // the index, short enough to stay well inside the 3s lock timeout that insert
                    // is running under.
                    sleep(800);
                });
            } catch (Throwable t) {
                failure.set(t);
                inserted.countDown();
            }
        });
        competitor.start();
        assertThat(inserted.await(10, TimeUnit.SECONDS))
                .as("the competing transaction never got its row in, so nothing was raced")
                .isTrue();

        var startedAt = System.nanoTime();
        var refusal = invite(admin, workspace, invitee);
        var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        competitor.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(failure.get()).isNull();

        assertThat(elapsedMs)
                .as("""
                        THE PROOF THAT THE INDEX ANSWERED AND NOT THE PRE-CHECK, because the two \
                        produce an identical response and a test that could not tell them apart \
                        would silently stop covering the constraint translation the day the \
                        interleaving stopped working. The competing row is uncommitted while this \
                        request runs, so READ COMMITTED hides it from the pre-check; the request \
                        then blocks at the insert until the competitor commits ~800ms later. A \
                        pre-check answer comes back in single-digit milliseconds. Measured: \
                        %dms""".formatted(elapsedMs))
                .isGreaterThan(300);

        assertThat(refusal.andReturn().getResponse().getStatus())
                .as("""
                        the pre-check is the sentence and the index is the invariant, so the loser \
                        of a race must arrive at the same answer by the other route. A 500 here \
                        is the ticket's own "must not 500 on the constraint"; a 201 means the \
                        index is not enforcing; anything else means the translation matched the \
                        wrong constraint.""")
                .isEqualTo(409);
        assertThat(errorTypeOf(refusal)).isEqualTo("DUPLICATE_INVITE");
        assertThat(detailOf(refusal))
                .as("the winner's row is live by construction — inserted moments ago with a "
                    + "seven-day TTL — so the catch uses the live wording rather than re-reading a "
                    + "row on a session the violation has already broken")
                .contains("waiting in this workspace");
        assertThat(inviteRowsFor(invitee))
                .as("exactly one offer survives the race, which is the whole invariant")
                .isEqualTo(1);
    }

    // ================================================================ fixture

    private ResultActions invite(User sender, Workspace workspace, String email) throws Exception {
        return mockMvc.perform(inviteRequest(sender, workspace.getId(), email));
    }

    private org.springframework.test.web.servlet.RequestBuilder inviteRequest(
            User sender, UUID workspaceId, String email) {
        return post("/api/workspaces/" + workspaceId + "/invites")
                .header("Authorization", bearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}");
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private String detailOf(ResultActions actions) throws Exception {
        var node = json.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.hasNonNull("detail") ? node.get("detail").asText() : "";
    }

    /** {@code null} when the problem carries no discriminator — which is itself an assertion. */
    private String errorTypeOf(ResultActions actions) throws Exception {
        var node = json.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.hasNonNull("errorType") ? node.get("errorType").asText() : null;
    }

    /** Unique per test run, so tests never share a recipient key or a {@code users} row. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    /** The same inbox, a different offer. */
    private static String tagged(String email) {
        var at = email.indexOf('@');
        return email.substring(0, at) + "+2" + email.substring(at);
    }

    private long inviteRowsFor(String email) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(i) FROM WorkspaceInvite i WHERE lower(i.email) = lower(:email)",
                        Long.class)
                .setParameter("email", email).getSingleResult());
    }

    /** Counted through the recipient KEY, which is what the ceilings count. */
    private long eventRowsFor(String email) {
        var key = MailAddresses.throttleKey(email);
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(e) FROM MailSendEvent e WHERE e.recipientKey = :key", Long.class)
                .setParameter("key", key).getSingleResult());
    }

    /**
     * A row written straight to the table, standing in for one this application cannot produce
     * through the endpoint: mixed-case (the boundary folds), expired (the TTL is fixed), or
     * accepted. Deliberately writes <strong>no</strong> {@code mail_send_events} row, so a
     * re-invitation over it meets the uniqueness rule and nothing else — which is what lets these
     * tests tell a 409 from a 429.
     */
    private UUID plantInvite(Workspace ws, User inviter, String email, Instant expiresAt,
                             Instant acceptedAt) {
        var i = new WorkspaceInvite();
        i.setWorkspace(ws);
        i.setEmail(email);
        i.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        i.setTokenHash(TokenUtils.sha256(TokenUtils.generateRawToken()));
        i.setInvitedBy(inviter);
        i.setExpiresAt(expiresAt);
        i.setAcceptedAt(acceptedAt);
        return inviteRepository.save(i).getId();
    }

    private User user() {
        var u = new User();
        u.setEmail(address("user"));
        u.setDisplayName("Duplicate invite");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Duplicate invite " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("di-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(owner);
        var saved = workspaceRepository.save(w);
        addMember(saved, owner, "OWNER");
        return saved;
    }

    /** A second holder of {@code workspace.member.manage} — Admin holds it too. */
    private void addAdmin(Workspace workspace, User user) {
        addMember(workspace, user, "ADMIN");
    }

    private void addMember(Workspace workspace, User user, String role) {
        var member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(user);
        member.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(member);
    }

    /**
     * Blocks until the mailer has actually been called, so a later {@code verifyNoInteractions} is
     * about the request under test rather than about a thread that had not got there yet. Fails
     * loudly rather than timing out silently: "no mail was ever sent" would make the refusal
     * assertion pass for the wrong reason.
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
