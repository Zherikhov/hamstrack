package com.hamstrack.common.mail;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
     * <p>The daily figure comes back in two pieces on purpose (see {@link MailSendCounts}): the
     * caller's own sends counted one each, and <em>distinct</em> other senders counted once each
     * however much they sent. That is what stops one account from spending a stranger's whole daily
     * allowance, while still bounding what one account can put into one inbox.
     *
     * @param from the earlier of the two window starts, passed explicitly rather than assumed to be
     *             {@code dayFrom}. The cooldown is capped at 1440 minutes, so today it never reaches
     *             further back than the daily window — but a correctness that depends on the value
     *             of a {@code @Max} is a correctness that breaks when somebody raises it.
     */
    @Query("""
            SELECT new com.hamstrack.common.mail.MailSendCounts(
                     coalesce(sum(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :cooldownFrom then 1L else 0L end), 0L),
                     max(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :cooldownFrom then e.createdAt end),
                     coalesce(sum(case when e.senderUserId = :senderUserId
                                        and e.createdAt > :dayFrom then 1L else 0L end), 0L),
                     count(distinct case when coalesce(e.senderUserId, :anonymous) <> :senderUserId
                                          and e.createdAt > :dayFrom
                                         then coalesce(e.senderUserId, :anonymous) end),
                     min(case when e.createdAt > :dayFrom then e.createdAt end))
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
                                        @Param("dayFrom") Instant dayFrom,
                                        @Param("from") Instant from);

    /**
     * The same values for an <strong>anonymous</strong> sender — HD-202's forgot-password and
     * resend-verification flows, where {@code sender_user_id} is NULL.
     *
     * <p>A separate method rather than a null-safe comparison inside the one above, and the reason
     * is semantic before it is technical: anonymous sends share <em>one</em> bucket per key — one
     * cooldown, and one slot of the daily ceiling — because "who submitted the form" is not knowable
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
                                        and e.createdAt > :dayFrom then 1L else 0L end), 0L),
                     count(distinct case when e.senderUserId is not null
                                          and e.createdAt > :dayFrom then e.senderUserId end),
                     min(case when e.createdAt > :dayFrom then e.createdAt end))
              FROM MailSendEvent e
             WHERE e.emailType = :emailType
               AND e.recipientKey = :recipientKey
               AND e.createdAt > :from
            """)
    MailSendCounts countRecentForAnonymous(@Param("emailType") String emailType,
                                           @Param("recipientKey") String recipientKey,
                                           @Param("cooldownFrom") Instant cooldownFrom,
                                           @Param("dayFrom") Instant dayFrom,
                                           @Param("from") Instant from);

    /**
     * The retention sweep (§7.4). Plain {@code @Modifying}: this transaction holds no managed
     * {@code MailSendEvent} — nothing reads them back as entities — so there is nothing to clear
     * and nothing pending to discard.
     */
    @Modifying
    @Query("DELETE FROM MailSendEvent e WHERE e.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
