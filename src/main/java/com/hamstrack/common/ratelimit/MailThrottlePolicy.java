package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;

import java.time.Duration;

/**
 * One kind of outbound mail's recipient-keyed ceilings (HD-190 §6.6, HD-202).
 *
 * <p><strong>One policy bean per {@link EmailType}, and that is the extension point.</strong> The
 * mechanism ({@link RecipientMailThrottle}) is deliberately ignorant of what kind of mail it is
 * throttling: it knows how to lock an address, count two windows, refuse, and record. Everything
 * that differs between invitation mail, reset mail and verification mail is here — the numbers, the
 * windows, the metric tags, the shape of the refusal and the sentences. HD-202 was therefore three
 * more beans of this type plus three call sites, not a second limiter that half-overlaps this one.
 *
 * <p><strong>Separate budgets, one table.</strong> Counts are always taken per {@code type}, so a
 * reset flood cannot consume an invitation allowance or the other way round. That separation is a
 * DENIAL control as much as a volume one: whoever can fill a bucket can withhold every flow that
 * shares it, so two flows share a bucket only when a shared budget is the thing being bought (see
 * {@link EmailType#REGISTRATION_VERIFICATION}, which was carved out of the verification bucket for
 * exactly this reason).
 *
 * <p>The numbers are read from {@code @ConfigurationProperties} at bean creation, which is the same
 * lifetime the properties themselves have.
 *
 * @param type                  which mail this governs; also the {@code email_type} written to
 *                              {@code mail_send_events}
 * @param cooldown              the same sender may not send to the same address twice inside this,
 *                              <em>across every workspace in the instance</em>. For the anonymous
 *                              auth flows there is one shared sender bucket per key, so this reads
 *                              as "one of these per address per cooldown, whoever asks"
 * @param ceilingWindow         the window {@link #maxPerRecipientPerWindow} is counted over.
 *                              <strong>Per policy since HD-202, and the reason is not
 *                              tidiness.</strong> A day was right for invitations — the harm it
 *                              bounds is a stranger's mail arriving. Where the same ceiling also
 *                              withholds mail the recipient <em>asked for</em> it is two controls
 *                              at once, and they pull opposite ways: it bounds how much mail one
 *                              inbox receives ({@code cap} per window), and it decides how long
 *                              whoever fills it keeps a named person from recovering their own
 *                              account. <strong>Sustained denial is achievable at EVERY
 *                              width</strong> — no per-address ceiling can be built that cannot be
 *                              filled, so a wider window does not buy prevention, only a lower
 *                              attacker effort (1 request/12 min at 5-per-hour against 1/3 min at
 *                              5-per-quarter-hour). What the width really buys is the mail bound;
 *                              what it costs is the length of a HIT-AND-RUN lockout, which is the
 *                              common case — one capful of requests, fired once, buys exactly this
 *                              long. Hence a quarter of an hour on password reset (20 mails/hour, a
 *                              quarter-hour lockout) and an hour on verification, where nobody is
 *                              locked out of an account they already hold and the tighter mail
 *                              bound is the whole of the trade. Capped at
 *                              {@link #MAX_CEILING_WINDOW}
 * @param maxPerRecipientPerWindow the global ceiling on one recipient key within
 *                              {@link #ceilingWindow}, spent on two things at once: how many of
 *                              this kind of mail <em>you</em> may send to that key, and how many
 *                              <em>other</em> senders may reach it — other senders count once each
 *                              however much they sent, so no single account can consume a
 *                              stranger's whole allowance. See
 *                              {@link RecipientMailThrottle#refusalIfVolumeCapReached}
 * @param cooldownKind          metric tag for a cooldown refusal — harassment-shaped
 * @param recipientVolumeKind   metric tag for a volume-cap refusal — coordinated-harassment-shaped,
 *                              and the sharpest of the signals because it needs several senders
 * @param refusal               who, if anybody, is told this ceiling fired. See {@link Refusal}
 * @param wording               what each refusal says; {@code null} — and required to be null —
 *                              when {@link #refusal()} is {@link Refusal#SILENT}, because a
 *                              sentence that is never rendered is a claim nobody re-reads.
 *                              Required to be non-null for both {@code 429} shapes, which render it
 */
public record MailThrottlePolicy(EmailType type,
                                 Duration cooldown,
                                 Duration ceilingWindow,
                                 int maxPerRecipientPerWindow,
                                 RateLimitKind cooldownKind,
                                 RateLimitKind recipientVolumeKind,
                                 Refusal refusal,
                                 MailThrottleWording wording) {

    /**
     * The widest {@link #ceilingWindow} any policy may declare — <strong>and the single source of
     * the number {@code InviteProperties} asserts the {@code mail_send_events} retention
     * against.</strong>
     *
     * <p>Before HD-202 the widest window was a {@code private} constant in
     * {@link RecipientMailThrottle} and {@code InviteProperties} carried a hand-copied {@code 1440}
     * beside a paragraph explaining the consequence: widen the original and the copy does not move,
     * so the assertion goes on passing while guaranteeing less than its message says, with no error
     * and no log line, and only a test comparing the two constants could catch it. Making the
     * window per-policy would have made that worse — N windows against one copy — so the copy is
     * gone instead. This constant is imported by the retention assertion, and it is enforced below
     * as a ceiling on every policy, so a policy declaring a wider window fails at bean creation
     * rather than silently outrunning the sweep that feeds it.
     */
    public static final Duration MAX_CEILING_WINDOW = Duration.ofDays(1);

    /**
     * Which doors of {@link RecipientMailThrottle} this type may be spent through, and therefore
     * what a refused caller learns.
     *
     * <p>A property of the mail type rather than of the call site, on purpose: the call site is the
     * thing that gets copied, and "refuse quietly" must not be a decision a future copy can flip by
     * reaching for a different method.
     *
     * <p><strong>ONE CONSTANT, ONE DOOR — an exact match, never "anything that is not the opposite
     * one".</strong> That distinction is not stylistic, and it cost a review round. While the
     * guards read {@code != SILENT} and {@code != RESPONDS_429}, a type that could answer
     * {@code 429} on one endpoint was admissible through the plain {@code requireAndRecord} as well
     * as through the disclosing door — and the seal in {@code AuthMailDoorsTest} watched only the
     * disclosing door's call sites. So a copied {@code requireAndRecord} would have made
     * {@code resend-verification} answer {@code 429}, publishing to anybody that somebody asked for
     * mail at that inbox in the last minute, and nothing would have fired. An exact predicate is a
     * type invariant; a "not the opposite" one is a partition that silently widens the moment a
     * third state exists.
     *
     * <p><strong>What varies is not the mail — it is the ENDPOINT's own disclosure.</strong> A
     * {@code 429} is unavailable exactly where an endpoint's answer is uniform by design, because
     * there the refusal would publish, to anybody, that somebody asked for mail at this address a
     * minute ago. Where the endpoint already discloses that much by construction, a {@code 429}
     * adds nothing and telling the caller is strictly better than dropping their request in
     * silence — that is {@link #RESPONDS_429_WHERE_ENDPOINT_DISCLOSES}, its own constant so that
     * "this endpoint publishes it anyway" is a claim declared once and checked by a door, rather
     * than a sentence in the javadoc of whoever picked the method.
     */
    public enum Refusal {

        /**
         * The refusal is a {@code 429} carrying {@link MailThrottlePolicy#wording()} and a
         * {@code Retry-After}. Correct wherever the caller has already proven they may make the
         * request — the invitation path, where the refusal only ever reaches a member of a resolved
         * workspace.
         *
         * <p>Admissible through {@link RecipientMailThrottle#requireAndRecord} and through no other
         * door. An anonymous endpoint that wants a {@code 429} declares
         * {@link #RESPONDS_429_WHERE_ENDPOINT_DISCLOSES} and states why it may.
         */
        RESPONDS_429,

        /**
         * The refusal is invisible: no mail is sent and the endpoint answers exactly what it would
         * have answered. Required on {@code POST /api/auth/forgot-password} and
         * {@code POST /api/auth/resend-verification}, whose uniform response is a security contract
         * rather than an implementation detail (HD-202). Admissible through
         * {@link RecipientMailThrottle#allowAnonymousSend} and through no other door.
         *
         * <p>A 429 there would not leak <em>account existence</em> — the ceilings are spent before
         * the account lookup and recorded whether or not a row exists — but it would leak something
         * else, to anybody on the internet, for free: that <em>somebody</em> asked for a reset at
         * this address in the last minute. That is a third party's activity, published by an
         * unauthenticated endpoint whose entire design is that its answer says nothing about the
         * address. So the ceiling is spent, the mail is dropped, and the caller is told the same
         * sentence as always.
         *
         * <p>The cost is stated rather than hidden: an honest user who needs a second link inside
         * the cooldown is told one was sent and does not receive it. They received the first one a
         * minute ago, which is the half of the trade that makes it cheap.
         *
         * <p><strong>THE RESPONSE IS NOT THE ONLY CHANNEL, AND THIS IS RECORDED RATHER THAN
         * FIXED.</strong> A refused request returns without the account lookup, without the
         * {@code mail_send_events} insert and without the flow's own token insert, so "throttled"
         * is measurably <em>cheaper</em> than "allowed" — which puts the same bit this constant
         * exists to withhold ("somebody asked for mail at this address in the last minute") on the
         * clock instead of on the status line. It is sub-millisecond against internet jitter and it
         * self-poisons, because measuring it spends a slot and moves the answer. Equalising it
         * would cost a wasted lookup on every refusal, on an unauthenticated endpoint, for a
         * channel nobody can read reliably — so the trade is refused and written down instead.
         *
         * <p>Which is the point of writing it here: this javadoc used to read as though the
         * response were the whole of the disclosure surface, and a future reader hardening this
         * path deserves to know which channel was considered and deliberately left open. If the
         * work either side of this ceiling ever grows — a second lookup, an outbound call, a
         * write — re-read this paragraph before assuming it still holds.
         */
        SILENT,

        /**
         * A {@code 429} carrying {@link MailThrottlePolicy#wording()} and a {@code Retry-After} —
         * on an <strong>anonymous</strong> endpoint, which is legitimate only because that endpoint
         * already publishes what the refusal would.
         *
         * <p>{@link EmailType#REGISTRATION_VERIFICATION} is the only holder.
         * {@code POST /api/auth/register} answers {@code 409} for a taken address, so address
         * existence is published by construction and a {@code 429} adds one folded-key bit to a
         * caller who has just been told about the exact spelling anyway. Dropping the request in
         * silence would be strictly worse: the {@code users} row would exist and be activatable by
         * nobody, its owner included.
         *
         * <p><strong>Distinct from {@link #RESPONDS_429} because the JUSTIFICATION is
         * distinct.</strong> {@code RESPONDS_429}'s door is reached only after tenancy and
         * authorization have been resolved, so its refusal never reaches a stranger; this one is
         * reached by anybody on the internet, and what makes it safe is a property of the ENDPOINT.
         * Collapsing the two would let an anonymous mailer inherit a justification it does not
         * have, which is the mistake that put register outside this mechanism in the first place.
         *
         * <p>The residual hole — this door mounted on an endpoint whose answer is uniform — cannot
         * be closed by the type, because the type cannot see where it is called from. It is closed
         * by a sealed set instead: {@code AuthMailDoorsTest} pins the CALL SITES of
         * {@link RecipientMailThrottle#requireAndRecordWhereEndpointDiscloses} down to the
         * enclosing METHOD, and its failure message is the checklist. A guard that cannot be
         * expressed as a type invariant is expressed as a sealed set, never as a comment.
         */
        RESPONDS_429_WHERE_ENDPOINT_DISCLOSES
    }

    public MailThrottlePolicy {
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "a policy for " + type + " must declare a positive cooldown — the off switch "
                    + "is app.rate-limit.enabled, never a zero window");
        }
        if (ceilingWindow == null || ceilingWindow.isZero() || ceilingWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "a policy for " + type + " must declare a positive ceiling window");
        }
        if (ceilingWindow.compareTo(MAX_CEILING_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "the ceiling window for " + type + " is " + ceilingWindow + ", wider than "
                    + MAX_CEILING_WINDOW + ". mail_send_events rows are swept on "
                    + "app.invites.event-retention-days, and that retention is asserted at startup "
                    + "against MailThrottlePolicy.MAX_CEILING_WINDOW — so a wider window here would "
                    + "count rows the sweep has already deleted, and the ceiling would silently "
                    + "shorten to the retention with no error and no log line. Raise this constant "
                    + "and the retention's @Min together, or narrow the window");
        }
        // >= and not >. The message is a claim about REACHABILITY, and equality already falsifies
        // it: at cooldown == window the first send starts a cooldown running to the far edge of the
        // window, so every later send inside that window is refused by the cooldown and the volume
        // cap can never be the thing that fires. The guard used to permit the equality case, and at
        // AuthMailProperties old @Max(15) against the 15-minute reset window an operator could set
        // exactly the unreachable cap this sentence says it exists to prevent.
        //
        // "UNREACHABLE" IS TRUE OF ONE COOLDOWN SHAPE AND NOT THE OTHER, so the message says which.
        // Where the cooldown is one shared bucket per address (the anonymous auth flows) equality
        // does make the cap unreachable outright. Where it is per (sender, recipient) - INVITE -
        // the cap counts each OTHER sender once, so a coalition of distinct senders still reaches
        // it; what equality kills there is the single-sender half of the ceiling, which is most of
        // the point of having one. Refusing both shapes is right; claiming the same consequence for
        // both is not.
        if (cooldown.compareTo(ceilingWindow) >= 0) {
            throw new IllegalArgumentException(
                    "the cooldown for " + type + " (" + cooldown + ") is not narrower than its "
                    + "ceiling window (" + ceilingWindow + "), which puts the volume cap out of "
                    + "reach of any single sender: the first send starts a cooldown running to the "
                    + "far edge of the window, so every later send from that sender is refused "
                    + "before it can count. Where the cooldown is shared by everybody, as it is on "
                    + "the anonymous auth flows, that makes the cap unreachable outright; where it "
                    + "is per sender, only a coalition of distinct senders can still reach it, "
                    + "which is not the ceiling this policy declares");
        }
        if (maxPerRecipientPerWindow < 1) {
            throw new IllegalArgumentException(
                    "the volume cap for " + type + " must be at least 1");
        }
        if (refusal != Refusal.SILENT && wording == null) {
            throw new IllegalArgumentException(
                    "a policy that can answer 429 on any door must say something — " + type
                    + " has no wording");
        }
        if (refusal == Refusal.SILENT && wording != null) {
            throw new IllegalArgumentException(
                    "the policy for " + type + " refuses silently, so its wording is never "
                    + "rendered. A sentence nobody can read is a claim nobody re-reads: delete it, "
                    + "or make the refusal visible");
        }
    }

    /**
     * Whether this type may be spent through {@link RecipientMailThrottle#allowAnonymousSend},
     * which drops the mail and moves nothing in the response — exactly {@link Refusal#SILENT}.
     *
     * <p>All three predicates are EXACT rather than "not the opposite one", for the reason the
     * {@link Refusal} javadoc gives: a negated predicate admits every state somebody adds later,
     * and the door whose call sites nothing seals is the one that then leaks.
     */
    public boolean mayRefuseSilently() {
        return refusal == Refusal.SILENT;
    }

    /**
     * Whether this type may be spent through {@link RecipientMailThrottle#requireAndRecord}, which
     * answers {@code 429} to a caller who has already proven they may make the request — exactly
     * {@link Refusal#RESPONDS_429}.
     */
    public boolean mayRefuseWithStatus() {
        return refusal == Refusal.RESPONDS_429;
    }

    /**
     * Whether this type may be spent through
     * {@link RecipientMailThrottle#requireAndRecordWhereEndpointDiscloses}, which answers
     * {@code 429} to an anonymous caller on an endpoint that already publishes what the refusal
     * would — exactly {@link Refusal#RESPONDS_429_WHERE_ENDPOINT_DISCLOSES}.
     */
    public boolean mayRefuseWhereEndpointDiscloses() {
        return refusal == Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES;
    }
}
