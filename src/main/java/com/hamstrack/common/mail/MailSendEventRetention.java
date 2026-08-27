package com.hamstrack.common.mail;

import com.hamstrack.common.config.InviteProperties;
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
 * <p><strong>The retention window must outlast every ceiling window, and that is a constraint
 * rather than an observation.</strong> A swept row is a row no ceiling can count, so a retention
 * shorter than a ceiling window silently shortens that ceiling to it — no error, no log line.
 * <strong>"Every" is two windows, not one</strong>: the configurable per-(sender, recipient)
 * cooldown, and the fixed 24-hour window of the global per-recipient daily cap
 * ({@code RecipientMailThrottle.DAY}), which no property can lower and which is therefore the floor
 * whatever the cooldown is set to. The default (7 days) has room to spare; one day would be
 * <em>exactly</em> the daily window, which is why the permitted minimum is two, and
 * {@code InviteProperties.isRetentionLongerThanWidestCeilingWindow} asserts the pair against the wider of the
 * two at startup instead of leaving it to independently editable annotations in three files.
 * Everything past that bound is forensic:
 * after {@code MailDailyVolumeHigh} fires, this table is the only place that can answer <em>who</em>
 * and <em>which addresses</em>, and the metrics deliberately cannot (bounded cardinality is
 * non-negotiable, so no ids and no addresses reach Prometheus). A week of low-volume rows costs
 * nothing and is short enough that this is not a durable store of who-emailed-whom.
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
     * Hourly. Nothing depends on the sweep being prompt — the ceilings filter by {@code created_at}
     * themselves, so a row that has outlived its retention is already invisible to them and is only
     * taking up space.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000)
    @Transactional
    public void sweep() {
        var cutoff = Instant.now().minus(Duration.ofDays(inviteProperties.eventRetentionDays()));
        int deleted = repository.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.debug("mail_send_events retention: deleted {} row(s) older than {}", deleted, cutoff);
        }
    }
}
