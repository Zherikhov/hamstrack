package com.hamstrack.common.mail;

import com.hamstrack.common.config.InviteProperties;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <strong>{@code app.rate-limit.enabled=false} turns off the ceilings, and NOT the bookkeeping or
 * the retention sweep</strong> (HD-190 sections 8.5 and 7.4, acceptance criteria 6 and 18).
 *
 * <p>Three separate decisions live under one flag here and it is easy to read them as one:
 *
 * <ul>
 *   <li><strong>The ceilings go off.</strong> They are rate limits, and this switch is the only way
 *       to turn any of them off — none of the five properties accepts an "unlimited" value, because
 *       {@code 0} is out of range on every one of them.</li>
 *   <li><strong>The recording keeps running.</strong> Costing one insert on a low-frequency write,
 *       and buying two things worth more: the operator's forensic trail keeps working (with the
 *       domain-only log line, it is the only bridge between a volume alert and "who"), and an
 *       instance that toggles the switch back on resumes with real history rather than with a blank
 *       window an abuser can walk straight through.</li>
 *   <li><strong>The sweep keeps running</strong>, for the same reason and one more: a sweep gated on
 *       this switch would turn "limiting is off" into "this table grows for ever".</li>
 * </ul>
 *
 * <p>Asserted at the throttle rather than at the endpoint. The switch is read in exactly two places
 * — {@code RecipientMailThrottle.requireAndRecord} and {@code InviteSenderVolumeBudget.require} —
 * and this file covers the first while {@code InviteSenderVolumeBudgetTest} covers the second; what
 * the endpoint adds over that is the wiring, which its own sibling
 * {@code InviteThrottleBehaviourTest} drives with the switch ON.
 *
 * <p>The scope of the switch is <em>every limiter that has an off switch</em>, which is not the same
 * as "every limiter" — the backlog-rebalance cooldown in {@code IssueRankService} is deliberately
 * outside it, and the wider claim has had to be corrected in five files. If a per-workspace stock
 * cap on outstanding invitations is ever built (section 6.4, recommended against) it does not join
 * this switch either: turning off brute-force protection is not a request to remove an unrelated
 * business rule.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class MailThrottleMasterSwitchTest {

    @Autowired RecipientMailThrottle throttle;
    @Autowired MailSendEventRetention retention;
    @Autowired InviteProperties inviteProperties;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    /**
     * The same sender, the same address, far past both recipient ceilings — and every one of them
     * is allowed. With the switch on, the second of these is a cooldown refusal
     * ({@code RecipientDailyCapTest}) and the sixth distinct sender is a daily-cap refusal.
     */
    @Test
    void theCeilingsAreOffAndEveryOtherwiseRefusedSendIsAllowed() {
        var recipient = address("switched-off");
        var sender = UUID.randomUUID();

        assertThatCode(() -> {
            for (int i = 0; i < 12; i++) {
                send(recipient, sender);
            }
            for (int i = 0; i < 12; i++) {
                send(recipient, UUID.randomUUID());
            }
        })
                .as("with the switch off, neither the per-(sender, recipient) cooldown nor the "
                    + "global daily cap may refuse anything")
                .doesNotThrowAnyException();
    }

    /**
     * <strong>And every one of those sends was still recorded.</strong> This is the half a reader
     * most easily assumes away, and the half that decides what happens the moment an operator turns
     * the switch back on: with recording gated too, the instance would resume with an empty window
     * and an abuser could walk through it at full speed while the ceilings looked armed.
     */
    @Test
    void theBookkeepingKeepsRunningWhileTheCeilingsAreOff() {
        var recipient = address("still-recorded");
        for (int i = 0; i < 4; i++) {
            send(recipient, UUID.randomUUID());
        }

        assertThat(rowsFor(recipient))
                .as("recording is not a rate limit — it is the operator's forensic trail, and the "
                    + "only thing that can answer WHO and WHICH ADDRESSES after the volume alert "
                    + "fires, because the metrics deliberately carry no ids and no addresses")
                .isEqualTo(4);
    }

    /**
     * <strong>The sweep runs whether or not limiting is on</strong> (acceptance criterion 18), and
     * it deletes by an absolute cutoff derived from {@code app.invites.event-retention-days}.
     *
     * <p>Its bound is a constraint rather than an observation: a swept row is a row no ceiling can
     * count, so a retention shorter than a ceiling window silently shortens that ceiling to it, with
     * no error and no log line. That pair is asserted at startup
     * ({@code InvitePropertiesTest.theRetentionCrossCheckRefusesAZeroMarginRetention}); what is
     * asserted here is that the sweep actually removes what it claims to and keeps what it claims
     * to — a sweep that deleted everything would satisfy "the table stays bounded" too.
     */
    @Test
    void theRetentionSweepDeletesOnlyRowsPastTheWindowAndRunsWithLimitingOff() {
        var recipient = address("retention");
        var stale = send(recipient, UUID.randomUUID());
        var fresh = send(recipient, UUID.randomUUID());
        backdate(stale, Duration.ofDays(inviteProperties.eventRetentionDays() + 1L));

        retention.sweep();

        assertThat(exists(stale))
                .as("a row older than app.invites.event-retention-days (%d) must be swept — the "
                    + "table is a bounded operational journal, not a durable store of "
                    + "who-emailed-whom, and it is not gated on app.rate-limit.enabled because "
                    + "that would make 'limiting is off' mean 'this table grows for ever'",
                    inviteProperties.eventRetentionDays())
                .isFalse();
        assertThat(exists(fresh))
                .as("and a row inside the window must survive it. Without this half, a sweep that "
                    + "deleted the whole table would pass — and would silently shorten every "
                    + "ceiling to nothing the moment the switch went back on")
                .isTrue();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * One allowed send, returning the id of the row it wrote.
     *
     * <p>The "it wrote a row" half is asserted here rather than left to blow up as a
     * {@code NoResultException} three frames down: with recording gated on the master switch — the
     * plausible tidy-up, since everything else under this branch is a rate limit — every test in
     * this file fails, and without this assertion they all fail with a JPA exception that names
     * neither the switch nor the reason.
     */
    private UUID send(String recipient, UUID sender) {
        transactions.executeWithoutResult(status -> throttle.requireAndRecord(
                EmailType.INVITE, recipient, sender, UUID.randomUUID()));
        var written = transactions.execute(status -> em.createQuery(
                        "SELECT e.id FROM MailSendEvent e WHERE e.recipientKey = :key "
                        + "ORDER BY e.createdAt DESC, e.id DESC", UUID.class)
                .setParameter("key", MailAddresses.throttleKey(recipient))
                .setMaxResults(1).getResultList());

        assertThat(written)
                .as("an allowed send must write its mail_send_events row EVEN WITH LIMITING OFF. "
                    + "The master switch turns off the ceilings, not the bookkeeping: recording "
                    + "costs one insert on a low-frequency write and buys the operator's forensic "
                    + "trail (the only thing that can answer WHO after a volume alert, since the "
                    + "metrics carry no ids) and a switch that can be turned back ON without "
                    + "handing an abuser a blank window to walk through")
                .isNotEmpty();
        return written.get(0);
    }

    private long rowsFor(String recipient) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(e) FROM MailSendEvent e WHERE e.recipientKey = :key",
                        Long.class)
                .setParameter("key", MailAddresses.throttleKey(recipient)).getSingleResult());
    }

    private boolean exists(UUID id) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(e) FROM MailSendEvent e WHERE e.id = :id", Long.class)
                .setParameter("id", id).getSingleResult()) > 0;
    }

    /**
     * Ages a row past the retention window. Native, because {@code created_at} is
     * {@code updatable = false} on the entity — deliberately, since the row is append-only — and
     * because {@code @CreatedDate} would overwrite anything set on the way in. Nothing about the
     * feature is being bypassed here: the sweep's predicate is on this column and this is the only
     * way to put a row on the far side of it without waiting a week.
     */
    private void backdate(UUID id, Duration age) {
        transactions.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE mail_send_events SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, Instant.now().minus(age))
                .setParameter(2, id)
                .executeUpdate());
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }
}
