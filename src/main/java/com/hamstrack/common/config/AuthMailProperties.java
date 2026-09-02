package com.hamstrack.common.config;

import com.hamstrack.common.ratelimit.MailThrottlePolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The ceilings on the mail anybody on the internet can aim at a stranger (HD-202):
 * {@code POST /api/auth/forgot-password}, {@code POST /api/auth/resend-verification} and
 * {@code POST /api/auth/register}.
 *
 * <p><strong>What this closes.</strong> All three were "look the address up and send". Their
 * only budget was the per-IP window on {@code /api/auth/*} — and a budget keyed on where a request
 * came from can always be widened by coming from somewhere else, which is why the account under
 * attack is what has to be counted. That was survivable only while the per-IP key was accidentally
 * one bucket for the whole internet: {@code RATE_LIMIT_TRUST_FORWARDED_FOR} never reached the
 * production container, so every request keyed on the reverse proxy's own address and 15 a minute
 * was the ceiling for everybody at once. HD-199 delivered that flag, correctly, and in doing so
 * turned one bucket into one per visitor — the deployment's Caddy sets
 * {@code header_up X-Forwarded-For {client_ip}}, which is the real visitor resolved through
 * {@code trusted_proxies}, not the edge peer. A rotating proxy pool therefore has effectively
 * unlimited buckets, every one of which can be aimed at a single address, and every message is sent
 * by us, from a verified domain, with the victim's own address in {@code To}.
 *
 * <p><strong>{@code register} is on this mechanism, and the reason it once was not is the exact
 * mistake the mechanism exists to delete.</strong> The written exemption said registration can
 * produce at most one message per address, because the second attempt is a {@code 409}. True per
 * <em>address</em>, and an address is not the unit of harm: {@code users.email} is unique on
 * {@code lower(email)} — the SPELLING — while every ceiling here counts
 * {@code MailAddresses.throttleKey} — the INBOX. So {@code victim+1@gmail.com},
 * {@code victim+2@gmail.com} and {@code v.i.ctim@googlemail.com} are three rows, three
 * {@code 201}s, three verification mails and one inbox. That is the one-keystroke re-spelling
 * defeat {@code MailAddresses.throttleKey} was written to close, reproduced against a door left
 * off the mechanism on purpose.
 *
 * <p><strong>Both numbers are spent per KIND of mail and per RECIPIENT, never per caller.</strong>
 * They are handed to {@link MailThrottlePolicy} beans and enforced by
 * {@code RecipientMailThrottle}, the same mechanism and the same {@code mail_send_events} table the
 * invitation ceilings use — one control for "this instance is about to mail an address somebody
 * typed", rather than a second limiter that half-overlaps it. Being in PostgreSQL, they are
 * cluster-wide and exact: N replicas allow the configured number, not N times it.
 *
 * <p><strong>One pair of numbers for every auth mailer, deliberately.</strong> The flows have the
 * same shape (a form, one address, one link) and the same failure mode, and the budgets
 * are already separate without separate knobs, because counts are taken per {@code email_type}. More
 * pairs would be more environment variables an operator has to reason about to express the
 * thing they always want, which is "how much mail may one address get from the login screen".
 *
 * <p><strong>Which means the cap is per BUDGET, and the budgets add up — as RATES, not as a
 * multiple.</strong> There are three here — reset, resend-verification, and registration — and
 * each is spent over <em>its own</em> window, so "3 × the cap across the widest window" is the
 * wrong arithmetic and understates it by two. At {@code maxPerRecipientPerWindow = 5}: reset is
 * 5 per quarter-hour, which is <strong>20 an hour</strong> because that bucket refills four times
 * inside one; resend-verification is 5 an hour; registration is 5 an hour. One inbox is therefore
 * bounded at <strong>30 pieces of auth mail an hour</strong>, which is also the number the "20
 * reset mails an hour is a nuisance" sentence below is measured against.
 * Registration got a bucket of its own in review, and the reason
 * is on {@code AuthMailThrottleConfig}: while it shared resend-verification's, five
 * {@code resend-verification} requests an hour at an address with NO account — which send nothing
 * and log nothing above {@code DEBUG} — denied signup to every spelling of that inbox, free,
 * silently, and refillable for ever. The third term in that sum is what it cost.
 *
 * <h2>The window is fixed, and the mailers do not all get the same width</h2>
 * Neither {@link #PASSWORD_RESET_CEILING_WINDOW} nor {@link #VERIFICATION_CEILING_WINDOW} is a
 * property (the third policy reuses the second's width). The width is a security decision rather
 * than a capacity one, and it is the decision
 * that separates these ceilings from the invitation cap's fixed day: <em>a ceiling on a recovery
 * flow is also a denial of it</em>. Anybody may type a victim's address into
 * {@code forgot-password}, so whoever fills that window decides how long the victim cannot reset
 * their password — and nothing tells them, because the refusal is silent.
 *
 * <p><strong>The exchange rate, stated so nobody has to re-derive it.</strong> The cap is five in
 * both cases; what the width changes is this:
 *
 * <table border="1">
 *   <caption>What the ceiling window buys and costs, at a cap of five</caption>
 *   <tr><th></th><th>cooldown only</th><th>5 / 15 min</th><th>5 / 60 min</th></tr>
 *   <tr><td>mail ceiling, one inbox</td><td>60/h</td><td>20/h</td><td>5/h</td></tr>
 *   <tr><td>burst denial from 5 requests</td><td>5 min</td><td>15 min</td><td>60 min</td></tr>
 *   <tr><td>effort to sustain total denial</td><td>1 req/min</td><td>1 req/3 min</td>
 *       <td>1 req/12 min</td></tr>
 * </table>
 *
 * <p>Read the bottom row first: <strong>sustained denial is achievable at every setting</strong>.
 * No per-address ceiling can be built that cannot be filled, so a wide window does not
 * <em>prevent</em> the denial — it only lowers the attacker's effort. The security value of a wide
 * window is therefore concentrated entirely in the mail-volume bound, and its cost is concentrated
 * entirely in the HIT-AND-RUN case, which is the common one: a script firing five requests once
 * buys the whole window, and an hour is qualitatively different from a quarter of an hour for a
 * locked-out person who is told nothing. Twenty mails an hour to one inbox is a nuisance; sixty is
 * the deliverability number, and a quarter-hour window stays a factor of three below it.
 *
 * <p>So <strong>password reset gets the quarter-hour</strong> and <strong>verification keeps the
 * hour</strong>. The recovery-denial argument is specific to password reset: resend-verification
 * locks nobody out of an account they already hold, and it is the flow most exposed to the
 * register-side mail bomb above, so the tighter mail ceiling is the right half of the trade there.
 *
 * <p><strong>The residual is stated where an operator meets it, in {@code docs/self-hosting.md}
 * — both halves of it.</strong> A burst is bounded by the window; the sustained version is not
 * bounded at all. An attacker who spends the cap every window, from any address, forever, holds a
 * named person out of password recovery indefinitely, is never refused, and therefore appears in no
 * refusal metric. That second sentence used to live only in a developer document, which made this
 * javadoc's own claim false; the operator table and the silent-refusal note beneath it now carry
 * it, together with the two queries that can see it.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, the {@link InviteProperties}
 * pattern): an out-of-range value aborts startup rather than being corrected behind the operator's
 * back. <strong>There is no "unlimited" value</strong> — {@code 0} is out of range on both; the off
 * switch is {@code app.rate-limit.enabled}, which turns off the ceilings and not the bookkeeping.
 *
 * <p><strong>The {@code @Max} below is a hand-copy of a {@link Duration}, and it is allowed to be
 * one only because the real check is elsewhere.</strong> An annotation cannot read
 * {@code Duration.toMinutes()}, so the bound on the cooldown is written as a literal that has to
 * stay one minute inside {@link #PASSWORD_RESET_CEILING_WINDOW}. Nothing keeps them in step by
 * construction — but nothing has to: {@link MailThrottlePolicy}'s canonical constructor refuses a
 * policy whose cooldown is not narrower than its own ceiling window, so a stale copy here does not
 * ship a broken ceiling, it fails at bean creation with a message naming both widths.
 * {@code InviteProperties.maxPerRecipientPerDay} carries the same arrangement for the same reason.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}, and it must never become
 * profile-gated</strong> — the argument is {@link InviteProperties}', unchanged: a DC install with
 * public signup switched on has exactly the Cloud abuse profile, so a profile-keyed default would
 * protect Cloud and leave the actually exposed install on the loose numbers. Two of these three
 * endpoints are open in <em>both</em> modes regardless of {@code PUBLIC_SIGNUP_ENABLED}, which is
 * what makes that argument stronger here than it was there.
 */
@Validated
@ConfigurationProperties(prefix = "app.auth-mail")
public record AuthMailProperties(
        /*
         * How long ONE ADDRESS must wait before another reset (or another verification) link can be
         * sent to it, whoever asks. There is no per-sender half to this: the flows are anonymous, so
         * all requests share one bucket per address - "who submitted the form" is not knowable and
         * must not be guessable, and keying it on anything the request carries would hand an
         * attacker a fresh cooldown per request.
         *
         * ONE MINUTE, because that is what an honest mistyped-my-address-twice user needs and what
         * a script cannot use. The person who clicks "send me a link" twice in ten seconds already
         * has the first link; the second request is dropped and they are told what they were told
         * the first time, which is the whole point of the endpoint's uniform answer.
         *
         * Capped at 14 = one minute INSIDE the narrowest of the fixed volume windows below (15,
         * PASSWORD_RESET_CEILING_WINDOW), because one property feeds every policy here. A cooldown
         * as wide as the window it sits inside makes that window's volume cap unreachable - the
         * first send starts a cooldown that runs to the far edge of the window, so every send that
         * would have counted towards the cap is refused by the cooldown first - and
         * MailThrottlePolicy refuses to build such a policy at bean creation.
         *
         * THE BOUND IS EXCLUSIVE, WHICH IS WHY IT IS 14 AND NOT 15. It used to be 15 against a
         * guard that only rejected a STRICTLY wider cooldown, so an operator could set exactly the
         * unreachable cap the guard's own message says it exists to prevent, from inside the
         * documented range, with no error. The guard is now >= and this is window-minus-one; see
         * the record javadoc for why a hand-copy is safe rather than merely tolerated.
         */
        @DefaultValue("1") @Min(1) @Max(14) int recipientCooldownMinutes,

        /*
         * How many of EACH kind of mail one address may receive per WINDOW, counted across
         * everybody who asked. Separate buckets per kind, so this is the reset cap AND,
         * independently, the verification cap - and the two windows are different widths, which is
         * why this is named per-window rather than per-hour. The widths and the reasoning are on
         * PASSWORD_RESET_CEILING_WINDOW / VERIFICATION_CEILING_WINDOW.
         *
         * Five, spaced by the cooldown above, is more than any honest sequence needs: a link arrives
         * on the first request, and requests two through five are the ones spent on a spam folder, a
         * typo and a second device. It is finite for a script, which is the requirement - without
         * it the only bound was per-IP, and IPs are what an attacker has the most of.
         *
         * RAISING THIS RAISES TWO THINGS AT ONCE, in opposite directions, and they do not trade off
         * the way the invite cap's do. It raises how much mail one address can be sent in a window
         * (harassment, and our sending reputation), and it LOWERS how easily somebody can lock a
         * named person out of their own account recovery for the rest of it. Neither direction is
         * free and the second one is the one nobody expects, so move it in small steps and watch the
         * password_reset_recipient_window / verification_recipient_window meters - which, because
         * the refusal is silent by design, are the only place either effect is visible at all, and
         * which do NOT see the sustained attack (it is never refused; the concentration gauge and
         * the mail_send_events query in docs/self-hosting.md are what see that one).
         *
         * Cluster-wide and exact (state in PostgreSQL), unlike the in-memory budgets: N replicas
         * allow this many, not N x this many.
         */
        @DefaultValue("5") @Min(1) @Max(1000) int maxPerRecipientPerWindow
) {

    /**
     * The window {@link #maxPerRecipientPerWindow} is counted over for {@code forgot-password}
     * mail — <strong>a quarter of an hour, fixed, and the argument for that number rather than the
     * hour it started as is the table on the record javadoc above</strong>.
     *
     * <p>The short version: sustained denial is achievable at every width, so the width buys only
     * the mail bound and costs only the length of a hit-and-run lockout. Twenty reset mails an hour
     * to one inbox is a nuisance and stays a factor of three under the deliverability number; sixty
     * minutes of silent lockout, bought with five requests, is not.
     */
    public static final Duration PASSWORD_RESET_CEILING_WINDOW = Duration.ofMinutes(15);

    /**
     * The window {@link #maxPerRecipientPerWindow} is counted over for verification mail —
     * <strong>an hour, fixed</strong>.
     *
     * <p>Wider than its reset sibling on purpose. The "a ceiling on a recovery flow is also a
     * denial of it" argument does not apply: resend-verification locks nobody out of an account
     * they already hold, and a person who never received their link can ask again next hour without
     * having lost anything. So the tighter mail bound is the half of the trade worth having.
     *
     * <p><strong>This width is also what the registration policy declares</strong>
     * ({@code AuthMailThrottleConfig.registrationVerificationMailThrottlePolicy}), which is a
     * separate BUDGET spending the same window. It is not "the budget register spends" — that
     * sentence was true until the split and is the one this constant kept longest. Register is the
     * flow most exposed to a mail bomb aimed at one inbox through many spellings, which is why an
     * hour rather than something looser is right for it too.
     */
    public static final Duration VERIFICATION_CEILING_WINDOW = Duration.ofHours(1);

    /**
     * How long an ANONYMOUS {@code mail_send_events} row is kept — precisely,
     * <strong>{@code min(this, app.invites.event-retention-days)}</strong>, and not the same kind
     * of number as the property.
     *
     * <p><strong>The {@code min} is the accurate statement and "far sooner, and that variable does
     * not reach them" was not.</strong> {@code MailSendEventRepository.deleteCreatedBefore} carries
     * no sender predicate, so the general sweep deletes anonymous rows too — this constant merely
     * gets to them first at the default of seven days. At {@code INVITE_EVENT_RETENTION_DAYS=2},
     * which is the lowest legal value, the two coincide exactly and "far sooner" is false. The
     * relation is asserted at startup by {@code MailSendEventRetention}, because
     * {@code InviteProperties.MIN_EVENT_RETENTION_DAYS} happening to equal this constant is a
     * coincidence of two independently derived numbers: raise this to three and an operator sitting
     * at two silently gets two, with every sentence about it still reading as though they got three.
     *
     * <p>The seven-day default on the invite retention is bought for forensics: after a volume
     * alert fires, that table is the only thing that can answer <em>who</em>, and "who" is a
     * {@code sender_user_id}. An anonymous row has no sender to name — the column is {@code NULL}
     * by construction — so the only question it can answer is "which inboxes were aimed at", and
     * that question is asked over hours, not days.
     *
     * <p>It is also the row an <strong>unauthenticated</strong> caller can force us to write.
     * A fresh address is a fresh bucket, so every such request is <em>allowed</em>, and the write
     * rate is bounded only by a per-IP budget a proxy pool defeats. Seven days of that on the
     * primary database is a cost bought for nothing; two days covers the widest anonymous ceiling
     * window (an hour) many times over and still leaves more history than the six-hour
     * concentration gauge and the operator query behind it can ask for.
     *
     * <p>Not a property. Adding a knob would invite an operator to raise it, and the thing they
     * would be raising is how much unauthenticated write amplification the instance accepts.
     * Enforced at startup by {@code MailSendEventRetention} in both directions — against
     * {@link MailThrottlePolicy#MAX_CEILING_WINDOW}, so it cannot be narrowed under a ceiling it
     * feeds, and against the lowest legal invite retention, so the {@code min} above cannot quietly
     * stop being this constant.
     */
    public static final Duration ANONYMOUS_EVENT_RETENTION = Duration.ofDays(2);
}
