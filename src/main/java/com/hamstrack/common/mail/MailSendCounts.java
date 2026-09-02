package com.hamstrack.common.mail;

import java.time.Instant;

/**
 * The only thing anybody is allowed to learn from {@code mail_send_events}: a few counts and two
 * timestamps about <em>one</em> recipient key, in one statement (HD-190 §7.1).
 *
 * <p>Every field is already narrowed to a single recipient key and a single {@code email_type} by
 * the query that produces it, so this record carries no address, no id and nothing that could be
 * rendered. That is the point — the table is not workspace-scoped, so the invariant keeping it out
 * of this project's top bug class is that <em>rows never leave the repository</em>.
 *
 * <p>The two timestamps exist so a refusal can name an <strong>honest</strong> wait. A ceiling that
 * answers "try again in one full window" over-states the wait every time but the first, and a
 * refusal that lies about its own remedy is how a retryable 429 gets read as a wall.
 *
 * <p><strong>"Window", not "day".</strong> The volume figures below are counted over
 * {@code MailThrottlePolicy.ceilingWindow}, which each kind of mail declares for itself and which
 * differs between them — a day for invitations, a quarter of an hour for password reset, an hour
 * for verification. The widths are not arbitrary: where the ceiling also withholds mail the
 * recipient asked for, it is simultaneously a bound on how long a victim cannot recover their own
 * account (HD-202). Nothing in this record knows which window it was handed; it is given two
 * instants and counts between them.
 *
 * <p><strong>Why the volume figure arrives in two pieces.</strong> The global ceiling counts
 * <em>the caller's own sends individually and every other sender once</em> (§6.3). Counting raw
 * sends let one account spend a stranger's whole allowance and lock every other workspace out
 * of inviting that person; counting only distinct senders would have let one account ring the
 * doorbell all day, bounded by nothing but its own cooldown. Splitting the figure gives both bounds
 * from one scan: no single account may exceed the cap on one address, and no single account may
 * occupy more than one of the cap's slots as far as anybody else is concerned.
 *
 * <p><strong>Both of those are bounds PER ACCOUNT, and neither is the bound on the inbox.</strong>
 * The two together let N colluding accounts deliver {@code N * (cap - N + 1)} messages to one
 * address in a day — {@code floor((cap+1)^2 / 4)} at the worst N, so nine at the default cap of
 * five rather than five. That is the accepted price of not handing one account a way to spend a
 * stranger's whole allowance, but it is superlinear, and anyone raising
 * {@code app.invites.max-per-recipient-per-day} is buying more of it than the number suggests. The
 * arithmetic is on that property.
 *
 * @param samePair                    sends to this key, from this sender, inside the cooldown
 *                                    window
 * @param samePairLatest              when the most recent of those was — {@code null} when there
 *                                    are none. The cooldown lifts at
 *                                    {@code samePairLatest + cooldown}
 * @param recipientWindowOwn          sends to this key from <em>this</em> sender inside the ceiling
 *                                    window (for an anonymous caller: from the shared anonymous
 *                                    bucket), counted one per send
 * @param recipientWindowOtherSenders how many <em>distinct other</em> senders reached this key
 *                                    inside the ceiling window, however many times each of them
 *                                    sent. All anonymous senders collapse into one bucket, because
 *                                    "who submitted the form" is not knowable and must not be
 *                                    guessable
 * @param recipientWindowOldest       when the oldest send inside the ceiling window was
 *                                    ({@code null} when there are none). <strong>A lower bound on
 *                                    when the volume figure can next fall</strong>, not the moment
 *                                    it certainly does: ageing out one of several sends by the same
 *                                    other sender does not free that sender's slot. Understating is
 *                                    the safe direction — the caller retries and is refused again —
 *                                    and the value is coarsened before it leaves the process anyway
 */
public record MailSendCounts(long samePair, Instant samePairLatest,
                             long recipientWindowOwn, long recipientWindowOtherSenders,
                             Instant recipientWindowOldest) {

    /** What the global volume ceiling is compared against. See the record javadoc for the shape. */
    public long recipientWindow() {
        return recipientWindowOwn + recipientWindowOtherSenders;
    }
}
