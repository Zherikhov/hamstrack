package com.hamstrack.common.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The ceilings on the invitation mailer (HD-190, {@code docs/design/invite-budget-proposal.md} §11.2).
 *
 * <p><strong>What this closes.</strong> Any account that can log in could make Hamstrack send mail
 * to any address on the internet, without limit, by typing that address into the invite box of a
 * workspace it created seconds earlier. Public signup is on in production and nothing bounds how
 * many workspaces one user creates, so the account costs one throwaway mailbox — and the mail
 * carries our SPF/DKIM, so a stranger's spam complaints land on the same sending reputation that
 * carries verification and reset mail. The first symptom of either failure is that new users stop
 * receiving their verification links: signup breaks silently, from a cause with no error in it.
 *
 * <p><strong>Three windows and no per-minute one.</strong> The quota being protected is
 * <em>3000 messages a month</em>. Any per-minute ceiling loose enough to let an admin paste twenty
 * addresses is 28 800/day, which spends the month before lunch — a rate window and a quota are
 * different units. So: an hourly window to bound a burst, a daily window to bound the spend, and a
 * cooldown to bound the harassment. The three ceilings are not one control at three granularities:
 * a window that bounds spend does not bound repetition at one address, and only a ceiling that
 * ignores the workspace sees the attack the ticket describes.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}, and it must never become
 * profile-gated.</strong> The DC abuse profile genuinely differs — a self-hoster runs their own
 * SMTP and {@code PUBLIC_SIGNUP_ENABLED} defaults to {@code false} there — and looser DC defaults
 * are still wrong, because the risk tracks the wrong variable: a DC install with public signup
 * switched on has exactly the Cloud abuse profile, so a profile-keyed default would protect Cloud
 * and leave the actually exposed install on the loose numbers. If a default ever needs to key on
 * something it is {@code PUBLIC_SIGNUP_ENABLED}, not the profile. The escape hatch is a number, not
 * a code path: a DC admin onboarding 300 people raises one env var for a day.
 *
 * <p><strong>Fail fast, never clamp</strong> ({@code @Validated}, the {@link SearchProperties} /
 * {@link RolesProperties} pattern): an out-of-range value aborts startup rather than being corrected
 * behind the operator's back. <strong>There is no "unlimited" value</strong> — {@code 0} is out of
 * range on every one of them; the off switch is {@code app.rate-limit.enabled}, and an operator who
 * wants no practical ceiling writes the top of the range.
 */
@Validated
@ConfigurationProperties(prefix = "app.invites")
public record InviteProperties(
        /*
         * Invitations ONE PRINCIPAL may send per hour - a fixed window, keyed on the user id,
         * counted in memory like every other volume budget in the app (ADR-0015: what gets
         * persisted is what is keyed on the VICTIM).
         *
         * Bounds a burst. The largest real invite burst the product supports is a single-address
         * form typed by one admin, so 20 an hour is invisible to honest use.
         */
        @DefaultValue("20") @Min(1) @Max(1000) int maxPerSenderPerHour,

        /*
         * Invitations ONE PRINCIPAL may send per day. THIS IS THE QUOTA CONTROL, and it is the
         * window a per-minute limiter cannot express: 100/day against a 3000/month provider quota
         * means a single abusive account consumes at most ~1/30 of the month per day - a condition
         * an operator has a month to notice rather than an afternoon.
         *
         * The two windows are independent, not multiples of each other: an account that spends its
         * hourly allowance five times over is done for the day.
         *
         * 100 in a day through a single-address form is already an unusual day, and this is
         * deliberately the first number an operator raises. A DC admin onboarding 300 people on
         * install day WILL hit it, and the answer is one env var raised for the day - which is why
         * these are properties with a documented range and not constants, and why the refusal does
         * not pretend the ceiling is a law of nature.
         *
         * In-memory and per node, so N replicas allow up to N x this. Accepted, and it is the
         * failure mode every other budget in the app already accepts: a bound on abuse, not an
         * invariant.
         */
        @DefaultValue("100") @Min(1) @Max(10_000) int maxPerSenderPerDay,

        /*
         * How long one principal must wait before inviting THE SAME ADDRESS again, ACROSS EVERY
         * WORKSPACE IN THE INSTANCE. This is the ticket's attack and this is its control: the
         * abuser presses "invite" at one victim from a succession of workspaces they create, so a
         * per-workspace bound inspects the wrong dimension and HD-133's UNIQUE(workspace_id, email)
         * inspects the same wrong dimension with a constraint. Only a key that ignores the
         * workspace sees the pattern.
         *
         * Keyed on (sender, recipient) rather than on the recipient alone, on purpose: a global
         * recipient key would make an ordinary refusal disclose, to an admin in workspace A, that
         * somebody elsewhere invited this address recently - a small cross-tenant disclosure paid
         * on every honest re-type. Keyed on the pair, the refusal only ever tells the caller about
         * the caller's own past action, and it still closes the attack completely, because the
         * attack is one sender. The global key is used for the much higher daily cap below, where
         * the same disclosure is paid essentially never.
         *
         * Its state is PERSISTED (mail_send_events), and not for durability - see ADR-0015. Derived
         * from workspace_invites it would be reset by three existing paths, one of which is the
         * victim's own decline button.
         *
         * Capped at 1440 (one day) so the cooldown window never reaches further back than the daily
         * window; the throttle's single SELECT passes the earlier of the two explicitly rather than
         * relying on that, but nothing above a day would be a cooldown anyway.
         */
        @DefaultValue("60") @Min(1) @Max(1440) int recipientCooldownMinutes,

        /*
         * How many INVITATIONS one address may receive per day from senders other than yourself,
         * plus how many of your own you may send to it - one number, spent on both.
         *
         * WHAT IS COUNTED, EXACTLY: your own sends one each, and every OTHER sender once, however
         * many times they sent. Counting raw sends (the first cut) inverted the control into a
         * weapon: one account spending five legal sends, spaced past the cooldown, blocked EVERY
         * workspace on the instance from inviting that named person for the rest of the day, at
         * ~5 requests a day, indefinitely, under both volume alerts. Counting only distinct senders
         * would go too far the other way and let one account ring the doorbell all day, bounded by
         * nothing but its own cooldown. Counting both halves keeps the intended arithmetic - five
         * throwaway accounts inviting one victim once each still trip it at the sixth - while
         * capping any single account at this many sends to one address and at one slot as far as
         * everybody else is concerned. So the price of denying somebody their invitations is one
         * fresh mailbox per slot, which is where it was always meant to be.
         *
         * Defence in depth against multi-account harassment. Everything sender-keyed multiplies by
         * the number of accounts an attacker holds and an account costs one mailbox on a catch-all
         * domain, so five throwaway accounts inviting one victim once each per cooldown window sit
         * under every sender-keyed ceiling and are still a doorbell. A recipient-keyed ceiling is
         * invariant under attacker identity, which is the property that makes it worth its cost.
         *
         * PER KIND OF MAIL, NOT PER ADDRESS. mail_send_events counts within one email_type, so this
         * is the INVITATION budget: after HD-202 one address can receive this many invitations PLUS
         * the reset cap PLUS the verification cap in a day. Deliberate - a shared bucket would let a
         * stranger's invitations suppress the victim's own password reset, which is the one piece of
         * mail they actually asked for.
         *
         * The key counted is the INBOX (MailAddresses.throttleKey), not the spelling: +tags and
         * Gmail dots fold together, or the ceiling counts mailboxes it can tell apart rather than
         * inboxes a human opens.
         *
         * Five, because a human genuinely invited to five distinct Hamstrack workspaces within one
         * day does not meaningfully exist. THE ACCEPTED TRADE-OFF, STATED PLAINLY: at the sixth
         * invitation an innocent admin learns that this address has received invitations from
         * workspaces they cannot see. That is a cross-tenant disclosure. It is bounded to one bit,
         * only reachable by a caller who already holds workspace.member.manage and already knows
         * the address, costs nothing on the first five, and buys a harassment control that cannot
         * be bought any other way. The refusal's WORDING is what keeps it at one bit. Its
         * Retry-After coarsens a DEADLINE rather than a duration - see
         * RecipientMailThrottle.secondsUntilBucketedDeadline, where the difference between those
         * two is the whole of that control - so it raises the price of the stranger's send instant
         * rather than bounding the disclosure to one bit, which no header can do against a caller
         * willing to wait for the refusal to lift.
         *
         * THE PER-INBOX CEILING THIS BUYS IS QUADRATIC IN THIS NUMBER, NOT LINEAR - read that
         * before turning the knob. Because the count is "own sends one each, other senders once",
         * N colluding accounts each send until own + (N-1) reaches the cap, so the total messages
         * one inbox receives in a day is N * (cap - N + 1), maximised at N = (cap+1)/2 and worth
         * floor((cap+1)^2 / 4):
         *
         *   cap = 5  -> 9 messages/day   (raw counting gave a hard 5)
         *   cap = 10 -> 30 messages/day
         *
         * The 1.8x at the default is the price of closing the denial-of-onboarding weapon that raw
         * counting was, and it is a good trade. Doubling the cap TRIPLES the harassment ceiling.
         *
         * This is the number most likely to need adjusting after launch - it is the only ceiling
         * whose false positive lands on an innocent third party. If it proves noisy, raise it
         * rather than removing it: the control degrades gracefully with the number and disappears
         * entirely without it. Raise it in small steps and with the quadratic above in mind.
         */
        @DefaultValue("5") @Min(1) @Max(1000) int maxPerRecipientPerDay,

        /*
         * How long mail_send_events rows are kept.
         *
         * It has to outlast EVERY ceiling window, because a row the sweep deleted is a row no
         * ceiling can count - the ceiling silently shortens to the retention, with no error and no
         * log line. THERE ARE TWO WINDOWS, and the second is the one this comment used to forget:
         * the per-(sender, recipient) cooldown, up to 1440 minutes, and the GLOBAL DAILY CAP, which
         * is a fixed 24 hours (RecipientMailThrottle.DAY) and is not configurable at all. So the
         * floor is a day whatever the cooldown is set to, which is why the minimum here is 2 rather
         * than 1: at 1 day the retention would be EXACTLY the daily window, and "exactly" is not
         * cover - two replicas whose clocks differ by a second are enough for one node's sweep to
         * delete a row the other node's daily count still needs, and the only symptom is a send that
         * should have been refused. The relationship is asserted at startup below rather than left
         * to independently editable annotations in two different files. (HD-202 will want a 24h
         * resend-verification cooldown, which IS 1440 minutes - and is covered by 2 days, which is
         * the point of asserting the pair instead of hoping.)
         *
         * The rest of the window is forensic: after the MailDailyVolumeHigh alert fires this table
         * is the only place that can answer WHO and WHICH ADDRESSES, and the metrics deliberately
         * cannot (bounded cardinality is non-negotiable). Seven days of low-volume rows is nothing;
         * it is also short enough that the table is not a durable store of who-emailed-whom.
         *
         * The sweep runs whether or not app.rate-limit.enabled is true - rows are cheap and the
         * alternative is an unbounded table on any instance that toggles the switch.
         */
        @DefaultValue("7") @Min(2) @Max(90) int eventRetentionDays
) {

    private static final int MINUTES_PER_DAY = 1440;

    /**
     * The width of the <strong>fixed</strong> ceiling window — {@code RecipientMailThrottle.DAY},
     * the window {@link #maxPerRecipientPerDay} is counted over. It is restated here rather than
     * imported because it is a {@code private} constant in another package; the pointer back is on
     * that field, since the person who widens it is standing there and not here.
     */
    private static final int FIXED_CEILING_WINDOW_MINUTES = MINUTES_PER_DAY;

    /**
     * The knobs that have to be read together, and that live in different files.
     *
     * <p>{@code MailSendEventRetention} deletes by {@code created_at} and the ceilings count by
     * {@code created_at}; if the sweep's cutoff ever falls <em>inside</em> a live ceiling window,
     * that ceiling quietly becomes the retention. Nothing would fail — the count would simply come
     * back lower, and the throttle would hand out a send it meant to refuse.
     *
     * <p><strong>There are TWO ceiling windows and this compares against the wider of them.</strong>
     * The first cut compared only the configurable cooldown, which made the assertion guarantee half
     * of what its own message claims: {@link #maxPerRecipientPerDay} is counted over a fixed 24
     * hours that no property can lower, so a one-day retention was <em>exactly</em> a daily window,
     * and exact is not cover. Two replicas whose clocks differ by a second are enough for one node's
     * sweep to remove a row the other node's daily count still needs — the global cap under-counts
     * and permits a send it meant to refuse, with no error and no log line, which is the precise
     * failure this assertion exists to delete. Hence {@code max(cooldown, fixed daily window)}.
     *
     * <p><strong>At today's annotation values it can never be the SOLE reason a boot is refused,
     * and that is the point rather than dead code.</strong> {@code @Min(2)} on the retention against
     * {@code @Max(1440)} on the cooldown means no value that satisfies the other constraints can
     * falsify this one — and {@code @Min(2)} is itself derived from this predicate, since one day
     * would be refused by it and allowing one day would make the documented range a lie. It still
     * <em>fires</em>: Hibernate Validator evaluates every constraint, so
     * {@code event-retention-days=1} is reported with both messages and this one is the message that
     * explains why (verified by booting the app; {@code =1} with {@code cooldown=1439} used to
     * start, and is exactly the zero-margin case this widening closes). What it survives to catch is
     * <em>drift between annotations</em>: raise the cooldown cap past a day, or lower the retention
     * floor back to one, and this predicate becomes load-bearing on its own. Either edit is
     * individually defensible where it lives and neither has a reason to visit the other.
     *
     * <p><strong>The third drift — widening {@code RecipientMailThrottle.DAY} — is the one this
     * assertion CANNOT catch, and claiming it can would restate, one level up, the defect the
     * widening above removed.</strong> {@code FIXED_CEILING_WINDOW_MINUTES} is a hand-copied 1440:
     * widen that constant and the copy does not move, so the predicate goes on comparing against
     * the old window and goes on PASSING while guaranteeing less than its message says — no error,
     * no log line, which is the silent under-cover this pair of knobs exists to prevent. Nothing
     * evaluated inside this record can notice, because the value it would have to read is the one
     * that did not change. Only an equality check between the two constants catches that, it is a
     * test rather than a constraint, and this annotation must not be cited in its place.
     *
     * <p>A unit test wanting the {@code false} branch alone constructs the record directly
     * ({@code eventRetentionDays = 1}) rather than binding properties.
     */
    @AssertTrue(message = "app.invites.event-retention-days must cover more than the widest ceiling "
            + "window — that is the LARGER of app.invites.recipient-cooldown-minutes and the fixed "
            + "24h window of app.invites.max-per-recipient-per-day. mail_send_events rows are swept "
            + "on the retention window and counted by both ceilings, so a shorter retention silently "
            + "shortens whichever ceiling it undercuts. Raise the retention: while the cooldown is "
            + "capped at 24h, the fixed daily window is what this compares against, so lowering the "
            + "cooldown cannot satisfy it")
    public boolean isRetentionLongerThanWidestCeilingWindow() {
        return (long) eventRetentionDays * MINUTES_PER_DAY
               > Math.max(recipientCooldownMinutes, FIXED_CEILING_WINDOW_MINUTES);
    }
}
