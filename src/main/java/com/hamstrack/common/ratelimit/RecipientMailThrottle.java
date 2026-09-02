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
 * bound.</strong> An over-fold costs {@link #refusalIfCoolingDown} an honest sender a wait they did
 * not earn, which is their own inconvenience and expires. It costs
 * {@link #refusalIfVolumeCapReached} a slot belonging to <em>a different, innocent person</em>
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
 * <p><strong>That is a property of this class and a duty of its callers</strong> (HD-202). On the
 * anonymous auth flows the ceiling is spent BEFORE the account is looked up, because a ceiling
 * spent after one — or only on the branch that really sends — answers exactly the question the
 * endpoint refuses to answer, and it does so while looking like a fix. See
 * {@link #allowAnonymousSend}.
 *
 * <h2>Three doors, one body, and why the policy owns the choice</h2>
 * A caller who has proven their right to make the request gets a {@code 429} naming a wait
 * ({@link #requireAndRecord}); an endpoint whose response is uniform by design gets nothing at all
 * and drops the mail ({@link #allowAnonymousSend}); an endpoint that already discloses, by its own
 * status codes, everything a refusal would disclose gets the {@code 429} as well
 * ({@link #requireAndRecordWhereEndpointDiscloses}). Which doors a type may use is declared on the
 * {@link MailThrottlePolicy}, not chosen at the call site, and every door refuses to serve a type
 * that did not declare it — because the call site is what gets copied, and a copied
 * {@code requireAndRecord} on {@code forgot-password} would publish, to anybody on the internet,
 * that <em>somebody</em> asked for a reset at an address in the last minute.
 *
 * <p>Each door admits <strong>exactly one</strong> {@code Refusal} constant, never "anything that
 * is not the opposite one". While the guards were negations, a type entitled to the anonymous
 * {@code 429} was also admissible through {@link #requireAndRecord} — a copied call site there
 * would have made {@code resend-verification} answer {@code 429}, and the seal below watches only
 * the third door, so nothing would have fired.
 *
 * <p>What the type still cannot see is WHERE it is called from, and the third door is safe only
 * because of a property of the endpoint. Its set of call sites is therefore sealed by
 * {@code AuthMailDoorsTest} — down to the enclosing method, because all three auth flows live in
 * one file and a file-granular seal would not notice the call moving between them. That seal is
 * load-bearing rather than tidy: mounting this door on a uniform-response endpoint is exactly the
 * leak the other two doors exist to prevent.
 *
 * <p>All three spend and record identically; they differ only in what they do with the answer.
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
 * INSERT → commit. Nothing slow and nothing caller-controlled may ever be added between the
 * ceiling being spent and the commit that follows it.</strong> The key is a recipient address, and
 * an address is something two tenants legitimately share — so any wait held inside this section is
 * a wait one tenant can impose on another. Since HD-202 it is also a wait an <em>unauthenticated</em>
 * caller can impose, which does not change the rule but does raise the price of breaking it.
 * Sending the mail itself is outside this section because the call site registers it on
 * {@code AfterCommit} (HD-181) — after the commit that releases this
 * lock. It is <strong>not</strong> the hand-off to the mail pool that keeps it out, as this
 * paragraph used to say: that pool was bounded with {@code CallerRunsPolicy}, so a full queue turned
 * the dispatch into an inline send, under exactly the load where an SMTP round trip held across
 * tenants would hurt most. HD-208 has since removed the inline branch, so the hand-off no longer
 * betrays that claim — but the claim is still the wrong one to rest on, because it is a property of
 * the pool's current policy and not of this section. What must not appear between
 * a ceiling and the commit is anything slow, however it is spelled, and that rule survives any
 * policy.
 *
 * <p>These ceilings are consequently <strong>cluster-wide and exact</strong> — they do not divide
 * by the replica count, because their state is in PostgreSQL rather than in a map in a process. On
 * N replicas the bound is the configured number, not N times it, and a deploy does not re-arm it.
 * That is the requirement rather than a nicety: these protect a <em>person</em>, and a cooldown a
 * second replica resets is a cooldown an attacker waits out. The per-sender volume budget
 * ({@link InviteSenderVolumeBudget}) is in memory and degrades the usual way; the asymmetry is
 * deliberate and is written up in {@code docs/self-hosting.md}.
 */
@Slf4j
@Service
public class RecipientMailThrottle {

    /**
     * The volume cap's {@code Retry-After} names a <em>deadline</em> rounded up to the next multiple
     * of this — never a <em>duration</em> rounded to it. {@link #secondsUntilBucketedDeadline} is
     * where that distinction is argued, and it is the whole of the control;
     * {@link #refusalIfVolumeCapReached} is where only this one ceiling is coarsened at all.
     *
     * <p>It is the same quantum for every policy, and it only ever leaves the process on a policy
     * whose refusal is visible: a {@code SILENT} one computes a wait nobody reads. That is not
     * waste worth removing — the two paths differ in what they DO with a refusal and in nothing
     * else, which is the property that stops a future silent path from quietly skipping a ceiling.
     */
    private static final long VOLUME_RETRY_QUANTUM_SECONDS = 900;

    /**
     * Serialises concurrent sends of ONE KIND of mail aimed at one recipient key, for the rest of
     * the transaction.
     *
     * <p><strong>The lock key is {@code emailType} and {@code recipientKey}, and the type half is
     * not decoration.</strong> Every count in this class filters {@code email_type} in its
     * {@code WHERE} clause, so an invitation and a password reset to one address are two
     * independent ceilings — locking on the address alone would make them serialise anyway, which
     * contradicts the design they sit inside and, since HD-202, lets an <em>unauthenticated</em>
     * {@code forgot-password} for an address queue behind a tenant's invitation to the same
     * address. Contention rather than disclosure, but with a pool of ten connections and a
     * three-second lock timeout, ten concurrent requests naming one address are enough to matter.
     *
     * <p><strong>A {@code '|'} separator, and NOT a NUL — that first attempt failed loudly and is
     * worth recording.</strong> {@code 0x00} is the obvious "cannot occur in either half" byte and
     * PostgreSQL will not accept it in {@code text} at all: every send became
     * {@code invalid byte sequence for encoding "UTF8": 0x00} from the lock statement itself. What
     * makes {@code '|'} safe is not that it cannot appear in an address (it can, in an unquoted
     * local part) but that it cannot appear in the OTHER half: an {@link EmailType} name is
     * {@code [A-Z_]+}, so the first {@code '|'} always ends the type and no two (type, key) pairs
     * can collide by concatenation whatever the address contains.
     *
     * <p>The window {@code maxPerRecipientPerWindow} is counted over is likewise the POLICY'S
     * ({@code MailThrottlePolicy.ceilingWindow}), not a constant here — a day for invitations, a
     * quarter of an hour for password reset, an hour for verification, and the argument for each is
     * on that record. The widest any policy may declare is
     * {@code MailThrottlePolicy.MAX_CEILING_WINDOW}, which is also what {@code InviteProperties}
     * asserts the {@code mail_send_events} retention against, so there is no hand-copied width in a
     * second file to drift out from under this one.
     *
     * <p>Positional parameters and an explicit {@code CAST} rather than {@code ::bigint}, so nothing
     * in the statement can be mistaken for a named parameter; the wrapping {@code SELECT 1} exists
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
     * class javadoc for why that ordering is not negotiable. The anonymous auth flows, which have
     * no such caller, use {@link #allowAnonymousSend} instead and are refused in silence.
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
        if (policy != null && !policy.mayRefuseWithStatus()) {
            // Not a defensive nicety: this door THROWS, and a throw is how an endpoint whose
            // response must be uniform becomes an oracle. The policy carries the shape of its
            // refusal precisely so that a copied call site cannot change it by picking a method.
            // Sealed by AuthMailDoorsTest, because a guard that never fires reads exactly like one
            // that works.
            //
            // The predicate is EXACT (== RESPONDS_429) and used to be "not SILENT", which admitted
            // the anonymous 429 shape here as well — so a copied requireAndRecord could have put a
            // 429 on resend-verification while passing this guard AND while sitting outside the
            // seal, which only ever watched the disclosing door. Each door admits one constant.
            throw new IllegalStateException(
                    type + " does not declare MailThrottlePolicy.Refusal.RESPONDS_429, and this "
                    + "door answers 429 to a caller whose authorization is already resolved. A "
                    + "SILENT type belongs on allowAnonymousSend (drop the mail, move nothing in "
                    + "the response); an anonymous type that may answer 429 declares "
                    + "RESPONDS_429_WHERE_ENDPOINT_DISCLOSES and goes through "
                    + "requireAndRecordWhereEndpointDiscloses, whose call sites are sealed.");
        }
        var refusal = spend(policy, type, recipientEmail, senderUserId, workspaceId,
                cooldownAddendum);
        if (refusal != null) {
            throw new RateLimitedException(refusal.message().get(), refusal.retryAfterSeconds());
        }
    }

    /**
     * <strong>The same ceilings, spent by an endpoint that already discloses everything the refusal
     * would</strong> — {@code POST /api/auth/register}, and nowhere else.
     *
     * <h2>Why register needs a door of its own</h2>
     * Register mails a verification link to an address anybody on the internet types, and it was
     * left off this mechanism on the written ground that "registration can produce at most one
     * message per address — the second attempt is a 409". That is true per <em>address</em>, and an
     * address is not the unit of harm. {@code users.email} is unique on {@code lower(email)}, i.e.
     * on the SPELLING; every ceiling here counts {@code MailAddresses.throttleKey}, i.e. the
     * INBOX. Those two keys disagree exactly where it matters: {@code victim+1@gmail.com},
     * {@code victim+2@gmail.com} and {@code v.i.ctim@googlemail.com} are three distinct
     * {@code users} rows, three {@code 201}s, three verification mails — and one inbox. It is the
     * same one-keystroke re-spelling defeat {@code MailAddresses.throttleKey} exists to close,
     * reproduced against a door deliberately left off the mechanism.
     *
     * <h2>What is refused, and why that answers the original objection</h2>
     * The REGISTRATION, not the mail. Refusing the mail would leave a {@code PENDING} row nobody —
     * its owner included — could ever activate; refusing before the {@code users} INSERT strands
     * nothing. So this door throws, and register answers {@code 429}.
     *
     * <p>A {@code 429} is available here and only here because register is not an anti-enumeration
     * endpoint: it already answers {@code 409} for a taken address, so it publishes address
     * existence by construction. What the refusal adds is one folded-key bit — that some address
     * sharing this inbox was registered recently — to a caller who has just been told, or not told,
     * about the exact spelling anyway.
     *
     * <h2>The accepted cost, and the budget split that bounds who can impose it</h2>
     * Filling an inbox's registration window also refuses that inbox's owner a NEW registration
     * until the window rolls. That is a ceiling on a flow somebody may need, and — like every
     * per-address ceiling in this class — it is fillable, so <strong>the denial is sustainable
     * indefinitely by anyone willing to keep refilling it</strong>. Calling it "bounded by the
     * window" would be the hit-and-run case mistaken for the whole; {@code AuthMailProperties} makes
     * exactly this distinction for password reset and it holds here too.
     *
     * <p>What the design does bound is <strong>the price of imposing it</strong>, and that is why
     * {@link EmailType#REGISTRATION_VERIFICATION} is a bucket of its own rather than a share of
     * {@link EmailType#VERIFICATION}. While the two shared one, an attacker filled the register
     * ceiling through {@code POST /api/auth/resend-verification} at an address with NO account:
     * that endpoint records unconditionally (it must — a row written only when the account exists
     * is the existence oracle it is built to refuse), so five requests an hour, spaced past the
     * cooldown, denied signup for that whole inbox while sending <em>zero</em> mail and logging
     * nothing above DEBUG. Free, silent, refillable for ever, against any address a stranger names.
     * With separate buckets the only way to fill this one is to POST here — which costs a real
     * verification mail the victim can see and a {@code PENDING} row an administrator can find.
     *
     * <p>The price of the split is stated too: the verification mail one inbox can be sent goes
     * from {@code cap} per hour to {@code 2 x cap} worst case, because register and resend now hold
     * a cap each. That is the trade — a doubled mail bound, still far under the deliverability
     * number, bought against a free and invisible denial of account creation.
     *
     * <p><strong>Call it AFTER the cheap duplicate pre-check and immediately before the INSERT.</strong>
     * See {@code AuthService.register}, which states why it is deliberately NOT above
     * {@code passwordEncoder.encode}.
     *
     * @param type           which mail the endpoint is about to send; a type with no policy is a
     *                       no-op
     * @param recipientEmail the address as submitted, lower-cased at the boundary
     * @throws RateLimitedException 429 with {@code Retry-After}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAndRecordWhereEndpointDiscloses(EmailType type, String recipientEmail) {
        var policy = policies.get(type);
        if (policy != null && !policy.mayRefuseWhereEndpointDiscloses()) {
            throw new IllegalStateException(
                    type + " does not declare "
                    + "MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES, and this "
                    + "door answers 429 to an ANONYMOUS caller. Reaching it is a statement that the "
                    + "endpoint sending this mail already publishes what a refusal would — say it "
                    + "on the policy, not by picking a method. A SILENT type belongs on "
                    + "allowAnonymousSend; an authorized-caller type belongs on requireAndRecord.");
        }
        var refusal = spend(policy, type, recipientEmail, null, null, null);
        if (refusal != null) {
            throw new RateLimitedException(refusal.message().get(), refusal.retryAfterSeconds());
        }
    }

    /**
     * <strong>The same ceilings, spent by an endpoint that may not admit it refused</strong>
     * (HD-202) — {@code POST /api/auth/forgot-password} and
     * {@code POST /api/auth/resend-verification}.
     *
     * <p><strong>Call this BEFORE the account lookup, and on every request.</strong> Both endpoints
     * answer one uniform sentence whether or not the address has an account, and HD-208 requires
     * the two to take indistinguishable time. A ceiling spent after the lookup — or only on the
     * branch where mail would really be sent — is an existence oracle wearing the costume of a fix:
     * it would answer "throttled" for registered addresses and "not throttled" for the rest, which
     * is the precise question both endpoints exist not to answer. Spent first, it is a statement
     * about an address somebody typed and about nothing else, and the row is recorded either way.
     *
     * <p>Anonymous sends share one bucket per key ({@code sender_user_id IS NULL}), because "who
     * submitted the form" is not knowable and must not be guessable — keying it on anything the
     * request carries would hand an attacker a fresh cooldown per request.
     *
     * @param type           which mail is about to be sent; a type with no policy is allowed and
     *                       nothing is recorded for it
     * @param recipientEmail the address as submitted, lower-cased at the boundary
     * @return {@code true} when the send may proceed. {@code false} means a ceiling refused: send
     *         nothing, and change nothing else about the response
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowAnonymousSend(EmailType type, String recipientEmail) {
        var policy = policies.get(type);
        if (policy != null && !policy.mayRefuseSilently()) {  // exactly Refusal.SILENT
            // Sealed by AuthMailDoorsTest, for the reason its sibling above is: a guard that never
            // fires is indistinguishable from one that works.
            throw new IllegalStateException(
                    type + " does not declare MailThrottlePolicy.Refusal.SILENT, so it answers 429 "
                    + "on refusal and dropping its mail silently here would lose a refusal its "
                    + "caller is meant to see. Call requireAndRecord (RESPONDS_429) or "
                    + "requireAndRecordWhereEndpointDiscloses "
                    + "(RESPONDS_429_WHERE_ENDPOINT_DISCLOSES).");
        }
        return spend(policy, type, recipientEmail, null, null, null) == null;
    }

    /**
     * Both ceilings, the lock, the metrics and the bookkeeping — <strong>everything except what is
     * done with a refusal</strong>.
     *
     * <p>One body for both doors on purpose. The alternative, a second method that "just skips the
     * throw", is how a silent path ends up skipping a ceiling as well: here the only thing the
     * silent door does differently is discard the value returned below.
     *
     * @return the refusal, or {@code null} when the send was allowed and recorded
     */
    private Refusal spend(MailThrottlePolicy policy, EmailType type, String recipientEmail,
                          UUID senderUserId, UUID workspaceId, Supplier<String> cooldownAddendum) {
        // An unthrottled mail type is a no-op rather than an error, and nothing is recorded for it
        // either: the row exists only to be counted by a ceiling. A type with no policy is one
        // MailThrottleCoverageTest holds a written exemption for.
        if (policy == null) {
            return null;
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
            // It is also scoped to the TYPE, because every count below filters email_type — see
            // LOCK_RECIPIENT for why an address-only lock contradicts the per-type design and, since
            // HD-202, lets an anonymous caller queue behind a tenant's invitation.
            lockTimeout.applyToCurrentTransaction();
            entityManager.createNativeQuery(LOCK_RECIPIENT)
                    .setParameter(1, type.name() + '|' + recipientKey)
                    .getSingleResult();

            var now = Instant.now();
            var cooldownFrom = now.minus(policy.cooldown());
            var windowFrom = now.minus(policy.ceilingWindow());
            var from = cooldownFrom.isBefore(windowFrom) ? cooldownFrom : windowFrom;
            var counts = senderUserId == null
                    ? repository.countRecentForAnonymous(type.name(), recipientKey, cooldownFrom, windowFrom, from)
                    : repository.countRecentForSender(type.name(), recipientKey, senderUserId,
                            MailSendEventRepository.ANONYMOUS_SENDER, cooldownFrom, windowFrom, from);

            // The cooldown is checked first on purpose. When both would fire, its message describes
            // the caller's OWN past action and discloses nothing, while the volume cap's necessarily
            // says something about traffic the caller cannot see. Refuse with the cheaper
            // disclosure. (A silent policy renders neither, and the order still matters: the METRIC
            // is what an operator reads, and the two kinds mean different attacks.)
            var refusal = refusalIfCoolingDown(policy, recipientEmail, counts, now, cooldownAddendum);
            if (refusal == null) {
                refusal = refusalIfVolumeCapReached(policy, counts, now);
            }
            if (refusal != null) {
                return refusal;
            }
        }

        record(type, recipientEmail, recipientKey, senderUserId, workspaceId);
        return null;
    }

    /**
     * A ceiling that fired: the wait it named, and the sentence it would say if anybody were going
     * to read it.
     *
     * <p>The message is a {@link Supplier} rather than a {@code String} because a
     * {@code Refusal.SILENT} policy has no wording at all — rendering one would be building a
     * sentence nobody can read, and the {@code cooldownAddendum} it interpolates is a query. The
     * metric, by contrast, is recorded where the ceiling fires and for every policy: a refusal the
     * caller cannot see is exactly the kind an operator must be able to.
     */
    private record Refusal(long retryAfterSeconds, Supplier<String> message) {}

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
     * coarsening in {@link #refusalIfVolumeCapReached} — the two waits describe different
     * people's actions, which is the whole reason one of them is rounded.
     */
    private Refusal refusalIfCoolingDown(MailThrottlePolicy policy, String recipientEmail,
                                         MailSendCounts counts, Instant now,
                                         Supplier<String> cooldownAddendum) {
        if (counts.samePair() == 0 || counts.samePairLatest() == null) {
            return null;
        }
        long retryAfter = secondsUntil(counts.samePairLatest().plus(policy.cooldown()), now);
        metrics.rateLimitHit(policy.cooldownKind());
        return new Refusal(retryAfter, () -> policy.wording().cooldown(recipientEmail,
                RetryWait.describe(retryAfter),
                cooldownAddendum == null ? null : cooldownAddendum.get()));
    }

    /**
     * The global per-recipient volume cap — the one ceiling whose count includes strangers.
     *
     * <p>Counted over the policy's own {@code ceilingWindow}: a day for invitations, an hour for
     * the anonymous auth mailers, where the same ceiling is also the length of time a victim
     * cannot recover their account (HD-202). Everything below is the invitation cap's arithmetic
     * because that is the ceiling whose numbers were argued over; the mechanism is the same one.
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
    private Refusal refusalIfVolumeCapReached(MailThrottlePolicy policy, MailSendCounts counts,
                                              Instant now) {
        if (counts.recipientWindow() < policy.maxPerRecipientPerWindow()
            || counts.recipientWindowOldest() == null) {
            return null;
        }
        long retryAfter = secondsUntilBucketedDeadline(
                counts.recipientWindowOldest().plus(policy.ceilingWindow()), now);
        metrics.rateLimitHit(policy.recipientVolumeKind());
        return new Refusal(retryAfter,
                () -> policy.wording().recipientVolume(RetryWait.describe(retryAfter)));
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
        // (§10.1). DOMAIN ONLY, never the local part.
        //
        // AT INFO ONLY WHEN THERE IS A "WHO" TO NAME, AND THE REASON IS A BOUND THAT NO LONGER
        // HOLDS FOR ANONYMOUS ROWS. This line used to be justified as "successful sends only, so it
        // is bounded by the very ceilings above" — true while every caller was an authenticated
        // invite sender, because such a caller runs out of budget. An anonymous caller CONTROLS THE
        // KEY SPACE: a fresh address is a fresh bucket, so every request is ALLOWED, every one
        // writes a row, and the rate is bounded only by a per-IP budget a proxy pool defeats.
        // A forensic trail that an unauthenticated stranger can turn into unbounded Loki ingest is
        // itself the flooding vector, and the sender/workspace it would print are both null — so it
        // names nothing an operator could act on either. Anonymous sends are DEBUG; the durable
        // evidence for them is the mail_send_events row (swept aggressively, see
        // AuthMailProperties.ANONYMOUS_EVENT_RETENTION) and the concentration gauge built on it.
        if (senderUserId == null) {
            log.debug("mail send allowed (anonymous): type={} recipientDomain={}",
                    type, MailAddresses.domainOf(recipientEmail));
        } else {
            log.info("mail send allowed: type={} sender={} workspace={} recipientDomain={}",
                    type, senderUserId, workspaceId, MailAddresses.domainOf(recipientEmail));
        }
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
        long bucketed = Math.floorDiv(deadline.getEpochSecond(), VOLUME_RETRY_QUANTUM_SECONDS)
                        * VOLUME_RETRY_QUANTUM_SECONDS + VOLUME_RETRY_QUANTUM_SECONDS;
        // Never below 1, for the reason on secondsUntil: a Retry-After of 0 invites an immediate
        // retry that is refused again.
        return Math.max(bucketed - now.getEpochSecond(), 1);
    }
}
