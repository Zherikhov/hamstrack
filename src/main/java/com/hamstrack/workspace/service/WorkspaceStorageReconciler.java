package com.hamstrack.workspace.service;

import com.hamstrack.common.config.StorageQuotaProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * <strong>The witness that {@code workspace_storage_usage} still equals the rows it claims to
 * count</strong> (HD-191 §7.3) — and the source of the two gauges that say when it does not.
 *
 * <h2>Why a counter needs a witness at all</h2>
 * The trigger is the mechanism; drift is expected to be exactly zero. That is precisely why this
 * exists: a number nobody ever checks is a number that is right until it silently is not, and the
 * quota is enforcing it against every upload in the tenant. Drift can only arrive through a door
 * outside the trigger — a raw {@code COPY}, a restore, a hand-run {@code DELETE} on a replica, a
 * future bulk path — and every one of those is exactly the situation in which nobody is watching.
 *
 * <h2>Per pass</h2>
 * <ol>
 *   <li><strong>One {@code pg_try_advisory_lock} on a fixed install-wide key.</strong> A replica
 *       that does not get it skips, logs at DEBUG — <em>and stamps the freshness anyway</em>,
 *       because a replica that correctly deferred is not stale and the alert on that gauge takes
 *       {@code max()} across replicas. Unlike {@code MailSendEventRetention}'s
 *       sweep — idempotent, bounded by an absolute cutoff, and therefore safe to run N times —
 *       this job <em>computes from what it reads and writes the result</em>, which is the shape
 *       that class's javadoc explicitly says may not inherit its silence. Two passes would not
 *       corrupt anything (they take the same row lock and would compute the same answer), but
 *       they would double the read cost of the whole instance's attachment table for nothing, and
 *       they would race on the gauges.</li>
 *   <li><strong>ONE unlocked grouped query naming the workspaces that disagree</strong>
 *       ({@code workspacesWhereTheCounterDisagreesWithTheRows}), which in the expected case
 *       returns nothing at all. The obvious alternative — walk every workspace, lock it,
 *       aggregate it — is {@code O(workspaces)} round trips at four statements each and takes
 *       minutes on an instance with six figures of tenants, all of it to discover that the
 *       trigger works. A witness should be cheap enough that nobody is ever tempted to switch it
 *       off.</li>
 *   <li>Only for the workspaces that query names — at most {@value #MAX_WORKSPACES_PER_PASS} of
 *       them, a cap that is free while drift is zero and that is WARNed about when it is hit, so
 *       a partial pass is never mistaken for a clean one — and <strong>each in its own short
 *       transaction</strong> (one long transaction would hold N row locks while walking the
 *       instance, so an upload into the first workspace would queue behind the reconciliation of
 *       the last): {@code lockTimeout.applyToCurrentTransaction()} → {@code SELECT … FOR UPDATE}
 *       on the counter row → the exact aggregate over that one workspace's rows → write only if
 *       they still disagree. <strong>Bound, then lock</strong> — the standing rule, and the reason
 *       a nightly job cannot become an unbounded queue behind a busy tenant. The recount under the
 *       lock is not redundant with the unlocked query above: that one is a candidate list which a
 *       concurrent upload may already have invalidated, and this is the correction, which may only
 *       ever observe committed state.</li>
 *   <li>Publish the largest absolute delta of the pass and the fullest workspace, then stamp the
 *       freshness.</li>
 * </ol>
 *
 * <h2>The freshness stamp is not optional and its seed is not zero</h2>
 * Both gauges are last-write-wins, so a reconciler that stops running leaves them FROZEN at a
 * calm value — and a frozen number reads exactly like a healthy instance. {@code ProductMetrics}
 * documents the trap at length on the mail concentration gauge, including the sharper half: a
 * sentinel <em>below</em> the alert threshold makes "never ran, not once" the state nobody hears
 * about. So {@code hamstrack.storage.drift_refreshed_at_age_seconds} counts from process start and
 * {@code StorageDriftGaugeStale} alerts on the age. That is also what makes an EMPTY
 * {@code app.storage.quota.reconcile-cron} an honest operator choice rather than a silent one: the
 * age rises, the rule fires, and the operator sees the consequence of their own setting.
 *
 * <h2>It runs whether or not the quota is enforced</h2>
 * {@code app.storage.quota.enabled=false} switches off the refusal, not the bookkeeping — the
 * reasoning {@link StorageQuotaProperties} gives. An instance that turns the quota on must not
 * resume from a number nobody maintained while it was off.
 *
 * <p>Scheduling is deliberately NOT a {@code @Scheduled} annotation on this class; see
 * {@code StorageReconcileSchedule} for why an empty cron has to disable the task rather than fail
 * the boot.
 */
@Slf4j
@Component
public class WorkspaceStorageReconciler {

    /**
     * The advisory-lock key. Arbitrary, fixed, and install-wide — {@code pg_try_advisory_lock}
     * takes a plain {@code bigint} and PostgreSQL keeps one namespace for the whole database, so
     * the only requirement is that no other job in this product picks the same number. It is the
     * first advisory lock in the tree; the next one goes on the list below.
     *
     * <ul><li>{@code 8_191_001} — this reconciler (HD-191).</li></ul>
     */
    private static final long ADVISORY_LOCK_KEY = 8_191_001L;

    /**
     * <strong>The most workspaces one pass will correct.</strong> Not a tuning dial and
     * deliberately not a property: drift is expected to be exactly zero, so in every healthy
     * instance this bound is never approached and costs nothing.
     *
     * <p>It exists because an unbounded pass is unbounded in three resources at once — it holds
     * one connection idle-in-transaction (pinning {@code xmin}, so vacuum stalls instance-wide),
     * it occupies one of the few threads the scheduler pool has and every other
     * {@code @Scheduled} job shares ({@code spring.task.scheduling.pool.size} — a small pool,
     * not a thread per job, at whatever size it is set to), and each
     * workspace it names can wait up to {@code lock_timeout} for its row. An instance whose
     * counters were bulk-invalidated (a restore, a {@code COPY}) would otherwise turn one nightly
     * witness into a night-long stall of the limiters this feature exists to add.
     *
     * <p>500 is one pass of real work at four statements each and still finishes in seconds; a
     * truncated pass is logged and the remainder is corrected by the next one, so the cap delays
     * a correction rather than losing it.
     */
    private static final int MAX_WORKSPACES_PER_PASS = 500;

    private final WorkspaceStorageUsageRepository usageRepository;
    private final IssueAttachmentRepository attachmentRepository;
    private final StorageQuotaProperties quotaProperties;
    private final ProductMetrics metrics;
    private final LockTimeout lockTimeout;
    private final EntityManager entityManager;

    /** Holds the advisory lock for the whole pass; see {@link #reconcile()}. */
    private final TransactionTemplate passTx;

    /**
     * One workspace, on a connection of its own. {@code REQUIRES_NEW} rather than the shared
     * {@code TransactionTemplate} bean: the pass transaction is suspended around each of these, so
     * the per-workspace row lock is taken and released while the advisory lock stays held.
     */
    private final TransactionTemplate perWorkspaceTx;

    public WorkspaceStorageReconciler(WorkspaceStorageUsageRepository usageRepository,
                                      IssueAttachmentRepository attachmentRepository,
                                      StorageQuotaProperties quotaProperties,
                                      ProductMetrics metrics,
                                      LockTimeout lockTimeout,
                                      EntityManager entityManager,
                                      PlatformTransactionManager transactionManager) {
        this.usageRepository = usageRepository;
        this.attachmentRepository = attachmentRepository;
        this.quotaProperties = quotaProperties;
        this.metrics = metrics;
        this.lockTimeout = lockTimeout;
        this.entityManager = entityManager;
        this.passTx = new TransactionTemplate(transactionManager);
        this.perWorkspaceTx = new TransactionTemplate(transactionManager);
        this.perWorkspaceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One pass.
     *
     * <p><strong>Not {@code @Transactional} itself</strong>, and that is what makes the
     * {@code catch} below work rather than merely look like it does — the finding
     * {@code AnonymousMailConcentration} records: Hibernate marks a transaction rollback-only when
     * a statement fails, so catching inside a transactional method only swaps the exception for an
     * {@code UnexpectedRollbackException} thrown by the commit on the way out. The transactions
     * are opened one workspace at a time, one layer down.
     *
     * <p>A failure is a WARN and nothing else: {@code @Scheduled} has nothing to retry with, and
     * what actually makes it visible is the freshness gauge that stops being stamped.
     */
    public void reconcile() {
        try {
            passTx.executeWithoutResult(status -> {
                // pg_try_advisory_xact_lock, not the session-scoped pg_try_advisory_lock: a session
                // lock has to be released on the connection that took it, and every statement below
                // this runs in a REQUIRES_NEW transaction on a DIFFERENT pooled connection — so the
                // session form would have to be paired with an unlock that could silently land
                // somewhere else, or leak the lock until the connection is evicted. The transaction
                // form is released by this transaction's own commit or rollback, including the
                // rollback an exception causes, which is the property that makes a crashed pass on
                // one replica not lock out every future pass on all of them.
                //
                // This holds ONE connection idle-in-transaction for the length of the pass. That is
                // the deliberate price of single-execution, it is a nightly job, and it does no
                // work of its own: no rows are read or written on this connection after the lock.
                if (!Boolean.TRUE.equals(entityManager
                        .createNativeQuery("SELECT pg_try_advisory_xact_lock(?1)")
                        .setParameter(1, ADVISORY_LOCK_KEY)
                        .getSingleResult())) {
                    log.debug("storage usage reconcile skipped — another replica holds the advisory lock");
                    // AND THE FRESHNESS IS STAMPED ANYWAY. A replica that correctly deferred is
                    // not stale: the pass DID run on this instance, one node over, and the two
                    // gauges it publishes are install-wide numbers every replica computes
                    // identically. Returning without stamping made StorageDriftGaugeStale
                    // unfixable on any multi-replica install — one node stamps, N-1 age from
                    // process start, and the rule takes max(), so it picks a never-stamping
                    // replica, fires after 25h and never clears. An always-firing alert gets
                    // silenced, and silencing this one removes the ONLY detector of a genuinely
                    // dead reconciler, which is the entire reason the stamp exists.
                    //
                    // The rule must NOT be changed to min() instead: min() is satisfied by any one
                    // fresh replica, so a single restarted node would suppress the alert for 25h
                    // while nothing reconciled at all.
                    metrics.storageReconcileCompleted();
                    return;
                }
                runPass();
            });
        } catch (RuntimeException e) {
            log.warn("storage usage reconcile pass did not complete; hamstrack.storage.drift_bytes "
                     + "and hamstrack.storage.quota_fill_max are now STALE, not low — they hold "
                     + "their last values, and a frozen number reads exactly like a clean "
                     + "instance. Watch hamstrack.storage.drift_refreshed_at_age_seconds", e);
        }
    }

    private void runPass() {
        long maxDrift = 0;
        // ONE grouped question for the whole instance, and normally an empty answer. Only the
        // workspaces it names pay for a lock and a recount — and never more than
        // MAX_WORKSPACES_PER_PASS of them, see below.
        var drifting = usageRepository.workspacesWhereTheCounterDisagreesWithTheRows(MAX_WORKSPACES_PER_PASS);
        for (var workspaceId : drifting) {
            maxDrift = Math.max(maxDrift, Math.abs(reconcileOne(workspaceId)));
        }
        if (drifting.size() >= MAX_WORKSPACES_PER_PASS) {
            // SAID OUT LOUD, because a truncated pass and a clean pass publish the same gauges: a
            // capped pass corrects the workspaces it reached and leaves the rest reading exactly
            // like an instance with no drift at all. Reaching this line at all means something
            // outside the trigger wrote attachment rows in bulk (a restore, a COPY, a hand-run
            // DELETE) — the cap is not a tuning dial.
            log.warn("storage usage reconcile pass hit its per-pass cap of {} workspaces, so it is "
                     + "PARTIAL: more workspaces are drifting than this pass corrected, and "
                     + "hamstrack.storage.drift_bytes reports only the largest delta among the ones "
                     + "it reached. The remainder is picked up by the next pass; if this repeats, "
                     + "something outside the trigger is writing issue_attachments in bulk",
                    MAX_WORKSPACES_PER_PASS);
        }

        metrics.storageDrift(maxDrift);
        // A zero quota is not reachable through the documented configuration (StorageQuotaConsistency
        // refuses a quota below the per-file limit at startup), but the guard is here rather than in
        // the query because the consequence would be a division by zero that aborts the pass — i.e.
        // a misconfiguration that silently takes the WITNESS out as well as the ceiling.
        long quota = quotaProperties.workspaceBytes().toBytes();
        metrics.storageQuotaFillMax(quota <= 0 ? 0d : usageRepository.maxFillAgainst(quota));
        // Stamped LAST, and only on a pass that reached here — see the class javadoc.
        metrics.storageReconcileCompleted();
        if (maxDrift > 0) {
            log.warn("storage usage counters corrected; largest delta this pass was {} bytes. The "
                     + "quota was enforcing a number that did not match the attachment rows",
                    maxDrift);
        }
    }

    /**
     * One workspace, in its own transaction. Returns the signed delta that was corrected
     * ({@code counter - rows}), or 0 when they already agreed.
     */
    private long reconcileOne(UUID workspaceId) {
        Long delta = perWorkspaceTx.execute(status -> {
            lockTimeout.applyToCurrentTransaction();
            usageRepository.ensureRow(workspaceId);
            Long counter = usageRepository.lockAndReadBytesUsed(workspaceId);
            long counted = counter == null ? 0L : counter;

            var actual = attachmentRepository.totalsForWorkspace(workspaceId);
            long actualBytes = actual.bytesOrZero();
            long actualCount = actual.countOrZero();
            // The candidate query may have named this workspace on a state a concurrent upload has
            // since made true, so the recount decides. Writing anyway would move updated_at — the
            // asOf a client renders — on a row nothing was wrong with.
            if (counted == actualBytes && countedAttachments(workspaceId) == actualCount) {
                return 0L;
            }
            usageRepository.overwriteCounts(workspaceId, actualBytes, actualCount);
            return counted - actualBytes;
        });
        return delta == null ? 0L : delta;
    }

    /**
     * The counter's attachment count, read after {@code lockAndReadBytesUsed} has already locked
     * the row — so this is a second read of a row this transaction holds, not a second chance for
     * it to move.
     */
    private long countedAttachments(UUID workspaceId) {
        return usageRepository.findByWorkspaceId(workspaceId)
                .map(u -> u.getAttachmentCount())
                .orElse(0L);
    }
}
