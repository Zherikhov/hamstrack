package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "issues")
@Getter
@Setter
public class Issue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private long number;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private IssueType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "priority_id", nullable = false)
    private Priority priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Issue parent;

    /**
     * The project module this issue belongs to (HD-31), or null. Backed by the
     * COMPOSITE FK {@code (component_id, workspace_id) → components (id, workspace_id)}
     * with {@code ON DELETE SET NULL (component_id)}, so a cross-tenant assignment is
     * unrepresentable and deleting a component nulls it here (§3.8, §5.2). Mapped as a
     * plain ToOne — it joins into the board/backlog/search fetch blocks and needs no
     * batch loader.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    private Component component;

    /**
     * The sprint this issue is committed to (HD-22), or null = the backlog. Backed by
     * the COMPOSITE FK {@code (sprint_id, workspace_id) → sprints (id, workspace_id)}
     * with {@code ON DELETE SET NULL (sprint_id)}, so a cross-tenant assignment is
     * unrepresentable and deleting a sprint nulls it here (§3.1, §4.2). Mapped as a
     * plain ToOne — it joins into the board/backlog/search fetch blocks and needs no
     * batch loader.
     *
     * <p><strong>Trap:</strong> the FK clears this column <em>behind JPA's back</em>,
     * so {@code SprintService.delete} must run an explicit bulk
     * {@code set i.sprint = null} BEFORE deleting the row — otherwise a managed,
     * now-stale {@code Issue} flushed later writes the old id back (the
     * {@code issue_seq}-clobber class of bug).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id")
    private Sprint sprint;

    /**
     * The project-wide backlog/board rank (agile-sprints-proposal §3.3) — written
     * ONLY by {@code IssueRankService} and by {@code IssueService.create}; never by
     * the client (it is not even exposed in {@code IssueResponse}). Spaced by
     * {@code RANK_STEP = 2^26}, so a drag is a midpoint between two neighbours and a
     * whole-project rebalance is rare.
     */
    @Column(nullable = false)
    private long position = 0;

    /**
     * Native story-point estimate (HD-22 §3.4), promoted from the V1-seeded
     * {@code story_points} custom field (archived by V11, values migrated here).
     * {@code null} = unestimated, which is deliberately NOT the same statement as
     * {@code 0} ("it's free") — the section stats report {@code unestimatedCount}
     * separately. Range 0…999 with at most 2 decimals, enforced in the service (422)
     * and by {@code issues_story_points_ck}.
     */
    @Column(name = "story_points", precision = 5, scale = 2)
    private java.math.BigDecimal storyPoints;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Optimistic lock counter (409 on a stale {@code version} in a PATCH).
     *
     * <p>The annotation is <strong>fully qualified on purpose</strong>: HD-32 added a
     * {@link Version} <em>entity</em> (a project release target) to this very package,
     * and a same-package type always shadows an on-demand import — so a bare
     * {@code @Version} here resolves to that entity and fails to compile with
     * "incompatible types: Version cannot be converted to Annotation". Any future
     * entity in {@code com.hamstrack.issue.entity} that needs optimistic locking must
     * qualify it the same way (or import {@code jakarta.persistence.Version}
     * explicitly, which would then shadow the entity instead).
     */
    @jakarta.persistence.Version
    @Column(nullable = false)
    private int version = 0;

    @Column(name = "closed_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime closedAt;
}
