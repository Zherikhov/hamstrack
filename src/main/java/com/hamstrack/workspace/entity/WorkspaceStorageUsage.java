package com.hamstrack.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How many attachment bytes one workspace occupies — the state behind the storage quota
 * (HD-191, ADR-0026).
 *
 * <p><strong>Every column but the key is {@code insertable = false, updatable = false}, and
 * that is the structural answer to the {@code projects.issue_seq} scar</strong> rather than a
 * style choice. That counter was maintained by a native {@code UPDATE … RETURNING}, which does
 * not refresh the managed copy, so a later {@code save()} of a stale {@code Project} wrote the
 * old value back and two issues got the same number. The rule CLAUDE.md drew from it — "mark a
 * DB-maintained counter {@code updatable = false}" — is a thing somebody has to remember on
 * every future field. Here JPA cannot write these columns at all, so there is no managed copy
 * to go stale and no annotation for the next field to be missing.
 *
 * <p>Maintained by two writers and neither of them is Hibernate: the row trigger
 * {@code trg_issue_attachments_storage_usage} (V26), which follows every INSERT/DELETE/UPDATE
 * of {@code issue_attachments.size_bytes} — including the ones performed by
 * {@code ON DELETE CASCADE}, which is the whole reason the counter is a trigger and not a
 * service-level decrement — and {@code WorkspaceStorageReconciler}, whose native UPDATE is the
 * witness that the trigger's arithmetic is still true.
 *
 * <p><strong>It is a counter row, not a business row</strong>: no {@code BaseEntity}, no
 * {@code @Version}, no auditing. {@code updatedAt} is stamped by whoever wrote the row in SQL,
 * so {@code @LastModifiedDate} would be a second, disagreeing author.
 *
 * <p>Its repository deliberately does not extend {@code JpaRepository}
 * ({@code WorkspaceStorageUsageRepository}) — a bare {@code save} must not compile, for the
 * same reason {@code RoleRepository} refuses {@code findById}.
 */
@Entity
@Table(name = "workspace_storage_usage")
@Getter
public class WorkspaceStorageUsage {

    /** Also the FK to {@code workspaces}, {@code ON DELETE CASCADE} — a purged tenant takes its counter with it. */
    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /**
     * <strong>Can this number be wrong? Yes — one way of being wrong is deliberate and one-sided,
     * the rest arrive in either direction, and something is watching for all of them.</strong>
     * The trigger clamps every decrement with {@code GREATEST(0, …)} (V26,
     * ADR-0026), so a counter that has already drifted low cannot go negative and, more to the
     * point, a {@code DELETE} is never REFUSED because the arithmetic disagrees with it: a
     * {@code CHECK (bytes_used >= 0)} would fire on the statement trying to REDUCE the number,
     * turning a benign overstatement into an inability to delete the very attachments that would
     * fix it. The cost of the clamp is that drift is silent at the row, so it is made loud
     * elsewhere: {@code WorkspaceStorageReconciler} recounts against the rows,
     * {@code hamstrack.storage.drift_bytes} reports the largest correction and
     * {@code StorageUsageCounterDrift} alerts on it.
     *
     * <p>The clamp itself only ever moves the number <em>up</em> relative to the raw arithmetic
     * (it declines to go below zero), so what it can produce is an overstatement. Drift in either
     * direction arrives only through a writer the trigger does not see — a restore, a
     * {@code COPY}, a hand-run {@code DELETE} — and both directions are corrected by the next
     * reconcile pass. The one thing a wrong value here cannot do is block a deletion.
     */
    @Column(name = "bytes_used", nullable = false, insertable = false, updatable = false)
    private long bytesUsed;

    @Column(name = "attachment_count", nullable = false, insertable = false, updatable = false)
    private long attachmentCount;

    /** When the counter last moved — reported to clients as {@code asOf}. */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
