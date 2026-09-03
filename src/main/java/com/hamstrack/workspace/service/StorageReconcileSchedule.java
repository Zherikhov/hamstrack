package com.hamstrack.workspace.service;

import com.hamstrack.common.config.StorageQuotaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers {@link WorkspaceStorageReconciler}'s pass on
 * {@code app.storage.quota.reconcile-cron} — and registers <strong>nothing</strong> when that
 * value is empty (HD-191 §7.3, AC-24).
 *
 * <p><strong>Why this is a {@link SchedulingConfigurer} and not a {@code @Scheduled(cron =
 * "${…}")} annotation.</strong> Spring resolves the placeholder and then asserts that exactly one
 * of {@code cron} / {@code fixedDelay} / {@code fixedRate} produced a schedule; a cron that
 * resolves to the empty string produces none, so the bean post-processor throws and the
 * application does not start. The property's documented contract is that an EMPTY value disables
 * the schedule — an operator writing {@code STORAGE_QUOTA_RECONCILE_CRON=} in their {@code .env}
 * must get a disabled reconciler and not a boot failure, and {@code "-"}
 * ({@code Scheduled.CRON_DISABLED}) is a spelling nobody will guess from a table row that says
 * "empty disables".
 *
 * <p><strong>Disabling it is visible, not silent.</strong> Nothing else changes: the trigger still
 * maintains the counter and the quota is still enforced against it. What stops is the WITNESS —
 * so {@code hamstrack.storage.drift_refreshed_at_age_seconds} rises from process start and
 * {@code StorageDriftGaugeStale} fires, which is the intended consequence of the operator's own
 * setting rather than an accident. That is only true because the freshness stamp is seeded at boot
 * rather than at zero; see {@code ProductMetrics}.
 *
 * <p>An INVALID cron still fails the boot, and should: a typo is not a decision, and the failure
 * arrives at deploy time instead of as a reconciler that never ran.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageReconcileSchedule implements SchedulingConfigurer {

    private final StorageQuotaProperties properties;
    private final WorkspaceStorageReconciler reconciler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        var cron = properties.reconcileCron();
        if (cron == null || cron.isBlank() || "-".equals(cron.trim())) {
            log.info("storage usage reconciler is DISABLED (app.storage.quota.reconcile-cron is "
                     + "empty). The counter is still maintained by its trigger and the quota is "
                     + "still enforced; what stops is the check that the counter is still true — "
                     + "hamstrack.storage.drift_refreshed_at_age_seconds will rise and "
                     + "StorageDriftGaugeStale will fire, which is the intended signal");
            return;
        }
        registrar.addCronTask(reconciler::reconcile, cron.trim());
    }
}
