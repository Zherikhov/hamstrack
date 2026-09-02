package com.hamstrack.auth;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.AuthMailProperties;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailSendEventRepository;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The per-address ceiling on the two UNIFORM-RESPONSE auth mailers</strong> (HD-202) —
 * {@code forgot-password} and {@code resend-verification}. {@code register} refuses visibly, on a
 * budget of its own, and has its own file ({@code AuthMailRegisterThrottleTest}); which door each
 * may use is sealed by {@code AuthMailDoorsTest}.
 *
 * <h2>What was open, and why the per-IP budget was never the answer</h2>
 * {@code forgot-password} and {@code resend-verification} were "look the address up and send". Their
 * only budget was the per-IP window on {@code /api/auth/*} — and whatever that key is, it is an
 * address the attacker chooses among, so a budget keyed on it can always be widened by coming from
 * somewhere else. It looked adequate only because {@code RATE_LIMIT_TRUST_FORWARDED_FOR} never
 * reached the production container, which accidentally made every request in the world share one
 * bucket. HD-199 fixed the flag and the bucket became one per visitor: unlimited buckets, all of
 * which can be pointed at one address, and every message sent from our own domain with the victim's
 * address in {@code To}. The thing being attacked is the account, so the account is what is counted.
 *
 * <h2>How a send is observed here, and why it is not the {@link JavaMailSender}</h2>
 * Dispatch is registered on {@code AfterCommit} and handed to a pool, so "did SMTP happen" is not a
 * question a request thread can answer without a sleep. It does not need to: each of these flows
 * writes its one-time token <em>inside</em> the transaction, and a link that was never minted cannot
 * have been mailed. So a new {@code password_resets} / {@code email_verifications} row is the exact,
 * synchronous witness of a send decision, and its absence is the witness of a drop. This is the same
 * reasoning {@code AuthFlowsTest} states for the same endpoints.
 *
 * <h2>The properties, and why the fourth is not a stopwatch</h2>
 * <ol>
 *   <li>The second request inside the cooldown mails nothing and <em>says exactly what the first
 *       said</em>. The endpoints' uniform answer is the anti-enumeration contract, so the refusal
 *       has to be invisible: a 429 here would tell anybody on the internet, for free, that somebody
 *       asked for a reset at this address a minute ago.</li>
 *   <li>Two addresses do not spend each other. The key is the recipient inbox, not the caller.</li>
 *   <li>Changing the per-IP key between requests changes nothing — the whole point, since that key
 *       is the one an attacker has an unlimited supply of.</li>
 *   <li><strong>An address with no account is throttled exactly like one with an account.</strong>
 *       That is a timing and behaviour assertion in one, and it is deterministic where a stopwatch
 *       would be flaky: the only way to fail it is to spend the ceiling <em>after</em> the account
 *       lookup, or only on the branch that really sends — which is the precise defect that would
 *       turn this ticket's fix into the oracle both endpoints exist to refuse. A wall-clock
 *       assertion would test the same line and fail on a noisy CI box.</li>
 * </ol>
 *
 * <p>Four more were added by the HD-202 review, each closing a hole every test above passes
 * through: the <strong>volume cap</strong> itself (all four properties stop at the cooldown, so the
 * cap could have been dead code), a <strong>{@code +tag} spelling</strong> spending the base
 * address's budget (every test above uses one spelling throughout, so all of them pass against a
 * throttle keyed on the raw address — the exact defect {@code MailAddresses.throttleKey} exists to
 * prevent), and the two facts the tenancy review found asserted in prose in three places and
 * checked nowhere: that an anonymous row carries {@code sender_user_id IS NULL} <em>and</em>
 * {@code workspace_id IS NULL}, and that an invitation to an address does not consume that
 * address's reset budget.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        // The per-IP limiter is not what is under test and must not be what refuses: 15/minute is
        // the shipped default and several of these tests spend more than that from one key.
        "app.rate-limit.auth-ip-requests-per-minute=1000",
        // So the per-IP key can be MOVED from the test, which is what property 3 needs.
        "app.rate-limit.trust-forwarded-for=true",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AuthMailRecipientThrottleTest {

    /** {@code app.auth-mail.max-per-recipient-per-window}'s shipped default. */
    private static final int CAP = 5;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired MailSendEventRepository mailSendEvents;
    @Autowired TransactionTemplate transactions;
    @Autowired RecipientMailThrottle throttle;

    @PersistenceContext EntityManager em;

    /** Never reach real SMTP; nothing here asserts on it (see the class javadoc). */
    @MockitoBean JavaMailSender mailSender;

    // ============================================================ 1. the second request

    /**
     * <strong>Acceptance criterion 1.</strong> Two reset requests a second apart mint one link, and
     * the caller cannot tell which of the two responses was the one that did nothing.
     *
     * <p>Both halves matter and they fail independently: a throttle that refused loudly would pass
     * the first assertion and fail the second, and it is the second that is the security contract.
     */
    @Test
    void theSecondResetRequestInsideTheCooldownMailsNothingAndSaysWhatTheFirstSaid() throws Exception {
        var user = activeUser();

        var first = forgotPassword(user.getEmail(), "198.51.100.7");
        var second = forgotPassword(user.getEmail(), "198.51.100.7");

        assertThat(resetTokensFor(user))
                .as("the second request is inside the cooldown, so it must mint no second link — "
                    + "and a link that was never minted was never mailed")
                .isEqualTo(1);
        assertThat(second)
                .as("and the caller must not be able to tell. A 429 here would not leak whether the "
                    + "ADDRESS EXISTS — the ceiling is spent before the lookup and recorded either "
                    + "way — but it would leak, to anybody, that somebody asked for a reset at this "
                    + "address in the last minute, which is a third party's activity published by "
                    + "an unauthenticated endpoint")
                .isEqualTo(first);
    }

    /** The same for the other anonymous mailer; the two have separate budgets and identical shape. */
    @Test
    void theSecondVerificationRequestInsideTheCooldownMailsNothingAndSaysWhatTheFirstSaid() throws Exception {
        var user = pendingUser();

        var first = resendVerification(user.getEmail(), "198.51.100.8");
        var second = resendVerification(user.getEmail(), "198.51.100.8");

        assertThat(verificationTokensFor(user))
                .as("one link, not two")
                .isEqualTo(1);
        assertThat(second).isEqualTo(first);
    }

    // ============================================================ 2. one address does not spend another

    /**
     * <strong>Acceptance criterion 2.</strong> The ceiling is keyed on the recipient, so one source
     * hammering address A must not deny address B.
     *
     * <p>The failure this excludes is a limiter accidentally keyed on the caller or on nothing at
     * all — which would turn a harassment control into an outage for everybody trying to reset a
     * password while one attacker is running.
     */
    @Test
    void twoAddressesFromOneSourceDoNotSpendEachOther() throws Exception {
        var alice = activeUser();
        var bob = activeUser();

        forgotPassword(alice.getEmail(), "203.0.113.9");
        forgotPassword(bob.getEmail(), "203.0.113.9");

        assertThat(resetTokensFor(alice)).isEqualTo(1);
        assertThat(resetTokensFor(bob))
                .as("bob asked once and must be served once. A ceiling that let alice's request "
                    + "spend bob's allowance would be a denial of password recovery for everybody "
                    + "on the instance, run from one address, for free")
                .isEqualTo(1);
    }

    // ============================================================ 3. the per-IP key is irrelevant

    /**
     * <strong>Acceptance criterion 3, and the reason the ticket exists.</strong> The second request
     * arrives on a different per-IP key and is refused anyway.
     *
     * <p>{@code app.rate-limit.trust-forwarded-for} is on for this context, so the rightmost
     * {@code X-Forwarded-For} entry <em>is</em> the limiter's key — the same arrangement production
     * runs under since HD-199, where Caddy writes the real visitor address into that header. Moving
     * it between the two requests is exactly what a rotating proxy pool does, and it is what makes a
     * per-IP budget unable to close this: whatever the key is, it is an address the attacker chooses
     * among.
     */
    @Test
    void theCeilingHoldsWhenThePerIpKeyChangesBetweenRequests() throws Exception {
        var user = activeUser();

        var first = forgotPassword(user.getEmail(), "192.0.2.1");
        var second = forgotPassword(user.getEmail(), "192.0.2.2");
        var third = forgotPassword(user.getEmail(), "198.51.100.200");

        assertThat(resetTokensFor(user))
                .as("three requests from three different addresses, one link. A budget that a new "
                    + "source address resets is not a budget — it is a counter of how many source "
                    + "addresses the caller has")
                .isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    // ============================================================ 4. existence changes nothing

    /**
     * <strong>Acceptance criterion 4, asserted structurally rather than with a stopwatch.</strong>
     *
     * <p>The ceiling is spent <em>before</em> the account lookup and recorded whether or not a row
     * exists, so an address nobody has registered leaves the same footprint in
     * {@code mail_send_events} as a registered one. Spent after the lookup — or only inside the
     * branch that mails — the count for an unknown address would stay at zero, and "is this address
     * throttled yet" would answer the question the endpoint exists to refuse. That is the whole of
     * the design constraint on this ticket, and it is what this test measures.
     *
     * <p>The response bodies are compared too: the uniform sentence is the other half of the
     * contract, and it is the half a future edit is most likely to break by adding a helpful detail.
     */
    @Test
    void anAddressWithNoAccountSpendsTheCeilingExactlyAsARegisteredOneDoes() throws Exception {
        var registered = activeUser().getEmail();
        var unknown = address();

        var knownBody = forgotPassword(registered, "198.51.100.11");
        var unknownBody = forgotPassword(unknown, "198.51.100.11");

        assertThat(unknownBody)
                .as("the two answers must be one answer")
                .isEqualTo(knownBody);
        assertThat(recordedSends(unknown))
                .as("an unknown address must be COUNTED, or the ceiling becomes an existence "
                    + "oracle: throttled would mean registered and un-throttled would mean not, "
                    + "and it would look like a security fix while being the opposite")
                .isEqualTo(1);
        assertThat(recordedSends(registered))
                .as("and a registered one is counted identically — one row each, from one request "
                    + "each")
                .isEqualTo(1);
    }

    /**
     * The same ordering seen from the other side: an unknown address is <em>refused</em> on its
     * second request just as a registered one is.
     *
     * <p>Unobservable in the response by design, so it is read off the bookkeeping the ceiling
     * itself keeps: a second row would mean the second request got past the cooldown.
     */
    @Test
    void anAddressWithNoAccountIsRefusedOnItsSecondRequestJustAsARegisteredOneIs() throws Exception {
        var unknown = address();

        forgotPassword(unknown, "198.51.100.12");
        forgotPassword(unknown, "198.51.100.12");

        assertThat(recordedSends(unknown))
                .as("the cooldown is spent on the submitted address, so the second request is "
                    + "dropped before anything reads the users table")
                .isEqualTo(1);
    }

    // ============================================================ the budgets are separate

    /**
     * A reset flood at an address must not withhold that address's verification link, or the other
     * way round: counts are taken per {@code email_type}.
     *
     * <p>Not a nicety. A shared bucket would let a stranger's traffic suppress the one piece of mail
     * the victim actually asked for — the failure mode {@code InviteProperties} already refuses for
     * invitations, restated here because this is the pair it was refused on behalf of.
     */
    @Test
    void theResetAndVerificationBudgetsAreSeparateBucketsOnOneAddress() throws Exception {
        var user = pendingUser();

        for (int i = 0; i < CAP; i++) {
            forgotPassword(user.getEmail(), "198.51.100.13");
        }
        resendVerification(user.getEmail(), "198.51.100.13");

        assertThat(verificationTokensFor(user))
                .as("the reset budget is spent to its hourly cap and the verification link still "
                    + "goes out. One bucket for both would hand an attacker a way to keep a "
                    + "pending account from ever being activated, by spamming a different endpoint")
                .isEqualTo(1);
    }

    // ============================================================ the volume cap itself

    /**
     * <strong>The second ceiling, which nothing else in this file reaches.</strong> Every test
     * above stops at the cooldown — the first request is served, the second is dropped a second
     * later — so the volume cap could have been dead code and they would all still pass. The
     * two ceilings fail independently and this is the one whose width was argued over.
     *
     * <p>The requests are spread across the window with the clock, not with a sleep: each is given
     * its own recorded row by moving the previous rows' {@code created_at} back past the cooldown,
     * which is what an attacker pacing themselves does and is deterministic where a sleep is slow
     * and flaky. What is left refusing is the cap alone.
     */
    @Test
    void theCapRefusesTheRequestAfterTheLastOneEvenWhenEveryCooldownHasExpired() throws Exception {
        var user = activeUser();

        for (int i = 0; i < CAP; i++) {
            forgotPassword(user.getEmail(), "198.51.100.21");
            ageOutTheCooldown(user.getEmail());
        }
        assertThat(resetTokensFor(user))
                .as("the cap is %d, and %d requests spaced past the cooldown must each be served — "
                    + "if this is short, the cooldown is refusing and the next assertion proves "
                    + "nothing", CAP, CAP)
                .isEqualTo(CAP);

        forgotPassword(user.getEmail(), "198.51.100.21");

        assertThat(resetTokensFor(user))
                .as("the (cap+1)-th request is outside every cooldown and must still be refused, "
                    + "silently. Without this the volume cap could be deleted and every other test "
                    + "in this file would stay green")
                .isEqualTo(CAP);
    }

    // ============================================================ the key is the inbox

    /**
     * <strong>A {@code +tag} spelling spends the base address's budget</strong> — the property the
     * whole mechanism rests on, asserted here for the first time on the auth flows.
     *
     * <p>{@code MailAddresses.throttleKey} exists because keying on the raw address made every
     * ceiling in the feature decorative: {@code victim+1@} and {@code victim+2@} are two strings and
     * one inbox, so an attacker re-spelled the attack at the cost of one keystroke and both counts
     * read zero every time. Nothing in this file could see that regression — every test above uses
     * one spelling throughout, so all of them pass against a throttle keyed on the raw address.
     */
    @Test
    void aPlusTagSpellingSpendsTheBaseAddressesBudget() throws Exception {
        var base = "hd202-" + UUID.randomUUID() + "@gmail.com";
        var tagged = base.replace("@", "+anything@");

        forgotPassword(base, "198.51.100.22");
        forgotPassword(tagged, "198.51.100.22");

        assertThat(recordedSends(base))
                .as("both requests reach one inbox, so they share one bucket and the second is "
                    + "refused. If this is 2, the ceilings count SPELLINGS and the whole control "
                    + "is defeated by one keystroke")
                .isEqualTo(1);
    }

    // ============================================================ what the anonymous row carries

    /**
     * <strong>An anonymous row carries no sender and no workspace</strong> — both {@code NULL},
     * both by construction rather than by coincidence.
     *
     * <p>Asserted because they are load-bearing in two directions at once. Tenancy: this table is
     * not workspace-scoped and these two columns are the only tenant-shaped things in it, so a
     * value derived from anything the request carries would attach a stranger's password-reset
     * attempt to somebody's workspace. And bucketing: the anonymous ceiling counts
     * {@code sender_user_id IS NULL}, so a non-null value here would give every anonymous request a
     * fresh cooldown — the ceiling would read zero forever and refuse nothing, with no error
     * anywhere.
     */
    @Test
    void theRowAnAnonymousRequestWritesNamesNoSenderAndNoWorkspace() throws Exception {
        var address = address();

        forgotPassword(address, "198.51.100.23");

        var nulls = transactions.execute(status -> em.createQuery("""
                        SELECT count(e) FROM MailSendEvent e
                         WHERE e.recipientKey = :key
                           AND e.senderUserId IS NULL
                           AND e.workspaceId IS NULL
                        """, Long.class)
                .setParameter("key", MailAddresses.throttleKey(address))
                .getSingleResult());

        assertThat(nulls)
                .as("sender_user_id and workspace_id must both be NULL on an anonymous row. They "
                    + "are hard-coded null at the call site rather than derived, and this is what "
                    + "says so — a future edit passing 'the workspace this address belongs to' "
                    + "would break tenancy and the anonymous cooldown in the same line")
                .isEqualTo(1);
    }

    /**
     * <strong>An invitation to an address does not consume that address's reset budget.</strong>
     *
     * <p>Counts are taken per {@code email_type} in the {@code WHERE} clause, which is what keeps
     * the buckets separate. The failure this excludes is not hypothetical tidiness: a shared bucket
     * would let any workspace member on the instance suppress a stranger's password recovery by
     * inviting them, and the victim would be told a link was sent.
     *
     * <p>The invitation is spent through the throttle directly rather than through
     * {@code POST …/invites}, because what is under test is the bucketing and not the invite
     * endpoint's tenancy — which has tests of its own.
     */
    @Test
    void anInvitationToAnAddressDoesNotSpendThatAddressesResetBudget() throws Exception {
        var user = activeUser();

        transactions.executeWithoutResult(status -> throttle.requireAndRecord(
                EmailType.INVITE, user.getEmail(), UUID.randomUUID(), UUID.randomUUID()));

        forgotPassword(user.getEmail(), "198.51.100.24");

        assertThat(resetTokensFor(user))
                .as("the reset link must still go out. One bucket for both would hand every member "
                    + "of every workspace a way to withhold a stranger's account recovery, by "
                    + "typing their address into an invite box")
                .isEqualTo(1);
        assertThat(rowsOfType(EmailType.INVITE, user.getEmail()))
                .as("and the invitation was really recorded — otherwise this test would pass "
                    + "against a throttle that recorded nothing at all")
                .isEqualTo(1);
        assertThat(rowsOfType(EmailType.PASSWORD_RESET, user.getEmail()))
                .as("one row per bucket on one address, never one shared row")
                .isEqualTo(1);
    }

    // ============================================================ fixture

    private String forgotPassword(String email, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/auth/forgot-password")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String resendVerification(String email, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * How many rows the throttle has written for this address inside the ceiling window — its own
     * bookkeeping, read through its own query, folded through {@code MailAddresses.throttleKey} the
     * way the ceilings count it. A key computed any other way here would make every assertion using
     * it pass against a count of zero.
     */
    private long recordedSends(String email) {
        var now = Instant.now();
        return recordedSends(EmailType.PASSWORD_RESET, email);
    }

    /**
     * The same, for one named kind of mail — which is what makes the separate-budgets and
     * folded-key assertions below say anything: a count that ignored {@code email_type} would pass
     * whichever bucket the row landed in.
     */
    private long recordedSends(EmailType type, String email) {
        var now = Instant.now();
        var from = now.minus(AuthMailProperties.VERIFICATION_CEILING_WINDOW);
        return mailSendEvents.countRecentForAnonymous(type.name(),
                MailAddresses.throttleKey(email), from, from, from).recipientWindowOwn();
    }

    /** Rows this throttle wrote for one inbox in one bucket, whoever the sender was. */
    private long rowsOfType(EmailType type, String email) {
        return transactions.execute(status -> em.createQuery("""
                        SELECT count(e) FROM MailSendEvent e
                         WHERE e.emailType = :type AND e.recipientKey = :key
                        """, Long.class)
                .setParameter("type", type.name())
                .setParameter("key", MailAddresses.throttleKey(email))
                .getSingleResult());
    }

    private long resetTokensFor(User user) {
        return countTokens("SELECT count(p) FROM PasswordReset p WHERE p.user.id = :id", user);
    }

    private long verificationTokensFor(User user) {
        return countTokens("SELECT count(v) FROM EmailVerification v WHERE v.user.id = :id", user);
    }

    /**
     * Counted in the database rather than by loading rows and reading {@code getUser()}: with
     * {@code open-in-view=false} that association is not initialised outside a transaction, and a
     * lazy-loading failure here would look like a throttle failure.
     */
    private long countTokens(String jpql, User user) {
        return transactions.execute(status -> em.createQuery(jpql, Long.class)
                .setParameter("id", user.getId())
                .getSingleResult());
    }

    /**
     * Moves every recorded send to this inbox back past the widest cooldown, so the next request
     * meets only the volume cap. Faster and steadier than sleeping, and it is exactly what an
     * attacker who paces themselves produces.
     */
    private void ageOutTheCooldown(String email) {
        // Native, because created_at is mapped updatable = false on a CreatedOnlyEntity and
        // this test must not depend on whether a bulk JPQL update honours that.
        transactions.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE mail_send_events SET created_at = :old WHERE recipient_key = :key")
                .setParameter("old", Instant.now().minusSeconds(180))
                .setParameter("key", MailAddresses.throttleKey(email))
                .executeUpdate());
    }

    private User activeUser() {
        return newUser(UserStatus.ACTIVE);
    }

    private User pendingUser() {
        return newUser(UserStatus.PENDING);
    }

    /**
     * Created through the repository rather than through {@code POST /api/auth/register}.
     *
* <p>Registration is a whole endpoint with its own ceilings, its own refusal shape and its own
     * budget; going through it to obtain a fixture would make every test below depend on a second
     * limiter's state. It used to say "…spends the very budget these tests measure — see
     * {@code AuthMailThrottleConfig} for why that path deliberately does not", whose two halves
     * contradicted each other: a path that does not spend a budget cannot spend it. The
     * contradiction was the visible end of a real gap, and register is on the mechanism now
     * ({@code AuthMailRegisterThrottleTest}) — on {@code EmailType.REGISTRATION_VERIFICATION},
     * which is deliberately NOT the bucket these tests measure.
     */
    private User newUser(UserStatus status) {
        var user = new User();
        user.setEmail(address());
        user.setDisplayName("Throttle Subject");
        user.setPasswordHash("{noop}irrelevant");
        user.setStatus(status);
        user.setSystemRole(SystemRole.USER);
        return userRepository.save(user);
    }

    /** A fresh inbox per subject: the ceilings live in a table, so addresses cannot be reused. */
    private static String address() {
        return "hd202-" + UUID.randomUUID() + "@example.test";
    }
}
