package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One append-only entry in a sprint's SCOPE LEDGER (HD-137, reports-proposal §5.2) —
 * "issue X joined / left sprint Y at time T, carrying Z points, moved by A".
 *
 * <p>This table is the reason the burn-up can draw a scope line that is actually true.
 * The alternative — deriving membership history from {@code issue_history} — is
 * name-keyed (history stores the sprint's display NAME, not its id), so one rename makes
 * every past sprint unreadable. The ledger is id-keyed and therefore immune to renames
 * of anything.
 *
 * <p><strong>Append-only.</strong> Nothing in the application ever updates or deletes a
 * row here, which is why it extends {@link CreatedOnlyEntity} (no {@code updated_at}, no
 * {@code @Version}, no update trigger). Rows leave only with their SPRINT (cascade);
 * deleting the ISSUE only nulls {@link #issue} — see there for why the two differ.
 *
 * <p><strong>One writer.</strong> Every row is written by {@code SprintScopeLedger} and
 * by nothing else. Sprint membership changes in seven places today (see that class's
 * javadoc for the enumerated list); a door that mutates {@code issues.sprint_id} without
 * going through the ledger makes the burn-up's scope line silently wrong, which is the
 * exact failure mode that makes competitors' sprint charts distrusted.
 *
 * <p><strong>Tenancy:</strong> {@code workspace} is denormalised for the same reason
 * {@code Issue}, {@code Sprint}, {@code Component} and {@code Version} carry it — a
 * tenant-scoped query never joins through {@code projects} — and, as on every other
 * workspace-denormalised table since V8 §3.8, it is part of BOTH parent foreign keys.
 * The sprint and the issue therefore cannot be assembled from different tenants: that is
 * not checked at runtime, it is unrepresentable. It matters here more than most, because
 * {@code idx_sprint_scope_events_ws} exists so a velocity sweep can filter by workspace
 * ALONE — a row whose {@code workspace_id} disagreed with its sprint would be read into
 * the wrong tenant's report, and a report is numbers, so there would be no symptom.
 *
 * <p>The project is not stored: a sprint belongs to exactly one project, and a report
 * always resolves the sprint first (through {@code findByIdAndProject}, i.e. through
 * membership).
 */
@Entity
@Table(name = "sprint_scope_events")
@Getter
@Setter
public class SprintScopeEvent extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sprint_id", nullable = false)
    private Sprint sprint;

    /**
     * The issue that moved — <strong>{@code null} once that issue has been deleted</strong>.
     *
     * <p>The FK is {@code ON DELETE SET NULL (issue_id)} (V18), not the {@code CASCADE}
     * §5.2 first proposed, and the difference is a freeze this codebase enforces
     * everywhere else: {@code SprintService.requireDetachable} refuses (422) to let even
     * a MANAGER pull a DONE issue out of a COMPLETED sprint, so that "17 of 21 points
     * delivered" cannot quietly become another number — while {@code IssueService.delete}
     * makes no sprint-state check at all. Under {@code CASCADE} the actor explicitly
     * refused the detach got its exact effect for free by deleting the issue.
     *
     * <p>The correction is <em>not</em> to block the delete (that would punish a
     * different, legitimate act with a report); it is to let the LEDGER ROW OUTLIVE THE
     * ISSUE. The scope arithmetic keeps both of its steps, and {@link #issueKey} plus
     * {@link #storyPoints} carry enough identity for a sprint review to still label the
     * line.
     *
     * <p>Reading side, for R4: never dereference this without a null check, and never
     * make it an inner {@code JOIN} — a completed sprint whose issues were later deleted
     * would silently lose rows from its own review.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private Issue issue;

    /**
     * The issue's KEY at the moment of the event ({@code "HD-137"}) — snapshotted, never
     * rewritten, and NOT NULL even when {@link #issue} has become null.
     *
     * <p>This is what makes a deleted issue's row still readable instead of an
     * unresolvable UUID. It is also deliberately a snapshot rather than a join: a project
     * re-key does not retro-edit what a past sprint review said, exactly as
     * {@code issue_history} keeps the names things had when they changed.
     *
     * <p>{@code VARCHAR(40)}, not the 30 the arithmetic needs today
     * ({@code projects.key VARCHAR(10)} + {@code '-'} + a 19-digit {@code BIGINT}): 30 is
     * exact, and an exact bound on an APPEND-ONLY table turns any future widening of
     * {@code projects.key} into a value-too-long 500 on a path with no retry. See V18 for
     * the full sum.
     */
    @Column(name = "issue_key", nullable = false, length = 40)
    private String issueKey;

    /** ADDED or REMOVED — {@code VARCHAR(10)} + {@code sprint_scope_events_event_ck}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 10)
    private SprintScopeEventType event;

    /**
     * The issue's estimate at the moment of the event, so a points burn-up can be
     * reconstructed without re-reading today's value. {@code null} = unestimated, which
     * is deliberately not the same statement as {@code 0} (as on {@link Issue}).
     */
    @Column(name = "story_points", precision = 5, scale = 2)
    private BigDecimal storyPoints;

    /**
     * Who moved it. Nullable, {@code ON DELETE SET NULL}: the event is a fact about the
     * sprint and must outlive the account that caused it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    /**
     * When the event HAPPENED — the burn-up's x-axis.
     *
     * <p>Not the same fact as the inherited {@code createdAt}, which is when the row was
     * written. They are equal for every path except the commitment batch a sprint start
     * writes, which is dated the sprint's {@code startAt}: a curator may legitimately
     * start a sprint with a backdated (or forward-dated) start, and the chart is drawn
     * against that date, not the click. Written explicitly by the ledger — deliberately
     * NOT a second {@code @CreatedDate} field, because Spring Data's auditing handler
     * overwrites created dates unconditionally and would silently re-stamp the batch to
     * "now".
     */
    @Column(name = "occurred_at", nullable = false,
            columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime occurredAt;
}
