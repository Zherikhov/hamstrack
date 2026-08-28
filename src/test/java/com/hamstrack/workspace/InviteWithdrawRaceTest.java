package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
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
import com.hamstrack.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The one genuinely new hazard in HD-158: a second actor writing
 * {@code workspace_invites}</strong> (§6.3).
 *
 * <p>Before the withdrawal endpoint, a row in this table had one writer at a time in practice —
 * the invitee, accepting or declining their own invitation. An administrator withdrawing one is
 * the first write by somebody else, so accept and withdraw can now interleave. What makes that
 * more than a bad message is that {@link WorkspaceInvite} extends {@code CreatedOnlyEntity} and
 * carries <strong>no {@code @Version}</strong>: the loser of an unlocked race does not get an
 * optimistic-locking failure, it gets Hibernate's unexpected row-count {@code StaleStateException}
 * — a 500 for the invitee, raised after the transaction has already decided to make them a member.
 *
 * <p><strong>One test per load site, and the sites are the enumeration.</strong> §6.3 moves three
 * reads onto locking finders — the emailed link, the by-id accept, and (since the fix round) the
 * decline — and the withdrawal itself is the fourth participant. Each is raced separately, because
 * "all the load sites lock" is a claim about a list, and a list held by one parameterised case is
 * one somebody shortens without noticing which member they deleted.
 *
 * <p>Every test here forces the interleaving rather than hoping for it: a competing transaction
 * takes the row lock, holds it for {@link #HOLD_MS} and only then commits, so the request under
 * test is guaranteed to arrive second and to <em>queue</em>. Each one therefore asserts three
 * things — the status, the state of the world afterwards, and that the request really waited. The
 * wait matters as much as the status: without it a green test proves only that the two requests
 * never met.
 *
 * <p><strong>The bound is the shipped one</strong> ({@code app.locking.lock-timeout-ms}, 3000).
 * {@link #HOLD_MS} sits well under it, so a queued request is released by the holder's commit and
 * never by the timeout — a 409 "try again in a moment" appearing in any of these tests would mean
 * the hold outgrew the bound, not that the feature refused.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InviteWithdrawRaceTest {

    /** Long enough that "it queued" is unmistakable, short enough to stay under the 3 s bound. */
    private static final long HOLD_MS = 1_200;

    /** Slack for scheduling: the request has to have waited for most of the hold, not all of it. */
    private static final long QUEUED_MS = 900;

    /** No SMTP in CI. Nothing here sends mail; the mock keeps the context shared with siblings. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired WorkspaceService workspaceService;
    @Autowired TransactionTemplate transactions;

    /**
     * <strong>Accept commits under a waiting withdrawal: 409, and the person is in</strong>
     * (AC 13, §4.4b).
     *
     * <p>The invitee accepts while the administrator's click is already in flight. The withdrawal
     * queues on the row, and when it finally reads it the row is settled and says
     * {@code accepted_at} — so the answer is the one refusal on this endpoint whose reader can act,
     * naming the member and the screen where they can remove them. Not a 500, and not a 204 that
     * would delete the invitation of somebody who is already a member and leave the roster
     * unexplained.
     */
    @Test
    @Timeout(60)
    void anAcceptCommittingUnderAWaitingWithdrawalAnswers409AndTheInviteeIsAMember()
            throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var inviteeAddress = address("racer");
        var invitee = user("Ivy Invitee", inviteeAddress);
        var inviteId = plantInvite(ws, owner, inviteeAddress);

        var race = raceAgainst(() -> workspaceService.acceptInvite(invitee, inviteId),
                delete("/api/workspaces/" + ws.getId() + "/invites/" + inviteId)
                        .header("Authorization", bearer(owner)));

        assertThat(race.status())
                .as("the loser of this race must read a settled row and answer definitely. A 500 "
                    + "here is the StaleStateException the lock exists to prevent; a 204 would be "
                    + "a withdrawal that deleted the invitation of somebody who had already "
                    + "joined")
                .isEqualTo(409);
        assertThat(race.body()).contains("INVITE_ALREADY_ACCEPTED").contains("People");
        assertThat(race.waitedMs())
                .as("the withdrawal returned in %d ms while the accept held the row for %d ms: it "
                    + "never queued, so this test observed two requests that did not meet",
                        race.waitedMs(), HOLD_MS)
                .isGreaterThanOrEqualTo(QUEUED_MS);
        assertThat(memberExists(ws, invitee))
                .as("the accept committed first and it committed completely")
                .isTrue();
        assertThat(inviteRepository.findById(inviteId))
                .as("a refused withdrawal deletes nothing")
                .isPresent();
    }

    /**
     * <strong>Withdrawal commits under a waiting accept: 404, and nobody joined</strong> (AC 13).
     *
     * <p>The mirror image, and the half that would be a data defect rather than a bad message.
     * Unlocked, the accept reads the row, decides to make the invitee a member, and then flushes an
     * {@code UPDATE} that affects zero rows — after the membership insert. Under the lock the
     * accept arrives second, finds no row at all, and answers exactly what an invitation that
     * never existed answers. The membership count is the assertion that matters: <strong>zero
     * {@code workspace_members} rows</strong>, because "the invitation is gone but the person got
     * in" is the outcome nothing else in the system would ever correct.
     */
    @Test
    @Timeout(60)
    void aWithdrawalCommittingUnderAWaitingAcceptAnswers404AndCreatesNoMembership()
            throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var inviteeAddress = address("racer");
        var invitee = user("Ivy Invitee", inviteeAddress);
        var inviteId = plantInvite(ws, owner, inviteeAddress);

        var race = raceAgainst(() -> workspaceService.revokeInvite(owner, ws.getId(), inviteId),
                post("/api/invites/" + inviteId + "/accept")
                        .header("Authorization", bearer(invitee)));

        assertThat(race.status())
                .as("a withdrawn invitation is indistinguishable from one that never existed, "
                    + "which is what the accept path already answers 404 for")
                .isEqualTo(404);
        assertThat(race.waitedMs())
                .as("the accept returned in %d ms while the withdrawal held the row for %d ms: it "
                    + "never queued", race.waitedMs(), HOLD_MS)
                .isGreaterThanOrEqualTo(QUEUED_MS);
        assertThat(memberExists(ws, invitee))
                .as("no workspace_members row may exist for a withdrawn invitation — an accept "
                    + "that inserts the membership and then fails to mark the invitation is the "
                    + "one outcome of this race that nothing else in the system would correct")
                .isFalse();
        assertThat(inviteRepository.findById(inviteId)).isEmpty();
    }

    /**
     * <strong>The same, through the emailed link</strong> — the third of the three load sites
     * §6.3 converts to a locking finder, and the one an invitee actually uses.
     *
     * <p>{@code acceptInvite(User, String rawToken)} resolves by token hash rather than by id, so
     * it is a separate finder and a separate {@code @Lock}: the by-id race above says nothing
     * about it. Written as its own test rather than parameterised into that one because "the three
     * load sites are covered" is a claim about an enumeration, and an enumeration held by a
     * parameter is one somebody shortens without noticing which case they deleted.
     */
    @Test
    @Timeout(60)
    void aWithdrawalCommittingUnderAWaitingLinkAcceptAnswers404AndCreatesNoMembership()
            throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var inviteeAddress = address("racer");
        var invitee = user("Ivy Invitee", inviteeAddress);
        var rawToken = TokenUtils.generateRawToken();
        var inviteId = plantInvite(ws, owner, inviteeAddress, TokenUtils.sha256(rawToken));

        var race = raceAgainst(() -> workspaceService.revokeInvite(owner, ws.getId(), inviteId),
                post("/api/workspaces/accept-invite")
                        .param("token", rawToken)
                        .header("Authorization", bearer(invitee)));

        assertThat(race.status())
                .as("the emailed link resolves through its own locking finder — findByTokenHash"
                    + "ForUpdate — so the same race has to end the same way")
                .isEqualTo(404);
        assertThat(race.waitedMs())
                .as("the link accept returned in %d ms while the withdrawal held the row for %d ms:"
                    + " that finder took no lock", race.waitedMs(), HOLD_MS)
                .isGreaterThanOrEqualTo(QUEUED_MS);
        assertThat(memberExists(ws, invitee))
                .as("a withdrawn invitation admits nobody, by link or by id")
                .isFalse();
    }

    /**
     * <strong>Withdrawal commits under a waiting decline: 404, not "try again in a moment"</strong>
     * — the fix round's change, and the reason the spec's waiver did not survive its own ticket.
     *
     * <p>§6.3 left {@code declineInvite} unlocked on the ground that the invitee cannot race their
     * own accept. True, and beside the point the same ticket created: the second writer is the
     * <em>administrator</em>. Unlocked, the withdrawal commits between the decline's read and its
     * flush, the DELETE affects zero rows, and the invitee is handed the generic
     * pessimistic-lock/stale-state 409 — <em>"someone else is changing this right now, try again in
     * a moment"</em> — for an invitation that is permanently gone. That is a refusal prescribing an
     * action its reader cannot perform, on the one path where the reader is a stranger to the
     * workspace and has no other way to find out.
     *
     * <p>Both halves are asserted: the status, and the absence of the retry sentence. Only the
     * second one distinguishes the fix from a 409 that happens to carry a different code.
     */
    @Test
    @Timeout(60)
    void aWithdrawalCommittingUnderAWaitingDeclineAnswers404AndNeverAsksForARetry()
            throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var inviteeAddress = address("racer");
        var invitee = user("Ivy Invitee", inviteeAddress);
        var inviteId = plantInvite(ws, owner, inviteeAddress);

        var race = raceAgainst(() -> workspaceService.revokeInvite(owner, ws.getId(), inviteId),
                post("/api/invites/" + inviteId + "/decline")
                        .header("Authorization", bearer(invitee)));

        assertThat(race.status())
                .as("the decline arrived second and its invitation is gone: that is a 404, the "
                    + "same answer it gives for an id that never existed")
                .isEqualTo(404);
        assertThat(race.waitedMs())
                .as("the decline returned in %d ms while the withdrawal held the row for %d ms, so "
                    + "it took no lock — which is exactly the state the fix round changed",
                        race.waitedMs(), HOLD_MS)
                .isGreaterThanOrEqualTo(QUEUED_MS);
        assertThat(race.body().toLowerCase(Locale.ROOT))
                .as("the invitee must never be told to retry something that can never succeed "
                    + "again. Body: %s", race.body())
                .doesNotContain("try again");
    }

    // ================================================================ the harness

    /** What the racing request answered, and how long it spent queued on the row lock. */
    private record Race(int status, String body, long waitedMs) {}

    /**
     * Runs {@code holder} in its own transaction, waits until it has taken the row lock, fires
     * {@code request} on this thread — where it queues — and lets the holder commit
     * {@link #HOLD_MS} later.
     *
     * <p>The lock is taken by the service method itself rather than by a hand-written locking read,
     * so what is being raced is the product's own critical section: a future change that stops
     * locking there stops being tested here, loudly (the request would return immediately and the
     * {@code waitedMs} assertion would fail), rather than silently.
     */
    private Race raceAgainst(Runnable holder, RequestBuilder request) throws Exception {
        var locked = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                transactions.executeWithoutResult(status -> {
                    holder.run();
                    // The lock is held from here to COMMIT, which is what the request queues on.
                    locked.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
                locked.countDown();
            }
        }, "invite-row-lock-holder");
        thread.start();

        assertThat(locked.await(30, TimeUnit.SECONDS))
                .as("the competing transaction never reached its row lock")
                .isTrue();
        assertThat(failure.get())
                .as("the competing transaction failed before it could hold anything")
                .isNull();

        var startedAt = System.nanoTime();
        MvcResult result = mockMvc.perform(request).andReturn();
        var waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

        thread.join();
        assertThat(failure.get())
                .as("the competing transaction failed while committing, so the race under test "
                    + "never actually happened")
                .isNull();
        return new Race(result.getResponse().getStatus(),
                result.getResponse().getContentAsString(), waitedMs);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ fixture

    private boolean memberExists(Workspace ws, User user) {
        return workspaceMemberRepository.existsByWorkspaceAndUser(ws, user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private User user(String displayName, String email) {
        var u = new User();
        u.setEmail(email.toLowerCase(Locale.ROOT));
        u.setDisplayName(displayName);
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private User user(String displayName) {
        return user(displayName, address("user"));
    }

    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Withdraw race " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("wr-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(owner);
        var saved = workspaceRepository.save(w);
        var m = new WorkspaceMember();
        m.setWorkspace(saved);
        m.setUser(owner);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
        return saved;
    }

    private UUID plantInvite(Workspace ws, User inviter, String email) {
        return plantInvite(ws, inviter, email, TokenUtils.sha256(TokenUtils.generateRawToken()));
    }

    private UUID plantInvite(Workspace ws, User inviter, String email, String tokenHash) {
        var i = new WorkspaceInvite();
        i.setWorkspace(ws);
        i.setEmail(email.toLowerCase(Locale.ROOT));
        i.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        i.setTokenHash(tokenHash);
        i.setInvitedBy(inviter);
        i.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600));
        return inviteRepository.save(i).getId();
    }
}
