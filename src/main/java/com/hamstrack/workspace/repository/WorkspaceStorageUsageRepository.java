package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.WorkspaceStorageUsage;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The counter row behind the storage quota (HD-191, ADR-0026).
 *
 * <p><strong>Not a {@code JpaRepository}, on purpose</strong> — the {@code RoleRepository}
 * precedent. {@code workspace_storage_usage.bytes_used} is written by a database trigger and by
 * {@code WorkspaceStorageReconciler}, and by nothing else; inheriting {@code save} /
 * {@code saveAll} / {@code deleteAll} would put an ordinary-looking door on a column whose
 * correctness depends on there being none. A future contributor who wants one has to add the
 * method here and explain it, which is the point.
 *
 * <p>Everything below is workspace-keyed. There is deliberately no unscoped finder: this table
 * has one row per tenant and no other predicate has a legitimate caller, so the shape that
 * causes this project's top bug class cannot be written by accident. The two aggregates that
 * are NOT workspace-keyed ({@link #totalBytesUsed()} and {@link #maxFillAgainst}) exist for the
 * operator metrics and return a single install-wide number carrying no tenant identity —
 * the same division {@code ProductMetrics} applies to the mail gauges.
 */
public interface WorkspaceStorageUsageRepository extends Repository<WorkspaceStorageUsage, UUID> {

    /** The summary read: one primary-key hit. Absent means zero — see {@code WorkspaceStorageService}. */
    Optional<WorkspaceStorageUsage> findByWorkspaceId(UUID workspaceId);

    /**
     * Make sure this workspace has a counter row, without caring whether it already did.
     *
     * <p>Idempotent by construction, so nothing has to be coupled to workspace creation and a
     * workspace that predates V26's seed (or whose row was removed by hand) is not a special
     * case. It is the statement immediately before {@link #lockAndReadBytesUsed} because
     * {@code SELECT … FOR UPDATE} locks rows that exist and silently locks nothing otherwise —
     * which would turn the reservation's mutual exclusion off exactly on a workspace's first
     * concurrent uploads.
     *
     * <p>Plain {@code @Modifying}: {@code clearAutomatically} here would discard whatever the
     * enclosing transaction has queued (the trap that once made workspace creation vanish), and
     * nothing in this transaction re-reads a mutated entity.
     */
    @Modifying
    @Query(value = """
            INSERT INTO workspace_storage_usage (workspace_id)
            VALUES (:workspaceId)
            ON CONFLICT (workspace_id) DO NOTHING
            """, nativeQuery = true)
    void ensureRow(@Param("workspaceId") UUID workspaceId);

    /**
     * Take the per-workspace reservation lock and read the total under it.
     *
     * <p><strong>{@code FOR UPDATE} is correct HERE and is forbidden on {@code workspaces}.</strong>
     * The standing rule is about the parent table: a key-share-blocking lock on a
     * {@code workspaces} row queues every FK child insert in that tenant behind it. Nothing
     * references {@code workspace_storage_usage}, so this lock serialises exactly what it is
     * meant to — concurrent uploads into one workspace — and nothing else in the instance
     * notices. Choosing the row that could be locked is half of why this counter is a table
     * of its own.
     *
     * <p>The caller calls {@code LockTimeout.applyToCurrentTransaction()} first: bound, then
     * lock. A wait past that bound surfaces as the existing 409 + {@code Retry-After} from
     * {@code handlePessimisticLock}, which is the right answer here — unlike a quota refusal, a
     * lost lock race really is fixed by retrying.
     *
     * <p>Returns {@code null} only if the row vanished between {@link #ensureRow} and this
     * statement, which no path in the product does.
     */
    @Query(value = """
            SELECT bytes_used FROM workspace_storage_usage
             WHERE workspace_id = :workspaceId
             FOR UPDATE
            """, nativeQuery = true)
    Long lockAndReadBytesUsed(@Param("workspaceId") UUID workspaceId);

    /**
     * The reconciler's correction — the only application write to these columns anywhere.
     *
     * <p>Runs under the same row lock a live upload takes, so it can only ever observe and
     * replace committed state.
     */
    @Modifying
    @Query(value = """
            UPDATE workspace_storage_usage
               SET bytes_used = :bytesUsed, attachment_count = :attachmentCount, updated_at = NOW()
             WHERE workspace_id = :workspaceId
            """, nativeQuery = true)
    void overwriteCounts(@Param("workspaceId") UUID workspaceId,
                         @Param("bytesUsed") long bytesUsed,
                         @Param("attachmentCount") long attachmentCount);

    /**
     * {@code hamstrack.storage.bytes_used_total} — one row per workspace, so a cheap scrape.
     * Install-wide and tenant-anonymous, per the cardinality rule.
     */
    @Query("SELECT COALESCE(SUM(u.bytesUsed), 0) FROM WorkspaceStorageUsage u")
    long totalBytesUsed();

    /**
     * {@code hamstrack.storage.quota_fill_max} — the fullest workspace on the instance as a
     * 0..1 fraction of {@code quotaBytes}, or {@code 0} when there are no workspaces.
     *
     * <p>Every replica computes the same install-wide number, so an alert takes {@code max()}
     * and never {@code sum()}. The quota is passed in rather than read here because it is a
     * property, not a column — there is no per-workspace quota and this ticket deliberately
     * does not create one.
     */
    @Query("SELECT COALESCE(MAX(u.bytesUsed), 0) * 1.0 / :quotaBytes FROM WorkspaceStorageUsage u")
    double maxFillAgainst(@Param("quotaBytes") long quotaBytes);

    /**
     * <strong>The workspaces whose counter disagrees with their attachment rows — the whole
     * instance, in one statement.</strong>
     *
     * <p>This is the reconciler's first pass and it is why the job is cheap. The obvious
     * implementation — walk every workspace, lock its counter, aggregate its rows — is
     * {@code O(workspaces)} round trips with four statements each, and it takes minutes on an
     * instance with six figures of tenants while doing nothing at all in the expected case. Drift
     * is expected to be <em>zero</em>: the trigger is the mechanism and the reconciler is only the
     * witness. So the witness asks one grouped question, and normally gets an empty answer.
     *
     * <p>Read WITHOUT a lock, deliberately: a concurrent upload can make a row here stale in
     * either direction. That is harmless because this is a candidate list, not a correction — the
     * correction re-reads and recomputes under the row lock, so a workspace that has stopped
     * drifting by then is written nothing, and one that started drifting after this query is
     * caught by the next pass.
     *
     * <p>{@code LEFT JOIN} from {@code workspaces} rather than from the counter table, because a
     * tenant whose counter row is MISSING is itself a drift, and joining the other way would make
     * that the one state the reconciler cannot see.
     *
     * <p><strong>{@code LIMIT} — the bound on one pass, and it is free in the expected case.</strong>
     * Each name this returns costs a {@code REQUIRES_NEW} transaction that may wait up to
     * {@code lock_timeout}, while the pass transaction sits idle-in-transaction holding the
     * advisory lock and pinning PostgreSQL's {@code xmin} horizon (so nothing in the instance can
     * be vacuumed for the length of the pass) and occupying one of the FEW threads every other
     * {@code @Scheduled} job in the product shares ({@code spring.task.scheduling.pool.size} is a
     * small pool and not a thread per job, so a long pass costs the others a scheduling slot at
     * whatever size it is set to). Drift is expected to be zero, so
     * a bound costs nothing normally and turns "one bad night reconciles ten thousand tenants in
     * one uninterruptible pass" into a truncated pass the caller LOGS — see
     * {@code WorkspaceStorageReconciler}, where hitting the cap is a WARN rather than a silent
     * partial correction, and the rest is picked up by the next pass.
     */
    @Query(value = """
            SELECT w.id
              FROM workspaces w
              LEFT JOIN workspace_storage_usage u ON u.workspace_id = w.id
              LEFT JOIN (SELECT workspace_id, SUM(size_bytes) AS bytes, COUNT(*) AS cnt
                           FROM issue_attachments
                          GROUP BY workspace_id) a ON a.workspace_id = w.id
             WHERE COALESCE(u.bytes_used, 0) <> COALESCE(a.bytes, 0)
                OR COALESCE(u.attachment_count, 0) <> COALESCE(a.cnt, 0)
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> workspacesWhereTheCounterDisagreesWithTheRows(@Param("limit") int limit);
}
