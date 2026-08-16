package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A time-box of one {@link Project} (HD-22, agile-sprints-proposal §4.3) — "Sprint 7"
 * — with a one-way lifecycle (FUTURE → ACTIVE → COMPLETED, no re-open) and an
 * optional goal and date range. Issues carry their own {@code sprint_id}; nothing is
 * propagated from parent to child.
 *
 * <p><strong>Tenancy:</strong> {@code project} is the owner and {@code workspace} is
 * the denormalized tenant scope (as {@code Issue}, {@code Component} and
 * {@code Version} already carry), so a tenant-scoped query never joins through
 * {@code projects} — and so the composite FK
 * {@code issues (sprint_id, workspace_id) → sprints (id, workspace_id)} can exist at
 * all: a cross-tenant sprint assignment is <em>unrepresentable</em>, not merely
 * rejected in Java (§3.1). The composite FK proves <em>same workspace</em>, not
 * <em>same project</em> — "same project" stays service-enforced through
 * {@code findByIdAndProject}, which every write path must use.
 *
 * <p><strong>Lifecycle invariants</strong> ({@code sprints_dates_ck},
 * {@code sprints_active_ck} and {@code sprints_completed_ck} enforce them in the DB,
 * because both transitions are conditional bulk UPDATEs that bypass any
 * entity-level check):
 * <ul>
 *   <li>{@code endAt > startAt} whenever both are set;</li>
 *   <li>ACTIVE ⇒ {@code startAt} non-null;</li>
 *   <li>COMPLETED ⇔ {@code completedAt} non-null.</li>
 * </ul>
 * At most one ACTIVE sprint per project is enforced by the partial unique index
 * {@code sprints_one_active_per_project_uk}.
 *
 * <p>Sprints are <em>content</em>, not bound taxonomy: nothing here enters
 * {@code ProjectConfigResponse} (§3.6), so a sprint start never invalidates the
 * config every board render depends on.
 */
@Entity
@Table(name = "sprints")
@Getter
@Setter
public class Sprint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 500)
    private String goal;

    /**
     * <strong>{@code updatable = false} is load-bearing</strong> (the documented
     * {@code projects.issue_seq} / {@code versions.released} pattern): the state is
     * written ONLY by the conditional bulk UPDATEs
     * {@code SprintRepository.markActive}/{@code markCompleted}, which are the
     * concurrency arbiters of the lifecycle. Left writable, an ordinary
     * {@code saveAndFlush} on a {@code Sprint} loaded earlier in the request (a PATCH
     * of the goal, a re-plan of the dates) would emit a FULL-column UPDATE from its
     * pre-race snapshot and silently un-complete a sprint a concurrent curator had
     * just closed. INSERT is unaffected, so {@code create()}'s
     * {@code setState(FUTURE)} still persists normally.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SprintState state = SprintState.FUTURE;

    /**
     * 1-based ordinal within the project — drives the default name ("Sprint 7") and
     * the list order. Arbitrated by {@code sprints_project_id_sequence_key}; numbers
     * are not reused after a delete, which is harmless (it is an order key, not an
     * identity).
     */
    @Column(nullable = false)
    private int sequence;

    /**
     * Planned or actual start. Entity-writable on purpose: a PATCH may legitimately
     * re-plan a FUTURE sprint, and {@code start} writes it inside its conditional
     * UPDATE and then clears + re-reads, so no stale copy survives to clobber it.
     */
    @Column(name = "start_at")
    private OffsetDateTime startAt;

    /** Planned or actual end. Entity-writable for the same reason as {@link #startAt}. */
    @Column(name = "end_at")
    private OffsetDateTime endAt;

    /**
     * Set exactly when {@link #state} is COMPLETED — see {@code sprints_completed_ck}.
     * {@code updatable = false} for the same reason as {@link #state} above.
     */
    @Column(name = "completed_at", updatable = false)
    private OffsetDateTime completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** A sprint that can still receive issues (i.e. not COMPLETED). */
    public boolean isOpen() {
        return state != SprintState.COMPLETED;
    }
}
