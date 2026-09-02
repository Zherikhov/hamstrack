package com.hamstrack.workspace;

import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailSendCounts;
import com.hamstrack.common.mail.MailSendEvent;
import com.hamstrack.common.mail.MailSendEventRepository;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>The global per-recipient daily cap: what it counts, and what its {@code Retry-After} is
 * allowed to publish</strong> (HD-190 sections 6.3 and 8.1).
 *
 * <p>This is the one ceiling whose count includes strangers, so it is the one that discloses
 * anything at all: at the sixth invitation an innocent admin learns that this address has received
 * invitations from workspaces they cannot see. That bit is accepted — bounded, only reachable by a
 * caller who already holds {@code workspace.member.manage} and already knows the address, free on
 * the first five, and it buys a harassment control that cannot be bought any other way. Everything
 * in this file is about keeping it to that.
 *
 * <h2>Two things are sealed here, and they failed in opposite directions</h2>
 * <strong>The arithmetic</strong>
 * ({@link #theCountCountsOwnSendsIndividuallyAndEveryOtherSenderOnce()}). Counting raw sends — the
 * obvious reading, and what shipped first — inverted the control into a weapon: one account, five
 * legal sends spaced past the cooldown, and for the rest of the day no workspace on the instance
 * could invite that named person. Counting only <em>distinct</em> senders overcorrects the other way
 * and lets one account ring the doorbell all day. The shipped rule counts both halves, and the
 * difference between the three is invisible in any single scenario — which is why all four are here
 * rather than the one that happens to fail today.
 *
 * <p><strong>The wait</strong> ({@link #theDailyWaitNamesAnAbsoluteDeadlineAndNotARemainingDuration()}).
 * The number is derived from <em>another tenant's</em> send instant, so it is coarsened. The first
 * cut rounded the remaining <em>duration</em>, which looks identical in a single response and is
 * defeatable: anchored to {@code now}, the emitted value steps down by a whole quantum as time
 * passes, and the instant at which it steps <em>is</em> the hidden deadline.
 * <strong>Consequently an assertion that {@code retryAfter % 900 == 0} PASSES on the broken variant
 * and FAILS on the fixed one</strong> — it would be a seal certifying the defect, and this file
 * demonstrates that rather than claiming it in prose.
 *
 * <h2>Why the arithmetic is asserted on the count and the behaviour on the throttle</h2>
 * The cooldown is checked before the daily cap, so through any real call path a sender's
 * <em>second</em> send to one address inside an hour is refused as a cooldown and the daily count
 * never gets a say. That makes "my own five sends fill my own cap" unreachable in-process without
 * travelling an hour forward — so it is asserted where it is actually decided, on
 * {@link MailSendCounts}, and the reachable half is driven through the throttle.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class RecipientDailyCapTest {

    /** {@code app.invites.max-per-recipient-per-day}'s shipped default. */
    private static final int CAP = 5;

    /** {@code RecipientMailThrottle.DAILY_RETRY_QUANTUM_SECONDS}, restated so drift is visible. */
    private static final long QUANTUM = 900;

    /** {@code app.invites.recipient-cooldown-minutes}' shipped default, in seconds. */
    private static final long COOLDOWN_SECONDS = 60 * 60;

    @MockitoBean JavaMailSender mailSender;

    @Autowired RecipientMailThrottle throttle;
    @Autowired MailSendEventRepository repository;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    // ================================================================ the arithmetic

    /**
     * <strong>Your own sends count one each; every other sender counts once, however many times
     * they sent.</strong> Four shapes, because no single one of them distinguishes the shipped rule
     * from either of the two it replaced.
     *
     * <ol>
     *   <li><strong>Five sends from one other sender count as ONE.</strong> The case that
     *       distinguishes the shipped rule from raw counting, and the denial-of-onboarding weapon
     *       raw counting was: one account, five legal sends, and every other workspace on the
     *       instance locked out of inviting that named human for the rest of the day, at about five
     *       requests a day, indefinitely, invisible to both volume alerts.</li>
     *   <li><strong>Five distinct other senders count as FIVE.</strong> The case that distinguishes
     *       it from counting nothing, and the harassment shape the cap exists for — five throwaway
     *       accounts inviting one victim once each sit under every sender-keyed ceiling and are
     *       still a doorbell.</li>
     *   <li><strong>Five of your own count as FIVE.</strong> The case that distinguishes it from
     *       counting only distinct senders, which would have let one account send all day bounded
     *       by nothing but its own cooldown.</li>
     *   <li><strong>Three of yours plus two strangers is five.</strong> The halves add; neither is
     *       a ceiling of its own.</li>
     * </ol>
     */
    @Test
    void theCountCountsOwnSendsIndividuallyAndEveryOtherSenderOnce() {
        var self = UUID.randomUUID();

        var noisy = address("noisy");
        var oneOtherSender = UUID.randomUUID();
        for (int i = 0; i < CAP; i++) {
            seedSend(noisy, oneOtherSender);
        }
        assertThat(countsFor(noisy, self).recipientWindow())
                .as("ONE other sender occupies ONE slot however much they sent. Counting these as "
                    + "five hands any single account the power to spend a stranger's entire daily "
                    + "allowance and deny every workspace on the instance the ability to invite "
                    + "that person — a denial of onboarding aimed at a named human, which is a "
                    + "worse thing than the harassment this cap bounds")
                .isEqualTo(1);

        var crowded = address("crowded");
        for (int i = 0; i < CAP; i++) {
            seedSend(crowded, UUID.randomUUID());
        }
        assertThat(countsFor(crowded, self).recipientWindow())
                .as("five DISTINCT other senders fill the cap — the harassment shape, and the one "
                    + "everything sender-keyed is invariant under, because an account costs one "
                    + "mailbox on a catch-all domain")
                .isEqualTo(CAP);

        var mine = address("mine");
        for (int i = 0; i < CAP; i++) {
            seedSend(mine, self);
        }
        var own = countsFor(mine, self);
        assertThat(own.recipientWindowOwn())
                .as("your OWN sends count one each, so one account is still capped at %d into one "
                    + "inbox. Counting only distinct senders would leave one account free to ring "
                    + "the doorbell all day, bounded by nothing but its own cooldown", CAP)
                .isEqualTo(CAP);
        assertThat(own.recipientWindowOtherSenders())
                .as("and you are never one of your own 'other senders'")
                .isZero();

        var mixed = address("mixed");
        for (int i = 0; i < 3; i++) {
            seedSend(mixed, self);
        }
        seedSend(mixed, UUID.randomUUID());
        seedSend(mixed, UUID.randomUUID());
        assertThat(countsFor(mixed, self).recipientWindow())
                .as("the two halves ADD. Neither is a ceiling of its own — that is what keeps the "
                    + "per-account bound and the per-inbox bound from being the same number")
                .isEqualTo(CAP);
    }

    /**
     * <strong>Both of those are bounds per ACCOUNT; the bound on the INBOX is quadratic in the
     * cap.</strong> Asserted as arithmetic rather than as a scenario, because it is the number
     * somebody raising {@code INVITE_MAX_PER_RECIPIENT_PER_DAY} is actually buying, and it is
     * written on that property in three places.
     *
     * <p>N colluding accounts each send until {@code own + (N-1)} reaches the cap, so one inbox
     * receives {@code N * (cap - N + 1)} in a day, maximised at {@code N = (cap+1)/2} and worth
     * {@code floor((cap+1)^2 / 4)}. Nine a day at the default rather than the hard five raw counting
     * gave — the accepted price of deleting the denial-of-onboarding weapon above — but it is
     * <em>superlinear</em>: doubling the cap triples the harassment ceiling.
     */
    @Test
    void thePerInboxCeilingIsQuadraticInTheCap() {
        assertThat(worstCasePerInbox(5))
                .as("the default. If this ever reads 5 again, the count has gone back to raw sends "
                    + "and the denial-of-onboarding weapon is back with it")
                .isEqualTo(9);
        assertThat(worstCasePerInbox(10))
                .as("doubling the cap TRIPLES this, which is the sentence on the property and the "
                    + "reason it says raise in small steps")
                .isEqualTo(30);
    }

    /**
     * HD-202's flows have no sender, and the SQL trap there is silent: {@code = NULL} is never true,
     * so folding anonymous rows into the ordinary query would have disabled the cooldown for exactly
     * those two paths while every count still looked plausible. They share <em>one</em> bucket per
     * key on purpose — "who submitted the form" is not knowable and must not be guessable, or an
     * attacker gets a fresh cooldown per request.
     *
     * <p>Asserted now, while the queries are young and nobody is depending on them, because the
     * ticket that will depend on them was forecast as "two policy beans and two call sites" — a
     * description under which nobody re-reads the SQL. (It landed as three of each, which is the
     * smaller half of what that forecast got wrong.)
     */
    @Test
    void anonymousSendsShareOneBucketPerKey() {
        var target = address("anonymous");
        for (int i = 0; i < 3; i++) {
            seedSend(target, null);
        }
        var otherSender = UUID.randomUUID();
        seedSend(target, otherSender);

        var anonymous = repository.countRecentForAnonymous(
                EmailType.INVITE.name(), MailAddresses.throttleKey(target),
                Instant.now().minusSeconds(COOLDOWN_SECONDS), Instant.now().minus(Duration.ofDays(1)),
                Instant.now().minus(Duration.ofDays(1)));

        assertThat(anonymous.samePair())
                .as("three anonymous sends are three sends from ONE bucket — an `= NULL` "
                    + "comparison would have made this 0 and silently disabled the cooldown for "
                    + "forgot-password and resend-verification")
                .isEqualTo(3);
        assertThat(anonymous.samePairLatest())
                .as("and the bucket has a last-send instant, or the cooldown has nothing to "
                    + "measure from and returns early")
                .isNotNull();
        assertThat(anonymous.recipientWindowOtherSenders())
                .as("an identified sender is one OTHER sender to the anonymous bucket")
                .isEqualTo(1);

        var identified = countsFor(target, otherSender);
        assertThat(identified.recipientWindowOtherSenders())
                .as("and symmetrically, all three anonymous sends collapse into ONE other sender "
                    + "as far as an identified caller is concerned — the nil-UUID sentinel exists "
                    + "so they can take part in a count(distinct) at all, since NULL is not equal "
                    + "to anything including itself")
                .isEqualTo(1);
    }

    // ================================================================ the behaviour

    /**
     * The reachable half of the arithmetic, driven through the real throttle: the first five
     * distinct senders cost nothing and the sixth is refused.
     *
     * <p>The refusal's terseness is the security property here, not brevity: "…because other
     * workspaces invited them", "…they already have an invitation" and "ask them to accept the one
     * they already have" each turn the one accepted bit of cross-tenant disclosure into a
     * description of another tenant's activity. Where the prescription rule and the disclosure rule
     * pull against each other, disclosure wins — and here they barely pull, because waiting
     * <em>is</em> the remedy.
     */
    @Test
    void theSixthDistinctSenderIsRefusedAndToldNothingAboutTheOtherFive() {
        var victim = address("terse");
        for (int i = 0; i < CAP; i++) {
            assertThat(attempt(victim, UUID.randomUUID()))
                    .as("send %d of %d must cost nothing — a ceiling that fired early would be "
                        + "spending honest admins' invitations on a control they cannot see",
                        i + 1, CAP)
                    .isEmpty();
        }

        var detail = attempt(victim, UUID.randomUUID())
                .orElseThrow(() -> new AssertionError(
                        "the sixth distinct account is where section 6.3 always said this trips, "
                        + "and the hybrid counting was chosen to keep that arithmetic where it was"))
                .getMessage();

        assertThat(detail)
                .as("the wording is what keeps this ceiling's disclosure at one bit")
                .contains("Invitations to this address are paused")
                .doesNotContain("workspace")
                .doesNotContain("already have")
                .doesNotContain("other");
        assertThat(detail)
                .as("it must still prescribe something its reader can perform, and waiting is it")
                .contains("Try again in");
    }

    // ================================================================ the Retry-After

    /**
     * <strong>The seal that must not certify the defect.</strong>
     *
     * <p>Two probes at genuinely different instants, both refused by the daily cap, must name the
     * <em>same absolute deadline</em>. That is the property; the emitted number is not, and neither
     * is its remainder.
     *
     * <p><strong>Why not simply assert {@code retryAfter % 900 == 0}.</strong> Because that is the
     * fingerprint of the <em>broken</em> variant. Rounding the remaining duration —
     * {@code ceil((deadline - now) / 900) * 900} — always emits a multiple of 900 and anchors the
     * quantum to {@code now}, so the value steps down by a whole quantum as time passes and the
     * instant at which it steps IS the hidden deadline: about ten probes by bisection, or the
     * minimum over a day of probes, recovers a stranger's send instant to within seconds. Rounding
     * the deadline instead makes every probe from every caller at every instant name the same
     * moment, and the emitted number then counts down one per second and is almost never a multiple
     * of 900. {@link #theTwoRoundingsAreToldApartByTheDeadlineAndNotByTheRemainder()} shows that
     * arithmetic directly, so the paragraph above is checkable rather than believed.
     *
     * <p><strong>And probing is free</strong> — a refusal writes nothing — which is exactly why the
     * header has to be coarsened at all.
     *
     * <p>The service's own {@code now} is not observable from here, so the deadline is recovered
     * from the bracket: it lies in {@code [before + retryAfter, after + retryAfter]}, and — being a
     * multiple of the quantum — there is exactly one candidate in a bracket that narrow. A bracket
     * containing <em>no</em> multiple of the quantum is itself the diagnosis: the emitted number was
     * a rounded duration, not a rounded deadline.
     */
    @Test
    void theDailyWaitNamesAnAbsoluteDeadlineAndNotARemainingDuration() throws Exception {
        var victim = address("deadline");
        for (int i = 0; i < CAP; i++) {
            seedSend(victim, UUID.randomUUID());
        }

        var first = probeDeadline(victim);
        // Strictly more than a second, because the whole distinction lives in how the answer
        // changes as `now` advances, and `now` is measured in whole seconds. Two probes inside one
        // second cannot tell the two roundings apart, and a test that took them would be green
        // against both.
        Thread.sleep(1_200);
        var second = probeDeadline(victim);

        assertThat(second.retryAfter())
                .as("the emitted number must COUNT DOWN as time passes: only its target sits on a "
                    + "quantum boundary. A value that did not move — or that stepped by a whole "
                    + "%d — is the duration-rounded variant, whose step instant is the hidden "
                    + "deadline", QUANTUM)
                .isLessThan(first.retryAfter());

        assertThat(second.deadlineEpochSecond())
                .as("two probes taken at different instants must name the SAME absolute deadline. "
                    + "This is the entire control: repetition then buys nothing, and what is "
                    + "published is which %d-second bucket a stranger's send instant falls in, "
                    + "once, rather than that instant to the second", QUANTUM)
                .isEqualTo(first.deadlineEpochSecond());

        assertThat(first.deadlineEpochSecond() % QUANTUM)
                .as("the DEADLINE is what is rounded")
                .isZero();
    }

    /**
     * The two roundings, side by side, over a day of simulated {@code now}s — so that "an assertion
     * on the remainder would seal the defect" is demonstrated rather than asserted in prose.
     *
     * <p>Run against the shipped private helper by reflection, because the distinction is arithmetic
     * and deserves to be checked at full resolution rather than through two probes a second apart.
     * The sibling test above is what proves this arithmetic is the one actually on the wire.
     */
    @Test
    void theTwoRoundingsAreToldApartByTheDeadlineAndNotByTheRemainder() throws Exception {
        var bucketed = RecipientMailThrottle.class.getDeclaredMethod(
                "secondsUntilBucketedDeadline", Instant.class, Instant.class);
        bucketed.setAccessible(true);

        // A deadline deliberately NOT on a quantum boundary — the ordinary case, and the only one
        // in which the two roundings differ at all.
        var deadline = Instant.ofEpochSecond(1_800_000_000L + 137);
        var shippedDeadlines = new LinkedHashSet<Long>();
        var shippedRemainders = new LinkedHashSet<Long>();
        var defeatableDeadlines = new LinkedHashSet<Long>();
        var defeatableRemainders = new LinkedHashSet<Long>();

        for (long back = 1; back <= 86_400; back += 7) {
            var now = deadline.minusSeconds(back);
            long shipped = (long) bucketed.invoke(null, deadline, now);
            long defeatable = durationRounded(deadline, now);
            shippedDeadlines.add(now.getEpochSecond() + shipped);
            shippedRemainders.add(shipped % QUANTUM);
            defeatableDeadlines.add(now.getEpochSecond() + defeatable);
            defeatableRemainders.add(defeatable % QUANTUM);
        }

        assertThat(shippedDeadlines)
                .as("SHIPPED: every probe over a whole day names one absolute moment, so probing "
                    + "repeatedly buys nothing")
                .hasSize(1);
        assertThat(defeatableDeadlines)
                .as("DEFEATABLE: rounding the duration makes the named moment move with `now`, and "
                    + "its minimum over a day of probes lands within seconds of the true deadline "
                    + "— which is a stranger's send instant, recovered off a refusal anybody can "
                    + "provoke for free")
                .hasSizeGreaterThan(1);

        assertThat(defeatableRemainders)
                .as("THE TRAP: the broken variant always emits a multiple of %d, so an assertion "
                    + "of `retryAfter %% %d == 0` passes on it. Such an assertion would be a seal "
                    + "certifying the bug", QUANTUM, QUANTUM)
                .containsExactly(0L);
        assertThat(shippedRemainders)
                .as("and the fixed variant almost never emits one, so that same assertion would "
                    + "FAIL on the correct code. The remainder is the fingerprint of the defect, "
                    + "not of the fix — the property to assert is the one above")
                .hasSizeGreaterThan(1);
    }

    /**
     * <strong>The cooldown's wait is left exact, and that is deliberate</strong> — do not "unify" it
     * with the coarsening above.
     *
     * <p>The instant it is derived from is the caller's own last send to this inbox. There is
     * nothing there to hide, and every second of precision is precision about the reader's own
     * history; coarsening it would cost an honest sender a wait they did not earn in exchange for
     * concealing from them something they did themselves. The two waits describe different people's
     * actions, which is the whole reason one of them is rounded.
     *
     * <p>Told apart from a bucketed value by which side of the cooldown the number lands on: an
     * exact wait is a shade under 3600, while rounding this deadline UP to the next quantum
     * boundary would put it in {@code (3600, 4500]}. So the assertion discriminates rather than
     * merely describing.
     */
    @Test
    void theCooldownWaitIsExactBecauseTheInstantIsTheCallersOwn() {
        var invitee = address("cooldown-exact");
        var sender = UUID.randomUUID();

        assertThat(attempt(invitee, sender)).isEmpty();

        var refusal = attempt(invitee, sender)
                .orElseThrow(() -> new AssertionError("the same sender must be cooled down"));

        assertThat(refusal.getRetryAfterSeconds())
                .as("a fresh 60-minute cooldown named to the second. A value ABOVE %d would mean "
                    + "this deadline had been rounded up to a quantum boundary the way the daily "
                    + "cap's is — which would be coarsening the caller's own history away from "
                    + "them for no disclosure benefit at all", COOLDOWN_SECONDS)
                .isGreaterThan(COOLDOWN_SECONDS - 30)
                .isLessThanOrEqualTo(COOLDOWN_SECONDS);

        assertThat(refusal.getMessage())
                .as("and its wording names the address — the caller's own past action, so saying "
                    + "it discloses nothing — where the daily cap's may not")
                .contains(invitee);
    }

    // ================================================================ fixture

    private record Probe(long retryAfter, long deadlineEpochSecond) {}

    /**
     * One refused attempt, bracketed by wall-clock reads, with the absolute deadline recovered from
     * the bracket. See the calling test for why the deadline rather than the number is the answer.
     */
    private Probe probeDeadline(String recipient) {
        long before = Instant.now().getEpochSecond();
        var refusal = attempt(recipient, UUID.randomUUID())
                .orElseThrow(() -> new AssertionError(
                        "the daily cap must be full for this probe to say anything"));
        long after = Instant.now().getEpochSecond();

        var candidates = new ArrayList<Long>();
        for (long candidate = before + refusal.getRetryAfterSeconds();
             candidate <= after + refusal.getRetryAfterSeconds(); candidate++) {
            if (candidate % QUANTUM == 0) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() != 1) {
            fail("`now + Retry-After` (%d..%d) contains %d multiples of %d, not one. Zero is the "
                 + "signature of a REMAINING DURATION rounded to the quantum instead of a DEADLINE "
                 + "rounded to it — the defeatable variant, whose emitted value steps down by a "
                 + "whole quantum as time passes and thereby publishes a stranger's send instant "
                 + "to anybody willing to bisect the step.",
                 before + refusal.getRetryAfterSeconds(), after + refusal.getRetryAfterSeconds(),
                 candidates.size(), QUANTUM);
        }
        return new Probe(refusal.getRetryAfterSeconds(), candidates.get(0));
    }

    /**
     * One real attempt through {@link RecipientMailThrottle}, in its own transaction the way a
     * request is. Returns the refusal, or empty when the send was allowed — in which case the event
     * row is committed, exactly as it would be on the invite path.
     */
    private Optional<RateLimitedException> attempt(String recipient, UUID sender) {
        try {
            transactions.executeWithoutResult(status ->
                    throttle.requireAndRecord(EmailType.INVITE, recipient, sender,
                            UUID.randomUUID()));
            return Optional.empty();
        } catch (RateLimitedException e) {
            return Optional.of(e);
        }
    }

    /** What the ceilings compare against, read the way the throttle reads it. */
    private MailSendCounts countsFor(String recipient, UUID sender) {
        var now = Instant.now();
        return repository.countRecentForSender(
                EmailType.INVITE.name(), MailAddresses.throttleKey(recipient), sender,
                MailSendEventRepository.ANONYMOUS_SENDER,
                now.minusSeconds(COOLDOWN_SECONDS), now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofDays(1)));
    }

    /**
     * A recorded send that did not go through the throttle.
     *
     * <p>Needed because the cooldown makes "the same sender, five times" unreachable through any
     * real call path inside an hour — and that scenario is precisely the one the cap's counting rule
     * turns on. Seeded rather than faked: it is this feature's own bookkeeping table, written with
     * the real {@link MailAddresses#throttleKey} so the row is counted by the same key a genuine
     * send would have been counted by. A key computed any other way here would make every assertion
     * in this file pass against a count of zero.
     *
     * @param sender {@code null} for the anonymous bucket HD-202's flows will use
     */
    private void seedSend(String recipient, UUID sender) {
        transactions.executeWithoutResult(status -> {
            var event = new MailSendEvent();
            event.setEmailType(EmailType.INVITE.name());
            event.setRecipientEmail(recipient);
            event.setRecipientKey(MailAddresses.throttleKey(recipient));
            event.setSenderUserId(sender);
            event.setWorkspaceId(UUID.randomUUID());
            em.persist(event);
        });
    }

    /** {@code ceil((deadline - now) / q) * q} — the variant that shipped in round 1. */
    private static long durationRounded(Instant deadline, Instant now) {
        long remaining = Math.max(Duration.between(now, deadline).toSeconds(), 1);
        return ((remaining + QUANTUM - 1) / QUANTUM) * QUANTUM;
    }

    /** {@code max over N of N * (cap - N + 1)} — the messages one inbox can receive in a day. */
    private static int worstCasePerInbox(int cap) {
        int worst = 0;
        for (int colluding = 1; colluding <= cap; colluding++) {
            worst = Math.max(worst, colluding * (cap - colluding + 1));
        }
        return worst;
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }
}
