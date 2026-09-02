package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.AuthMailProperties;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The auth mailers' entries in the recipient-throttle policy map (HD-202).
 *
 * <p>{@code InviteMailThrottleConfig} is the shape this copies, and it predicted this file almost
 * exactly: {@link MailThrottlePolicy} beans, call sites in {@code AuthService}, no
 * migration — {@code mail_send_events} has carried {@code email_type} from day one for this. What
 * it did not predict, and each is a consequence of the caller being <em>anonymous</em> rather than
 * a member of a resolved workspace:
 *
 * <ol>
 *   <li><strong>The refusal is silent on the uniform-response endpoints, so those carry no
 *       wording.</strong> {@code forgot-password} and {@code resend-verification} answer one
 *       sentence to everybody — that uniformity is the anti-enumeration contract, and it is what
 *       both endpoints exist to hold. A 429 would not leak account existence (the ceilings are
 *       spent before the lookup and recorded either way) but it would leak, to anybody, for free,
 *       that <em>somebody</em> asked for a reset at this address in the last minute. That is a
 *       third party's activity published by an unauthenticated endpoint. So the ceiling is spent,
 *       the mail is dropped, and the response does not move.</li>
 *   <li><strong>The two volume windows are different widths.</strong> Password reset gets a quarter
 *       of an hour, verification an hour. Here a ceiling is not only a bound on mail arriving; on
 *       reset it is also a bound on mail the recipient <em>asked for</em>, so whoever fills the
 *       window decides how long a named person cannot reset their own password. Since sustained
 *       denial is achievable at every width, the width buys only the mail bound and costs only the
 *       length of a hit-and-run lockout — which is why the flow that can lock somebody out gets
 *       the shorter one and the flow that cannot gets the tighter mail bound. The arithmetic is on
 *       {@link AuthMailProperties}, where somebody stands when they change the number.</li>
 *   <li><strong>Verification mail is sent from two endpoints with opposite disclosure contracts,
 *       so it has two policies rather than one.</strong> {@code resend-verification} answers one
 *       sentence to everybody; {@code register} answers {@code 409} for a taken address and may
 *       therefore say {@code 429}. Giving them one BUDGET was the obvious economy and it was
 *       wrong — see {@link #registrationVerificationMailThrottlePolicy}, which is where the
 *       argument and its accepted cost live.</li>
 * </ol>
 *
 * <p><strong>{@code POST /api/auth/register} is on this mechanism too, and the earlier
 * decision that it need not be was wrong.</strong> (It spends a bucket of its own; it shared
 * verification's until final review, which is item 3 above.) The reason it gave — "registration can produce at
 * most one message per address, because the second attempt is a {@code 409}" — is true per
 * <em>address</em>, and the whole premise of {@code MailAddresses.throttleKey} is that an address
 * is not the unit of harm. {@code users.email} is unique on {@code lower(email)}, i.e. on the
 * spelling; the ceiling counts the inbox. With public signup on, an unauthenticated caller posts
 * {@code victim+1@gmail.com}, {@code victim+2@gmail.com}, {@code v.i.ctim@googlemail.com} — each a
 * distinct row, each a {@code 201}, each one verification mail, all of them in one inbox — and the
 * throttle was never consulted. That is the same one-keystroke re-spelling defeat this mechanism
 * exists to close, reproduced against a door deliberately left off it. The register call site
 * refuses the <em>registration</em> rather than the mail (refusing before the {@code users} INSERT
 * strands nothing) and it answers {@code 429}, which is available there and nowhere else in this
 * file because register already publishes address existence through its own {@code 409}.
 *
 * <p>{@code MailThrottleCoverageTest} seals mailers rather than paths, so it could not have seen
 * that gap and must not be read as having blessed it; what seals the paths now is
 * {@code AuthMailDoorsTest}, which pins the set of call sites of the one door no type invariant can
 * guard, down to the enclosing method.
 *
 * <p><strong>Every policy here shares one pair of numbers</strong> ({@link AuthMailProperties}), so
 * a per-inbox bound stated as "the cap" is per BUDGET and not per instance: the caps add up across
 * the buckets an attacker can reach. That arithmetic is on each bean.
 */
@Configuration
public class AuthMailThrottleConfig {

    /**
     * {@code POST /api/auth/forgot-password}.
     *
     * <p>The one with the sharper failure mode of the two: a reset link is how somebody who has
     * lost access gets back in, so this ceiling is simultaneously the harassment bound and the
     * account-recovery denial. Its refusal meters are the only visible sign of a burst — and of the
     * <em>sustained</em> version they are no sign at all, because an attacker who spends the cap as
     * slots age out is never refused. That one is seen by the concentration gauge over
     * {@code mail_send_events} instead ({@code MailRecipientConcentration}).
     */
    @Bean
    public MailThrottlePolicy passwordResetMailThrottlePolicy(AuthMailProperties properties) {
        return new MailThrottlePolicy(
                EmailType.PASSWORD_RESET,
                Duration.ofMinutes(properties.recipientCooldownMinutes()),
                AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW,
                properties.maxPerRecipientPerWindow(),
                RateLimitKind.PASSWORD_RESET_RECIPIENT_COOLDOWN,
                RateLimitKind.PASSWORD_RESET_RECIPIENT_WINDOW,
                MailThrottlePolicy.Refusal.SILENT,
                // No wording. MailThrottlePolicy refuses a SILENT policy that carries one, because a
                // sentence nobody can read is a claim nobody re-reads. Password reset has exactly
                // one door and it is the silent one, so there is nothing here to render.
                null);
    }

    /**
     * {@code POST /api/auth/resend-verification}, and nothing else.
     *
     * <p>A separate bucket from the reset one, on the same address, on purpose: counts are taken per
     * {@code email_type}, so somebody spamming one flow at a victim cannot suppress the other. Any
     * shared bucket would let a stranger's traffic withhold the one piece of mail the victim
     * actually wants.
     *
     * <p><strong>It used to be shared with {@code POST /api/auth/register}, and that was a hole
     * rather than a saving</strong> — see
     * {@link #registrationVerificationMailThrottlePolicy}. What is left here is silent, so it
     * carries no wording.
     *
     * <p><strong>THE FREE, SILENT DENIAL SURVIVES ON THIS DOOR. The split narrowed it; it did not
     * delete it, and several write-ups of HD-202 read as though it had.</strong> Every ingredient
     * is still here: a slot is recorded before the account lookup and unconditionally (it must be,
     * or the row is the existence oracle), the refusal is silent, the log line for an anonymous row
     * is {@code DEBUG}, and a fresh spelling is a fresh bucket. So five requests an hour at an
     * inbox still hold that inbox's own {@code resend-verification} for the hour, send zero mail
     * and cost nothing. What register's separate bucket bought is that the same five requests no
     * longer reach {@code POST /api/auth/register} — which is the door where the denial was
     * <em>unrecoverable</em>, because somebody who cannot register has no account, no other route
     * in, and nothing an administrator can see.
     *
     * <p>Tolerable here for the reason the window widths turn on: the victim of a
     * {@code resend-verification} denial already holds the account and already received a
     * verification link when they created it, so what is withheld is a <em>second copy</em> of a
     * message they have — and the link itself outlives the hour. The residual is not zero and is
     * not claimed to be; it is bounded by the same concentration gauge, which sees this traffic
     * (its query has no {@code email_type} filter).
     */
    @Bean
    public MailThrottlePolicy verificationMailThrottlePolicy(AuthMailProperties properties) {
        return new MailThrottlePolicy(
                EmailType.VERIFICATION,
                Duration.ofMinutes(properties.recipientCooldownMinutes()),
                AuthMailProperties.VERIFICATION_CEILING_WINDOW,
                properties.maxPerRecipientPerWindow(),
                RateLimitKind.VERIFICATION_RECIPIENT_COOLDOWN,
                RateLimitKind.VERIFICATION_RECIPIENT_WINDOW,
                MailThrottlePolicy.Refusal.SILENT,
                // No wording, for the reason the reset policy above has none: resend-verification
                // answers one sentence to everybody and renders nothing this policy could say.
                null);
    }

    /**
     * {@code POST /api/auth/register} — <strong>its own bucket, and the separation is the
     * control</strong> (HD-202 review).
     *
     * <h2>Why not one bucket with resend-verification</h2>
     * The shared bucket was argued for on the ground that an attacker denied by one endpoint would
     * simply use the other, and that is true of the MAIL BOMB. It is exactly backwards for the
     * DENIAL, because the two endpoints do not cost the same to spend. {@code resend-verification}
     * records its row <em>before</em> the account lookup and unconditionally — it must, or the row
     * itself becomes the existence oracle the endpoint is built to refuse — so at an address with
     * no {@code PENDING} account it sends no mail, logs nothing above {@code DEBUG}, and still
     * spends a slot. Five of those an hour, spaced past the cooldown, therefore held
     * {@code victim@gmail.com}, {@code victim+anything@gmail.com} and
     * {@code v.i.ctim@googlemail.com} out of {@code POST /api/auth/register} for the rest of the
     * hour, refillable for ever, for free, with no signal on either side.
     *
     * <p>With the buckets separate, the only way to fill this one is to POST here — which sends a
     * real verification mail the victim can see and leaves a {@code PENDING} row an administrator
     * can find. The denial is still achievable (no per-address ceiling can be built that cannot be
     * filled, which {@code AuthMailProperties} says at length); what changed is that it can no
     * longer be imposed silently or for nothing, and that register's {@code 429} is no longer
     * settable by traffic aimed at a different endpoint.
     *
     * <h2>The price, stated rather than discovered</h2>
     * One inbox can now be sent up to <em>twice</em> {@code max-per-recipient-per-window} pieces of
     * verification mail an hour instead of once, because register and resend hold a cap each. Ten
     * an hour at the shipped default; the deliverability number this file worries about is sixty.
     * The resend half of that ten also requires a real {@code PENDING} account per spelling, which
     * only register can create — so the attacker pays the register cap to unlock the resend cap.
     *
     * <h2>The refusal shape</h2>
     * {@link MailThrottlePolicy.Refusal#RESPONDS_429_WHERE_ENDPOINT_DISCLOSES}: a {@code 429} on an
     * anonymous endpoint, legitimate only because register already publishes address existence
     * through its own {@code 409}. Silence would be strictly worse here — a dropped verification
     * mail leaves an account nobody, its owner included, can activate — and the constant is
     * distinct from {@code RESPONDS_429} so that an anonymous mailer cannot inherit the invitation
     * path's "the caller is already authorized" justification, which it does not have.
     */
    @Bean
    public MailThrottlePolicy registrationVerificationMailThrottlePolicy(AuthMailProperties properties) {
        return new MailThrottlePolicy(
                EmailType.REGISTRATION_VERIFICATION,
                Duration.ofMinutes(properties.recipientCooldownMinutes()),
                AuthMailProperties.VERIFICATION_CEILING_WINDOW,
                properties.maxPerRecipientPerWindow(),
                RateLimitKind.REGISTRATION_VERIFICATION_RECIPIENT_COOLDOWN,
                RateLimitKind.REGISTRATION_VERIFICATION_RECIPIENT_WINDOW,
                MailThrottlePolicy.Refusal.RESPONDS_429_WHERE_ENDPOINT_DISCLOSES,
                new RegistrationThrottleWording());
    }

    /**
     * What a refused <em>registration</em> is told. Rendered on
     * {@code RecipientMailThrottle.requireAndRecordWhereEndpointDiscloses} and on no other door.
     *
     * <p><strong>Neither sentence names the address, and that is not the usual reason.</strong> On
     * the invitation path the cooldown sentence names the recipient because it describes the
     * caller's own past action. Here there is no "own": the bucket is shared by everybody who ever
     * typed anything folding to this inbox, so echoing the address would attribute a stranger's
     * request to this caller, and echoing it back to an unauthenticated caller who supplied it buys
     * nothing anyway.
     *
     * <p><strong>A refusal may only prescribe an action its reader can perform.</strong> The reader
     * here is somebody trying to create an account, and the only thing they can do is wait — so
     * that is the only thing either sentence asks of them. Neither says whether the address is
     * registered, whether mail was sent, or who sent it; the caller learns one bit they could
     * mostly infer anyway, that this inbox has had verification mail recently.
     *
     * <p><strong>"Was sent" is now a true sentence, and it was not while the bucket was
     * shared.</strong> A row in this budget is written by {@code register} and only by
     * {@code register}, in the same transaction as the {@code users} INSERT and immediately before
     * the mail goes out — so a row that survives to be counted means a message was sent. While
     * {@code resend-verification} shared the bucket, a row could equally have come from a resend at
     * an address with no {@code PENDING} account, where nothing at all was sent and this sentence
     * was simply false.
     */
    static final class RegistrationThrottleWording implements MailThrottleWording {

        @Override
        public String cooldown(String recipient, String wait, String addendum) {
            var message = new StringBuilder(
                    "A verification email was sent to this address very recently.");
            if (addendum != null && !addendum.isBlank()) {
                message.append(' ').append(addendum);
            }
            return message.append(" Please try again in ").append(wait)
                    .append(", or use the link you already received.").toString();
        }

        @Override
        public String recipientVolume(String wait) {
            return "Verification emails to this address are paused. Try again in " + wait + ".";
        }
    }
}
