package com.hamstrack.common.mail;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate counts over {@code mail_send_events}, and nothing else (HD-190 §7.1, §9.2).
 *
 * <p><strong>It deliberately does not extend {@code JpaRepository}</strong> — the
 * {@code RoleRepository} precedent. This table is <em>not workspace-scoped</em> and it holds
 * recipient addresses, which is the exact shape of this project's top bug class. Inheriting
 * {@code findAll} / {@code findById} would put row-returning methods on it by default, and a
 * default is what somebody eventually calls. What is left here answers only questions of the form
 * "how many, and when", already narrowed to one recipient key by the query itself.
 *
 * <p><strong>There is no {@code save} either</strong>, for the same reason: {@code save} returns the
 * entity, and "no declared method returns a {@code MailSendEvent}" is a property a test can assert
 * by reflection. The insert goes through {@code EntityManager.persist} in
 * {@code RecipientMailThrottle}, which already holds an {@code EntityManager} for the advisory lock.
 * Auditing is unaffected — {@code @CreatedDate} fires on {@code @PrePersist}.
 *
 * <p><strong>Every predicate is on {@code recipientKey}, never on {@code recipientEmail}.</strong>
 * The submitted address is stored to be echoed and investigated, not to be counted: counting it
 * counts spellings instead of inboxes, which is what made the first cut of these ceilings
 * decorative. A query here that filters on {@code recipientEmail} is a bug.
 *
 * <p><strong>A {@code findByRecipientKey} returning rows would be one too.</strong> No endpoint
 * reads this table, no DTO exposes it, and {@code workspace_id} is written and never queried; an
 * operator investigating an alert queries the database directly.
 *
 * <h2>What V21's index comments say, and where they are now wrong</h2>
 * An applied migration must not be edited, so V25 carries the correction — but a correction inside a
 * migration header only reaches somebody who opens that file, and anybody weighing the fate of an
 * index on this table is standing here instead. Both stale claims are about <em>counts</em>, which
 * is why neither aged well:
 *
 * <ul>
 *   <li>{@code idx_mail_send_events_recipient} is described there as "the throttle's only read".
 *       The throttle is one reader among several now, and any replacement sentence that names a
 *       number would be stale before the one after it is written. What is durable is the shape:
 *       every ceiling narrows to one {@code recipientKey} first, so that index is the access path
 *       for the ceilings and for nothing else.</li>
 *   <li>{@code idx_mail_send_events_sender} is described as "forensic, not hot", and V25 makes that
 *       <em>more</em> true rather than less. The anonymous half of the sender dimension has moved
 *       onto {@code idx_mail_send_events_anonymous} — leading {@code created_at} range, partial
 *       predicate matched, strictly smaller relation — and {@link #deleteAnonymousCreatedBefore}
 *       will very likely plan on it as well. What stays uniquely {@code _sender}'s is the non-NULL
 *       lookup an operator runs after {@code MailDailyVolumeHigh} ("what else did this account
 *       send?"). Confirm with {@code EXPLAIN} against a populated table before dropping anything.</li>
 * </ul>
 */
public interface MailSendEventRepository extends Repository<MailSendEvent, UUID> {

    /**
     * A sentinel standing in for "no authenticated sender", so anonymous rows can take part in a
     * {@code count(distinct ...)} instead of vanishing from it — {@code NULL} is not equal to
     * anything, itself included, so without this every anonymous send would either drop out of the
     * count or read as its own distinct sender. The nil UUID cannot collide with a real id: ours
     * are UUID v7, which is never all-zero.
     */
    UUID ANONYMOUS_SENDER = new UUID(0L, 0L);

    /**
     * Both recipient-keyed ceilings for one recipient key, in one indexed scan.
     *
     * <p>Every predicate that narrows rows is anchored on {@code (recipient_key, created_at)},
     * which is the index — the sender appears only inside the conditional aggregates, so this is
     * one scan over at most {@code from}'s worth of rows and not a bitmap-OR of two.
     *
     * <p>The volume figure comes back in two pieces on purpose (see {@link MailSendCounts}): the
     * caller's own sends counted one each, and <em>distinct</em> other senders counted once each
     * however much they sent. That is what stops one account from spending a stranger's whole
     * allowance, while still bounding what one account can put into one inbox.
     *
     * @param windowFrom the start of {@code MailThrottlePolicy.ceilingWindow}. Its width is the
     *             POLICY'S, not this method's, and the policies do not agree on it (HD-202).
     *             Nothing here assumes which, and nothing here should be given a list of them
     * @param from the earlier of the two window starts, passed explicitly rather than assumed to be
     *             {@code windowFrom}. Every configured cooldown is inside its own ceiling window
     *             today — {@code MailThrottlePolicy} refuses a policy where it is not — but a
     *             correctness that depends on the value of a {@code @Max} is a correctness that
     *             breaks when somebody raises it.
     */
    @Query("""
            SELECT new com.hamstrack.common.mail.MailSendCounts(
                     coalesce(sum(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :cooldownFrom then 1L else 0L end), 0L),
                     max(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :cooldownFrom then e.createdAt end),
                     coalesce(sum(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :windowFrom then 1L else 0L end), 0L),
                     count(distinct case when coalesce(e.senderUserId, :anonymous) <> :senderUserId
                                          and e.createdAt > :windowFrom
                                         then coalesce(e.senderUserId, :anonymous) end),
                     min(case when e.createdAt > :windowFrom then e.createdAt end))
              FROM MailSendEvent e
             WHERE e.emailType = :emailType
               AND e.recipientKey = :recipientKey
               AND e.createdAt > :from
            """)
    MailSendCounts countRecentForSender(@Param("emailType") String emailType,
                                        @Param("recipientKey") String recipientKey,
                                        @Param("senderUserId") UUID senderUserId,
                                        @Param("anonymous") UUID anonymous,
                                        @Param("cooldownFrom") Instant cooldownFrom,
                                        @Param("windowFrom") Instant windowFrom,
                                        @Param("from") Instant from);

    /**
     * The same values for an <strong>anonymous</strong> sender — HD-202's forgot-password and
     * resend-verification flows, where {@code sender_user_id} is NULL.
     *
     * <p>A separate method rather than a null-safe comparison inside the one above, and the reason
     * is semantic before it is technical: anonymous sends share <em>one</em> bucket per key — one
     * cooldown, and one slot of the volume ceiling — because "who submitted the form" is not knowable
     * and must not be guessable; keying them on a would-be identity would hand an attacker a fresh
     * cooldown per request. ({@code = NULL} is also never true in SQL, so folding the two into one
     * query would have silently disabled the cooldown for exactly those flows.)
     */
    @Query("""
            SELECT new com.hamstrack.common.mail.MailSendCounts(
                     coalesce(sum(case when e.senderUserId is null
                                        and e.createdAt > :cooldownFrom then 1L else 0L end), 0L),
                     max(case when e.senderUserId is null
                                        and e.createdAt > :cooldownFrom then e.createdAt end),
                     coalesce(sum(case when e.senderUserId is null
                                        and e.createdAt > :windowFrom then 1L else 0L end), 0L),
                     count(distinct case when e.senderUserId is not null
                                          and e.createdAt > :windowFrom then e.senderUserId end),
                     min(case when e.createdAt > :windowFrom then e.createdAt end))
              FROM MailSendEvent e
             WHERE e.emailType = :emailType
               AND e.recipientKey = :recipientKey
               AND e.createdAt > :from
            """)
    MailSendCounts countRecentForAnonymous(@Param("emailType") String emailType,
                                           @Param("recipientKey") String recipientKey,
                                           @Param("cooldownFrom") Instant cooldownFrom,
                                           @Param("windowFrom") Instant windowFrom,
                                           @Param("from") Instant from);

    /**
     * The retention sweep (§7.4). Plain {@code @Modifying}: this transaction holds no managed
     * {@code MailSendEvent} — nothing reads them back as entities — so there is nothing to clear
     * and nothing pending to discard.
     */
    @Modifying
    @Query("DELETE FROM MailSendEvent e WHERE e.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

    // NOTE, because two documents got this wrong: this sweep has NO SENDER PREDICATE, so it reaches
    // anonymous rows too. The anonymous cutoff below does not "own" them and this one does not
    // "miss" them - the effective anonymous lifetime is min(the two), which at the lowest legal
    // app.invites.event-retention-days is exactly the anonymous constant and no sooner.

    /**
     * The same sweep, restricted to rows an <strong>unauthenticated</strong> caller caused
     * (HD-202 review) — {@code sender_user_id IS NULL}.
     *
     * <p><strong>Why these are swept on a different, shorter clock.</strong> The seven-day default
     * behind {@link #deleteCreatedBefore} is bought for forensics, and forensics here means
     * answering <em>who</em> — a {@code sender_user_id}. An anonymous row has none by construction,
     * so the only question it can answer is which inboxes were aimed at, which is asked over hours.
     * It is also the row an anonymous caller can force us to write: a fresh address is a fresh
     * bucket, so every such request is allowed and every one inserts, bounded only by a per-IP
     * budget a proxy pool defeats. Keeping a week of that on the primary database is a cost bought
     * for nothing.
     *
     * <p>The two sweeps overlap and that is harmless — this one deletes a subset of what the other
     * eventually would, earlier. Running only this one would be the bug.
     */
    @Modifying
    @Query("DELETE FROM MailSendEvent e WHERE e.senderUserId IS NULL AND e.createdAt < :cutoff")
    int deleteAnonymousCreatedBefore(@Param("cutoff") Instant cutoff);

    /**
     * <strong>How concentrated anonymous auth mail is on its busiest single inbox</strong>, over
     * {@code since} — the one signal that sees the attack the refusal meters cannot (HD-202
     * review).
     *
     * <p>The denial-of-recovery attacker is never refused. To hold a victim's bucket full they send
     * exactly at the rate slots age out, spaced past the cooldown, and <em>every one of those
     * requests is allowed</em> — so {@code hamstrack_ratelimit_hit_total} stays at zero and the
     * only refusals the alert can see are the victim's own one or two attempts. A rule built on
     * refusals therefore fires for the noisy mail-bomb shape and never for the quiet, targeted one
     * it was written for.
     *
     * <p>What is always true, whichever ceiling did or did not fire, is that one inbox is receiving
     * an abnormal share of a flow that nobody authenticated asked for. This returns that number
     * and <strong>nothing else</strong>: the maximum row count over any single {@code recipient_key},
     * with no key attached. That restraint is the whole reason it can be a metric — no meter in
     * this product may carry an address, and a gauge labelled by {@code recipient_key} would put
     * one into Prometheus, into alert state, and out through the email contact point. An operator
     * who sees the number queries {@code mail_send_events} for the key, which is where an address
     * legitimately lives.
     *
     * <p>{@code sender_user_id IS NULL} rather than a type filter: what makes this row shape
     * suspicious is that nobody signed for it. Invitation traffic has {@code InviteVolumeUnaccepted}
     * and a named account behind every row.
     *
     * <p><strong>Native, and not because HQL could not express it.</strong> The shape is
     * {@code max()} over a {@code GROUP BY} in a derived table, which HQL does support — but this
     * one is read by an operator standing at a {@code psql} prompt with an alert in front of them,
     * next to the hand-run query {@code docs/self-hosting.md} and {@code rules.yml} give them for
     * the same table. Being readable in the same dialect as the thing an operator will actually
     * type is worth more here than portability we do not use.
     *
     * <p>The two are deliberately <em>not</em> the same statement, and an earlier version of this
     * javadoc claimed they were. The operator's query is
     * {@code SELECT recipient_key, email_type, count(*) … GROUP BY 1, 2 HAVING count(*) > 20} — it
     * answers <em>who</em>, split by kind, because that is what somebody holding an alert needs
     * next. This one is {@code max(count(*))} with no key attached at all, because it feeds a
     * metric and no meter in this product may carry an address. They agree on the population and on
     * nothing else, which is the point: the restriction is what makes this one publishable.
     *
     * <p><strong>Indexed by {@code idx_mail_send_events_anonymous} (V25), a PARTIAL index on
     * {@code (created_at, recipient_key) WHERE sender_user_id IS NULL}.</strong> Without it this is
     * a scan of the whole table filtered afterwards, every five minutes, from every replica — and
     * the population that makes it slow is the attack it exists to see, so the query degrades
     * exactly when it matters. Partial because the predicate is a constant and anonymous rows are
     * the minority; {@code recipient_key} is carried so the {@code GROUP BY} is answered from the
     * index.
     *
     * <p><strong>{@code @Transactional(readOnly = true)} is on the method, not on the caller.</strong>
     * {@code AnonymousMailConcentration.refresh} catches whatever this throws, and a {@code catch}
     * inside a transactional method catches nothing useful — the failed statement has already
     * marked the transaction rollback-only, so the commit on the way out replaces the swallowed
     * exception with an {@code UnexpectedRollbackException}. Declared here, the transaction (and the
     * {@code statement_timeout} {@code BoundedJpaTransactionManager} sets on it) is fully unwound
     * before the caller's handler runs.
     *
     * @return the largest per-key count; {@code 0} when there are no anonymous rows at all
     */
    @Transactional(readOnly = true)
    @Query(value = """
            SELECT coalesce(max(per_key), 0) FROM (
                SELECT count(*) AS per_key
                  FROM mail_send_events
                 WHERE sender_user_id IS NULL
                   AND created_at > :since
                 GROUP BY recipient_key) busiest
            """, nativeQuery = true)
    long maxAnonymousSendsToOneRecipient(@Param("since") Instant since);
}
