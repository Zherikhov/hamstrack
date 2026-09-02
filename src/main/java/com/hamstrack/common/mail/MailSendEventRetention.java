package com.hamstrack.common.mail;

import com.hamstrack.common.config.AuthMailProperties;
import com.hamstrack.common.config.InviteProperties;
import com.hamstrack.common.ratelimit.MailThrottlePolicy;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps {@code mail_send_events} bounded (HD-190 §7.4).
 *
 * <p><strong>A retention must outlast every ceiling window it feeds, and that is a constraint
 * rather than an observation.</strong> A swept row is a row no ceiling can count, so a retention
 * shorter than a ceiling window silently shortens that ceiling to it — no error, no log line.
 * <strong>The comparison is against the widest window any policy is ALLOWED to declare</strong>
 * ({@code MailThrottlePolicy.MAX_CEILING_WINDOW}, enforced as a ceiling on every policy at bean
 * creation), never against an enumeration of today's windows: policies are added and their widths
 * are per-policy, so a list here would go stale one entry before it looked wrong.
 * {@code InviteProperties.isRetentionLongerThanWidestCeilingWindow} makes that comparison at
 * startup instead of leaving it to independently editable annotations in three files, and the
 * permitted minimum of two days exists because one day would be <em>exactly</em> the widest
 * permitted window, and exact is not cover.
 *
 * <p>Everything past that bound is forensic: after {@code MailDailyVolumeHigh} fires, this table is
 * the only place that can answer <em>who</em> and <em>which addresses</em>, and the metrics
 * deliberately cannot (bounded cardinality is non-negotiable, so no ids and no addresses reach
 * Prometheus). A week of low-volume rows costs nothing and is short enough that this is not a
 * durable store of who-emailed-whom.
 *
 * <p><strong>Rows nobody signed for live for {@code min(ANONYMOUS_EVENT_RETENTION,
 * app.invites.event-retention-days)}</strong> — two days and the operator's setting. The
 * {@code min} is the whole statement, and "far sooner, and the variable does not reach them" was
 * not: {@link MailSendEventRepository#deleteCreatedBefore} has no sender predicate, so the general
 * sweep deletes anonymous rows too; the anonymous cutoff merely gets there first at the default of
 * seven days, and at the lowest legal setting of two the two coincide exactly. The forensic value
 * of the long window is the {@code sender_user_id}, and an anonymous row has none; meanwhile it is
 * the one row shape an <em>unauthenticated</em> caller can force us to write, at a rate bounded
 * only by a per-IP budget a proxy pool defeats. Two sweeps, one table, and the shorter one deletes
 * a subset of what the longer one eventually would.
 *
 * <p><strong>It runs whether or not {@code app.rate-limit.enabled} is true.</strong> Events are
 * recorded regardless of the master switch — see {@code RecipientMailThrottle} for why — so a sweep
 * gated on the same switch would turn "limiting is off" into "this table grows for ever".
 *
 * <p><strong>Every node runs it, and that is the intended deployment behaviour.</strong> This is the
 * first {@code @Scheduled} job in the product that writes to the shared database — the others sweep
 * process-local maps, where "each node cleans its own" is trivially right and says nothing about
 * this case. On N Cloud replicas this fires N times an hour, and it is safe because the work is
 * idempotent and bounded by an absolute cutoff: the second replica's DELETE matches the rows the
 * first one already removed, which is none of them. No leader election, no advisory lock, no
 * {@code ShedLock}. <strong>A future DB-writing scheduled job may not copy this pattern without
 * making the same argument</strong> — anything that computes from what it reads, sends mail, or is
 * not idempotent needs single-execution, and inheriting this class's silence is how it would ship
 * without one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSendEventRetention {

    private final MailSendEventRepository repository;
    private final InviteProperties inviteProperties;

    /**
     * The anonymous retention is a constant rather than a property, so nothing binds it at startup
     * and nothing would notice it drifting out of step with the three numbers it is documented
     * against. This is that notice, and it makes all three comparisons.
     *
     * <p>The first compares against {@code MailThrottlePolicy.MAX_CEILING_WINDOW} rather than
     * against the anonymous policies' actual windows for the reason the class javadoc gives: an
     * enumeration of today's widths is a claim that goes stale at the next policy, and the constant
     * is already enforced as an upper bound on every one of them.
     *
     * <p>The second and third exist because <strong>an unasserted relation between two
     * independently derived numbers is a coincidence, and this file's prose reads as though it were
     * a guarantee.</strong> Neither is reachable today; both are cheap, both fail at startup rather
     * than at the one moment somebody needs the number, and each replaces a sentence that would
     * otherwise be silently false.
     */
    @PostConstruct
    void assertAnonymousRetentionCoversEveryCeiling() {
        if (AuthMailProperties.ANONYMOUS_EVENT_RETENTION
                .compareTo(MailThrottlePolicy.MAX_CEILING_WINDOW) <= 0) {
            throw new IllegalStateException(
                    "AuthMailProperties.ANONYMOUS_EVENT_RETENTION is "
                    + AuthMailProperties.ANONYMOUS_EVENT_RETENTION + ", which does not outlast "
                    + "MailThrottlePolicy.MAX_CEILING_WINDOW (" + MailThrottlePolicy.MAX_CEILING_WINDOW
                    + "). Anonymous mail_send_events rows are swept on that retention and counted by "
                    + "the recipient ceilings, so a retention inside a ceiling window silently "
                    + "shortens the ceiling to it, with no error and no log line — which is the "
                    + "exact failure the invite retention has an @AssertTrue for. Widen the "
                    + "retention or narrow MAX_CEILING_WINDOW.");
        }

        // The GAUGE's lookback, which is a different consumer of the same rows from the ceilings
        // above and is covered by MAX_CEILING_WINDOW only transitively. Narrow that constant one
        // day and AnonymousMailConcentration silently reports a truncated maximum - which reads as
        // "nobody is being targeted", i.e. the same no-error failure the first check exists to
        // delete, on the one signal that sees the attacker who is never refused.
        if (AuthMailProperties.ANONYMOUS_EVENT_RETENTION
                .compareTo(AnonymousMailConcentration.WINDOW) <= 0) {
            throw new IllegalStateException(
                    "AuthMailProperties.ANONYMOUS_EVENT_RETENTION is "
                    + AuthMailProperties.ANONYMOUS_EVENT_RETENTION + ", which does not outlast "
                    + "AnonymousMailConcentration.WINDOW (" + AnonymousMailConcentration.WINDOW
                    + "). The concentration gauge counts rows this sweep has already deleted, so it "
                    + "would report a number truncated by the retention rather than the real "
                    + "maximum — and a truncated maximum reads as a quiet instance. Widen the "
                    + "retention or narrow the gauge's window.");
        }

        // The claim that anonymous rows are reached FIRST. deleteCreatedBefore has no sender
        // predicate, so the effective anonymous lifetime is min(this constant, the operator's
        // app.invites.event-retention-days) — and the operator may legally set that to its own
        // minimum. The two happen to be equal today, which is a coincidence of two numbers derived
        // for unrelated reasons: raise this constant to three and an operator sitting at two gets
        // two, while every sentence about it still says otherwise.
        var lowestGeneralRetention = Duration.ofDays(InviteProperties.MIN_EVENT_RETENTION_DAYS);
        if (AuthMailProperties.ANONYMOUS_EVENT_RETENTION.compareTo(lowestGeneralRetention) > 0) {
            throw new IllegalStateException(
                    "AuthMailProperties.ANONYMOUS_EVENT_RETENTION is "
                    + AuthMailProperties.ANONYMOUS_EVENT_RETENTION + ", which is longer than the "
                    + "lowest app.invites.event-retention-days an operator may set ("
                    + InviteProperties.MIN_EVENT_RETENTION_DAYS + " days). The general sweep has no "
                    + "sender predicate, so at that setting the anonymous rows would be deleted by "
                    + "it and not by their own cutoff — every statement that anonymous rows are "
                    + "swept first, here and in docs/self-hosting.md and .env.prod.example, would "
                    + "be false with nothing to say so. Lower this constant, or raise "
                    + "InviteProperties.MIN_EVENT_RETENTION_DAYS with it.");
        }
    }

    /**
     * Hourly. Nothing depends on the sweep being prompt — the ceilings filter by {@code created_at}
     * themselves, so a row that has outlived its retention is already invisible to them and is only
     * taking up space.
     *
     * <p>Two cutoffs, one pass: the anonymous rows go first and sooner. The order is irrelevant to
     * correctness (the second DELETE simply finds fewer rows) and the two are logged separately so
     * an operator can see which population is actually growing.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void sweep() {
        var now = Instant.now();
        var anonymousCutoff = now.minus(AuthMailProperties.ANONYMOUS_EVENT_RETENTION);
        int anonymous = repository.deleteAnonymousCreatedBefore(anonymousCutoff);
        if (anonymous > 0) {
            log.debug("mail_send_events retention: deleted {} anonymous row(s) older than {}",
                    anonymous, anonymousCutoff);
        }

        var cutoff = now.minus(Duration.ofDays(inviteProperties.eventRetentionDays()));
        int deleted = repository.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.debug("mail_send_events retention: deleted {} row(s) older than {}", deleted, cutoff);
        }
    }
}
