package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailSendCounts;
import com.hamstrack.common.mail.MailSendEvent;
import com.hamstrack.common.mail.MailSendEventRepository;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.persistence.LockTimeout;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <strong>The recipient-keyed ceilings on outbound mail — one mechanism for every path that lets a
 * caller choose a stranger's address</strong> (HD-190 §6.2/§6.3,
 * {@code docs/adr/0015-recipient-keyed-mail-throttle-persisted.md}).
 *
 * <h2>Why this exists</h2>
 * Before it, any account that could log in could make Hamstrack send mail to any address on the
 * internet, without limit, by typing that address into the invite box of a workspace it had created
 * seconds earlier. The attack it closes is <em>one victim, many workspaces the abuser creates</em>:
 * a per-workspace bound inspects the wrong dimension (each workspace holds one invitation), and so
 * does the per-workspace uniqueness HD-133 later added — a fresh workspace is a fresh index key, so
 * every send in this attack is the abuser's first. Only a key that ignores the workspace sees it.
 *
 * <h2>What "the key" means, and why it is not the address</h2>
 * Every ceiling counts {@code MailAddresses.throttleKey(...)} — one key per destination
 * <strong>inbox</strong>, not one per spelling. Keyed on the raw lower-cased address the whole
 * mechanism was decorative: {@code victim+1@}, {@code victim+2@} and
 * {@code v.i.c.t.i.m@googlemail.com} are distinct strings that reach one human, so the ticket's
 * attack was re-spelled at the cost of one keystroke and both counts read zero every time.
 * <strong>For a ceiling, over-folding is the fail-safe direction</strong> — an extra match raises a
 * count and refuses sooner, where under-folding hands out free sends. This is the reverse of the
 * invite <em>redemption</em> path (HD-120), where an extra match lets the wrong person accept and
 * addresses are compared exactly; the direction of harm decides, and it points opposite ways. Mail
 * is always sent to the submitted address, never to the key.
 *
 * <p><strong>Fail-safe is not free, and its cost differs per ceiling — the milder one is not the
 * bound.</strong> An over-fold costs {@link #refuseIfCoolingDown} an honest sender a wait they did
 * not earn, which is their own inconvenience and expires. It costs
 * {@link #refuseIfRecipientDailyCapReached} a slot belonging to <em>a different, innocent person</em>
 * who merely shares a folded key, because that ceiling is sender-invariant — an invitation denied to
 * somebody who did nothing, for a reason nobody involved can see. Which is why the folding rules are
 * published facts about delivery and standard normalisations only, never heuristics; the argument
 * lives on {@code MailAddresses.throttleKey}, where the person changing them is standing.
 *
 * <h2>Why it is not a {@code PrincipalThrottleInterceptor}</h2>
 * Not merely because the recipient is in the request body (an interceptor has no argument
 * resolvers). The load-bearing reason is <strong>tenancy</strong>.
 * {@link PerPrincipalMinuteBudget} is spent before the controller resolves anything, and its javadoc
 * argues that this is safe precisely because the key is the caller: "the 429 is identical for a real
 * workspace, a nonexistent one and somebody else's". A <em>recipient</em>-keyed refusal does not
 * have that property — spent in an interceptor it would answer, to a caller who is not a member of
 * the workspace in the path, a question about invitation traffic elsewhere in the instance. That is
 * a 429 where the tenancy contract requires 404. So this runs <strong>inside the service, after the
 * workspace has been resolved</strong>, and the structural guarantee that a future mail path does
 * not forget it lives on a different axis: {@code MailThrottleCoverageTest} seals the set of
 * <em>mailers</em>, the way {@code ThrottleCoverageTest} seals the set of <em>paths</em>.
 *
 * <h2>Check and record are one call, deliberately</h2>
 * A separate {@code record(...)} would be the line the next mail path forgets, and forgetting it
 * fails open and silently — the ceilings would simply stop counting. So
 * {@link #requireAndRecord} refuses or writes the event; there is no way to do one without the
 * other. The row is written <em>per attempt that gets past the ceilings</em>, not per delivered
 * message: delivery is async and best-effort, and an abuse control that only counted successes would
 * be defeated by addresses that bounce — which is what a spam run consists of.
 *
 * <h2>Enumeration</h2>
 * Every ceiling is keyed on the <strong>submitted</strong> address (folded to its inbox key) and
 * counted identically whether or not a {@code users} row exists. No branch here reads {@code users};
 * there is no early return keyed on account existence; and the work is one indexed statement whose
 * plan does not depend on it. This is the argument already written on
 * {@code RateLimitService.checkLoginAllowed}, which keys on the submitted email "whether or not the
 * account exists, so the limiter itself cannot be used to probe which emails are registered".
 *
 * <h2>Exactness and cost, and the invariant that keeps the lock cheap</h2>
 * One extra {@code SELECT} and one extra {@code INSERT} on a low-frequency write. The counts are
 * read then written, so before reading it takes {@code pg_advisory_xact_lock(hashtext(key))}
 * — preceded by {@link LockTimeout#applyToCurrentTransaction()}, bound then lock, the standing rule.
 * That serialises everything aimed at <em>one inbox</em>, which is where exactness matters, and
 * contends with nothing else. {@code hashtext} is 32-bit, so two unrelated keys will occasionally
 * serialise; that is a lock, not a decision. Because the lock is held to commit and the insert
 * becomes visible at commit, a queued request sees the row it waited for.
 *
 * <p><strong>The critical section must stay lock → one aggregate SELECT → optional exists → one
 * INSERT → commit. Nothing slow and nothing caller-controlled may ever be added between
 * {@code require...} and the commit that follows it.</strong> The key is a recipient address, and an
 * address is something two tenants legitimately share — so any wait held inside this section is a
 * wait one tenant can impose on another. Sending the mail itself is outside this section because
 * the call site registers it on {@code AfterCommit} (HD-181) — after the commit that releases this
 * lock. It is <strong>not</strong> the hand-off to the mail pool that keeps it out, as this
 * paragraph used to say: that pool was bounded with {@code CallerRunsPolicy}, so a full queue turned
 * the dispatch into an inline send, under exactly the load where an SMTP round trip held across
 * tenants would hurt most. HD-208 has since removed the inline branch, so the hand-off no longer
 * betrays that claim — but the claim is still the wrong one to rest on, because it is a property of
 * the pool's current policy and not of this section. What must not appear between
 * {@code require...} and the commit is anything slow, however it is spelled, and that rule survives
 * any policy.
 *
 * <p>These ceilings are consequently <strong>cluster-wide and exact</strong> — the first limiter in
 * this product that does not divide by the replica count, because its state is in PostgreSQL. The
 * per-sender volume budget ({@link InviteSenderVolumeBudget}) is in memory and degrades the usual
 * way; the asymmetry is deliberate and is written up in {@code docs/self-hosting.md}.
 */
@Slf4j
@Service
public class RecipientMailThrottle {

    /**
     * Every recipient-keyed ceiling that is not the cooldown is measured over this window.
     *
     * <p><strong>Widening it is a two-file edit, and the second file will not remind you.</strong>
     * The retention sweep has to outlast every ceiling window, or a swept row silently shortens the
     * ceiling that counted it — and the pair is asserted at startup by
     * {@code InviteProperties.isRetentionLongerThanWidestCeilingWindow}, which necessarily has to
     * know what the widest window is. It knows it as {@code InviteProperties.FIXED_CEILING_WINDOW_MINUTES},
     * a hand-copied 1440 that says one day because this constant says one day. Widen this one alone
     * and that copy does not move: the assertion keeps passing while guaranteeing less than its
     * message says, with no error and no log line. So the assertion cannot police this constant —
     * only a test asserting the two are equal can, and this edit is where that test earns its keep.
     */
    private static final Duration DAY = Duration.ofDays(1);

    /**
     * The daily cap's {@code Retry-After} names a <em>deadline</em> rounded up to the next multiple
     * of this — never a <em>duration</em> rounded to it. {@link #secondsUntilBucketedDeadline} is
     * where that distinction is argued, and it is the whole of the control;
     * {@link #refuseIfRecipientDailyCapReached} is where only this one ceiling is coarsened at all.
     */
    private static final long DAILY_RETRY_QUANTUM_SECONDS = 900;

    /**
     * Serialises concurrent sends aimed at one recipient key for the rest of the transaction.
     * Positional parameters and an explicit {@code CAST} rather than {@code ::bigint}, so nothing in
     * the statement can be mistaken for a named parameter; the wrapping {@code SELECT 1} exists
     * because {@code pg_advisory_xact_lock} returns {@code void}, which is awkward to map back.
     */
    private static final String LOCK_RECIPIENT =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(hashtext(?1) AS bigint))) AS mail_throttle_lock";

    private final Map<EmailType, MailThrottlePolicy> policies = new EnumMap<>(EmailType.class);
    private final MailSendEventRepository repository;
    private final EntityManager entityManager;
    private final LockTimeout lockTimeout;
    private final RateLimitProperties rateLimitProperties;
    private final ProductMetrics metrics;

    public RecipientMailThrottle(List<MailThrottlePolicy> policies,
                                 MailSendEventRepository repository,
                                 EntityManager entityManager,
                                 LockTimeout lockTimeout,
                                 RateLimitProperties rateLimitProperties,
                                 ProductMetrics metrics) {
        for (var policy : policies) {
            var clash = this.policies.put(policy.type(), policy);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two MailThrottlePolicy beans claim " + policy.type()
                        + ". One policy per EmailType — two would make which ceiling applies depend "
                        + "on bean registration order.");
            }
        }
        this.repository = repository;
        this.entityManager = entityManager;
        this.lockTimeout = lockTimeout;
        this.rateLimitProperties = rateLimitProperties;
        this.metrics = metrics;
    }

    /**
     * Which kinds of mail are recipient-throttled, as a structural fact rather than as a list
     * somebody maintains.
     *
     * <p>Exposed for {@code MailThrottleCoverageTest}, which inverts {@code ThrottleCoverageTest}'s
     * axis from <em>path</em> to <em>mailer</em>: every {@code EmailType} must be either in this set
     * or in the test's {@code EXEMPT} set with a written reason, so a fourth kind of outbound mail
     * fails one test that names what to do, at the commit that adds it.
     */
    public Set<EmailType> throttledTypes() {
        return Set.copyOf(policies.keySet());
    }

    /**
     * Spend the recipient-keyed ceilings for one send and record it, or refuse with a 429 naming a
     * wait the caller can act on.
     *
     * <p><strong>Call this AFTER tenancy and authorization have been resolved.</strong> A refusal
     * here is only ever seen by a caller who has already proven they may make this request — see the
     * class javadoc for why that ordering is not negotiable.
     *
     * @param type            which mail is about to be sent; unthrottled types are a no-op
     * @param recipientEmail  the recipient as submitted (lower-cased at the boundary). It is stored
     *                        verbatim so the refusal can echo it, and <em>counted</em> only through
     *                        {@code MailAddresses.throttleKey}, which is derived here so no call
     *                        site can forget it
     * @param senderUserId    the principal on whose behalf the mail is sent, or {@code null} for the
     *                        anonymous flows, which share one bucket per key
     * @param workspaceId     forensic breadcrumb, written and never queried; {@code null} where there
     *                        is no workspace
     * @param cooldownAddendum an extra sentence for the cooldown refusal, evaluated <strong>only</strong>
     *                        if the cooldown fires — because a claim about a row has to be checked
     *                        against the row, and doing that check on the happy path would be a query
     *                        nobody reads. May be {@code null}
     * @throws RateLimitedException 429 with {@code Retry-After}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAndRecord(EmailType type, String recipientEmail, UUID senderUserId,
                                 UUID workspaceId, Supplier<String> cooldownAddendum) {
        var policy = policies.get(type);
        // An unthrottled mail type is a no-op rather than an error, and nothing is recorded for it
        // either: the row exists only to be counted by a ceiling. A type with no policy is one
        // MailThrottleCoverageTest holds a written exemption for.
        if (policy == null) {
            return;
        }

        // Derived HERE rather than taken from the caller, so "the ceilings count inboxes" is true by
        // construction and cannot be undone by a future call site that folds its address differently.
        var recipientKey = MailAddresses.throttleKey(recipientEmail);

        // THE MASTER SWITCH TURNS OFF THE CEILINGS, NOT THE BOOKKEEPING. Recording while limiting is
        // off costs one insert on a low-frequency write and buys two things worth more: the
        // operator's forensic trail (and the domain-only INFO line, the only bridge between a volume
        // alert and "who") keeps working, and an instance that toggles the switch back on resumes
        // with real history rather than a blank window an abuser can walk through. The retention
        // sweep runs regardless, for the same reason, so this cannot grow unbounded.
        if (rateLimitProperties.enabled()) {
            // Bound the wait, then take the lock — the standing rule, and the order matters: a bound
            // applied after a locking read bounds nothing. The lock is on the KEY, not the address:
            // locking the spelling would serialise nothing that the counts then aggregate together.
            lockTimeout.applyToCurrentTransaction();
            entityManager.createNativeQuery(LOCK_RECIPIENT)
                    .setParameter(1, recipientKey)
                    .getSingleResult();

            var now = Instant.now();
            var cooldownFrom = now.minus(policy.cooldown());
            var dayFrom = now.minus(DAY);
            var from = cooldownFrom.isBefore(dayFrom) ? cooldownFrom : dayFrom;
            var counts = senderUserId == null
                    ? repository.countRecentForAnonymous(type.name(), recipientKey, cooldownFrom, dayFrom, from)
                    : repository.countRecentForSender(type.name(), recipientKey, senderUserId,
                            MailSendEventRepository.ANONYMOUS_SENDER, cooldownFrom, dayFrom, from);

            // The cooldown is checked first on purpose. When both would fire, its message describes
            // the caller's OWN past action and discloses nothing, while the daily cap's necessarily
            // says something about traffic the caller cannot see. Refuse with the cheaper disclosure.
            refuseIfCoolingDown(policy, recipientEmail, counts, now, cooldownAddendum);
            refuseIfRecipientDailyCapReached(policy, counts, now);
        }

        record(type, recipientEmail, recipientKey, senderUserId, workspaceId);
    }

    /** Overload for paths with no row to make a claim about. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAndRecord(EmailType type, String recipientEmail, UUID senderUserId,
                                 UUID workspaceId) {
        requireAndRecord(type, recipientEmail, senderUserId, workspaceId, null);
    }

    /**
     * The per-(sender, recipient) cooldown.
     *
     * <p><strong>Its wait is exact, and deliberately so.</strong> The instant it is derived from is
     * the caller's own last send to this inbox, so there is nothing here to hide and every second of
     * precision is precision about the reader's own history. Do not "unify" this with the
     * coarsening in {@link #refuseIfRecipientDailyCapReached} — the two waits describe different
     * people's actions, which is the whole reason one of them is rounded.
     */
    private void refuseIfCoolingDown(MailThrottlePolicy policy, String recipientEmail,
                                     MailSendCounts counts, Instant now,
                                     Supplier<String> cooldownAddendum) {
        if (counts.samePair() == 0 || counts.samePairLatest() == null) {
            return;
        }
        long retryAfter = secondsUntil(counts.samePairLatest().plus(policy.cooldown()), now);
        metrics.rateLimitHit(policy.cooldownKind());
        var addendum = cooldownAddendum == null ? null : cooldownAddendum.get();
        throw new RateLimitedException(
                policy.wording().cooldown(recipientEmail, RetryWait.describe(retryAfter), addendum),
                retryAfter);
    }

    /**
     * The global per-recipient daily cap — the one ceiling whose count includes strangers.
     *
     * <p><strong>Counted as "my own sends, one each; every other sender, once."</strong> Counting
     * raw sends let a single account spend a victim's entire daily allowance and thereby block every
     * other workspace on the instance from inviting that person, for ~5 requests a day, indefinitely,
     * invisible to both volume alerts — a denial of onboarding aimed at a named human, which is a
     * worse thing than the harassment the cap exists to bound. Counting only distinct senders would
     * have gone too far the other way and let one account ring the doorbell all day, bounded by
     * nothing but its own cooldown. Counting both halves keeps §6.3's arithmetic where it was
     * (five throwaway accounts inviting one victim once each still trip it at the sixth) while
     * capping any one account's contribution at the cap itself and at one slot as far as everyone
     * else is concerned.
     *
     * <p><strong>Both of those are per-ACCOUNT bounds; the bound on the INBOX is quadratic in the
     * cap.</strong> N colluding accounts each sending until {@code own + (N-1)} reaches the cap
     * deliver {@code N * (cap - N + 1)} messages to one address in a day — {@code (cap+1)^2 / 4} at
     * the worst N, so nine a day at the default of five rather than five. That is the accepted price
     * of not handing one account a way to spend a stranger's whole allowance, but it is superlinear:
     * doubling the cap triples the harassment ceiling. Written on
     * {@code InviteProperties.maxPerRecipientPerDay} as well, because that is where somebody stands
     * when they decide to raise it.
     *
     * <p><strong>The wait is coarsened before it leaves the process, and only here.</strong>
     * {@code GlobalExceptionHandler} emits {@code Retry-After} verbatim, and the caller knows
     * {@code now} — so an exact number would publish, to the second, the instant of the oldest send
     * to this address <em>from anybody in the instance</em>. That is a tenant's activity timeline
     * reconstructed one address at a time, off a refusal any member can provoke for free. Rounding UP
     * costs a caller who is already being told to come back later nothing, and the prose
     * ({@link RetryWait#describe}) is coarser still. <strong>What is rounded is the DEADLINE, not the
     * remaining duration</strong> — {@link #secondsUntilBucketedDeadline}, where the difference
     * between those two is the entire control and not an implementation detail.
     *
     * <p><strong>What the coarsening buys, stated so nobody has to over-claim it.</strong> It does
     * not hold this header to one bit. A caller who keeps probing until the refusal <em>lifts</em>
     * learns the deadline at their polling resolution, and no shape of {@code Retry-After} closes
     * that — being able to retry against a sliding window is what a retryable refusal means. What
     * the coarsening removes is the <strong>cheap silent oracle</strong>: a single refused request
     * that hands back a stranger's send instant to the second, plus a handful more that converge on
     * it exactly. After it, that instant costs continuous polling across most of a day, bounded by
     * whatever volume budget the calling path spends before it reaches here, and counted in the
     * refusal metrics.
     *
     * <p><strong>On the invite path that bound and this quantum are of the same order, which is the
     * reason the residual stays a residual.</strong> Every probe spends one unit of
     * {@code app.invites.max-per-sender-per-day} (default 100) and a day holds 96 quanta of 900 s,
     * so an account can just about afford to sit at every bucket boundary for a day and no more —
     * uniform polling therefore recovers the deadline at roughly the resolution the header already
     * published. Not a matched pair in the strong sense, and it should not be sold as one: a caller
     * who has been told the bucket can spend an hour's budget (default 20) inside that single 900 s
     * window and narrow the instant to tens of seconds, paying an hour of their own invitations and
     * a visible spike in the refusal metrics for it. What no amount of polling buys is any of the
     * OTHER senders' instants — the deadline is the oldest send's alone.
     *
     * <p>The <em>wording</em> is what keeps this ceiling's stated one-bit disclosure at
     * one bit ({@code InviteProperties.maxPerRecipientPerDay}); the header raises the price of the
     * rest, which is the most a header can do.
     *
     * <p>The instant itself is a lower bound rather than the exact moment a slot frees: ageing out
     * one of several sends by the same other sender does not free that sender's slot. Under-stating
     * the wait is the safe direction — the caller retries and is refused again.
     */
    private void refuseIfRecipientDailyCapReached(MailThrottlePolicy policy, MailSendCounts counts,
                                                  Instant now) {
        if (counts.recipientDay() < policy.maxPerRecipientPerDay() || counts.recipientDayOldest() == null) {
            return;
        }
        long retryAfter = secondsUntilBucketedDeadline(counts.recipientDayOldest().plus(DAY), now);
        metrics.rateLimitHit(policy.recipientDailyKind());
        throw new RateLimitedException(
                policy.wording().recipientDaily(RetryWait.describe(retryAfter)), retryAfter);
    }

    private void record(EmailType type, String recipientEmail, String recipientKey,
                        UUID senderUserId, UUID workspaceId) {
        var event = new MailSendEvent();
        event.setEmailType(type.name());
        event.setRecipientEmail(recipientEmail);
        event.setRecipientKey(recipientKey);
        event.setSenderUserId(senderUserId);
        event.setWorkspaceId(workspaceId);
        entityManager.persist(event);
        // The bridge between an alert that cannot carry ids and an operator who needs to know who
        // (§10.1). DOMAIN ONLY, never the local part. Successful sends only, so it is bounded by the
        // very ceilings above — a refused caller in a loop cannot make this a log-flooding vector,
        // which is why the REFUSALS are metrics and not log lines.
        log.info("mail send allowed: type={} sender={} workspace={} recipientDomain={}",
                type, senderUserId, workspaceId, MailAddresses.domainOf(recipientEmail));
    }

    /** Never below 1: a {@code Retry-After: 0} invites an immediate retry that is refused again. */
    private static long secondsUntil(Instant target, Instant now) {
        return Math.max(Duration.between(now, target).toSeconds(), 1);
    }

    /**
     * Seconds until the first quantum boundary strictly after {@code deadline} — <strong>the
     * DEADLINE is rounded, the duration is not</strong>.
     *
     * <p><strong>Rounding the duration is the defeatable variant, and one probe cannot tell them
     * apart.</strong> {@code ceil((deadline - now) / q) * q} anchors the quantum to {@code now}, so
     * the emitted number steps DOWN by a whole quantum as {@code now} advances — and the instant at
     * which it steps <em>is</em> the deadline. Bisecting that step takes about ten probes over a
     * quarter of an hour; taking the minimum over a day of probes lands within seconds of it, and a
     * few accounts probing together land within one. Rounding the deadline makes every probe, from
     * every caller, at every instant, name the <em>same absolute moment</em>: repetition buys
     * nothing, and what is published is which 900-second bucket the deadline falls in, once.
     *
     * <p><strong>So do not "verify" this by checking the emitted number is a multiple of 900.</strong>
     * It almost never is — the number counts down one per second, because only its target sits on a
     * boundary. A {@code Retry-After} that IS a multiple of the quantum is the fingerprint of the
     * broken variant, which means an assertion of that shape would certify the defect. The property
     * to assert is that {@code now + retryAfter} is the same value at two different instants.
     *
     * <p>{@code floorDiv} and then {@code + quantum}, so the answer is strictly after the deadline
     * even when the deadline lands exactly on a boundary. Rounding up never under-states the wait,
     * and a caller sent away for a few extra minutes loses nothing they were going to be given.
     */
    private static long secondsUntilBucketedDeadline(Instant deadline, Instant now) {
        long bucketed = Math.floorDiv(deadline.getEpochSecond(), DAILY_RETRY_QUANTUM_SECONDS)
                        * DAILY_RETRY_QUANTUM_SECONDS + DAILY_RETRY_QUANTUM_SECONDS;
        // Never below 1, for the reason on secondsUntil: a Retry-After of 0 invites an immediate
        // retry that is refused again.
        return Math.max(bucketed - now.getEpochSecond(), 1);
    }
}
