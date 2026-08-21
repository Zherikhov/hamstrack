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

    /**
     * The three taxonomy references, backed by SINGLE-COLUMN foreign keys —
     * {@code issues_type_id_fkey} and {@code issues_status_id_fkey} (HD-13,
     * {@code V19__issues_taxonomy_fk.sql}) and {@code issues_priority_id_fkey} (V1).
     *
     * <p><strong>What they buy: no stranded reference.</strong> None of these three columns
     * can name a row that does not exist, in any path — including one written later,
     * including raw SQL, including a cascade. An issue whose status had been deleted would
     * render as a blank board column, vanish from every status filter, and be unrepairable
     * through the UI, because no screen can name a status that is not there.
     *
     * <p><strong>What they do NOT buy: tenancy.</strong> Read this before assuming they are
     * equivalent to {@link #component} and {@link #sprint}. Those two are
     * backed by COMPOSITE keys — {@code (component_id, workspace_id) → components (id,
     * workspace_id)} — which make a cross-tenant reference <em>unrepresentable</em>. These
     * three cannot be that shape, and the reason is structural rather than a shortcut:
     * {@code statuses}/{@code issue_types}/{@code priorities} have no {@code workspace_id}
     * column, a global row's {@code scope_workspace_id} is NULL, and one global catalog row
     * is referenced by issues in every workspace at once — a key naming a workspace can be
     * satisfied by exactly one of them. PostgreSQL has no MATCH PARTIAL and no conditional
     * foreign key. The tenancy half is enforced in the application, by
     * {@code ProjectConfigService.requireStatusOffered} / {@code requireTypeOffered} /
     * {@code requirePriorityOffered} and the scope-checked binding chain behind them. Verified
     * exhaustively rather than assumed: no path reaches an issue taxonomy write without passing
     * through them — native SQL, {@code DemoDataService}, cross-project moves and the bulk
     * endpoints were each checked for a bypass. See {@code V19__issues_taxonomy_fk.sql}.
     *
     * <p><strong>Two honest limits on that claim, because it is now load-bearing.</strong>
     * <ul>
     *   <li><strong>The guard is by set MEMBERSHIP, not by SCOPE.</strong>
     *       {@code requireStatusOffered} compares an id against the project's effective workflow
     *       and asserts nothing about {@code scope_workspace_id}. If a future admin path ever
     *       gets a foreign row into a workflow or a set, the issue boundary accepts it without
     *       complaint. What makes it safe today is one level up: exactly three call sites can
     *       populate a container, and all three resolve their children through
     *       {@code findByIdVisibleTo}. That is the whole guarantee — the boundary inherits its
     *       tenancy from the container, it does not check its own.</li>
     *   <li><strong>Propagation is cache-bound, and ARCHIVING newly joined that list.</strong>
     *       Those catalog reads go through {@code ProjectConfigCache} (~60 s), so a row removed
     *       from a set or a workflow stays writable for up to the TTL. Not a tenancy hole — the
     *       row was legitimately in the set when it was cached. What changed in HD-13 is the
     *       third case: the guards now test {@code archivedAt} against the <em>cached list</em>
     *       rather than against a freshly loaded row, and none of the three cache queries filters
     *       archived — so <strong>archiving a status no longer takes effect on issue writes
     *       immediately</strong>, where it used to. It is the same TTL and the same kind of
     *       staleness, and it is the price of resolving from the set instead of loading first
     *       (which is what removed the cross-tenant name disclosure). Together these mean the
     *       app-layer half is <em>eventually</em> consistent while the foreign key below is
     *       immediate; when the two disagree the FK wins and the caller gets the backstop 409
     *       rather than the tidy 422.</li>
     * </ul>
     *
     * <p><strong>No {@code cascade} attribute, and none may ever be added.</strong>
     * {@code CascadeType.REMOVE} or {@code ALL} here would try to delete the <em>catalog
     * row</em> when an issue is deleted — the exact inversion of what the constraint
     * protects. Since V19 it would also fail loudly on the first shared status rather than
     * quietly stranding everyone else's issues, which is an improvement and not a licence.
     *
     * <p>{@code insertable}/{@code updatable} stay default. The {@code updatable = false}
     * rule that guards {@code projects.issue_seq} applies to columns written by native SQL
     * behind the ORM's back; these are written by JPA, and marking either non-updatable would
     * silently break every status transition.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private IssueType type;

    /** See {@link #type} — single-column FK, buys integrity and not tenancy, never cascade. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** See {@link #type} — single-column FK, buys integrity and not tenancy, never cascade. */
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

    /**
     * When work actually STARTED on this issue (HD-137, reports-proposal §5.1) — the
     * first moment it entered a status whose category is {@code IN_PROGRESS} <em>or</em>
     * {@code DONE}. An issue dragged straight to Done was started and finished in one
     * move; refusing to admit that would drop exactly the fastest work out of every
     * cycle-time percentile.
     *
     * <p><strong>Never cleared, never re-stamped</strong> — and that asymmetry with
     * {@link #closedAt} is deliberate. {@code closedAt} is cleared when an issue leaves
     * a DONE status because "is it closed" is a question about the issue's <em>current
     * state</em>. "When did work start" is not a current-state question, so a re-open
     * must not move it: clearing (or re-stamping) it would make the cycle time of
     * reopened work shrink retroactively, and a number that quietly gets better when
     * work goes worse is how a report loses its readers.
     *
     * <p>Written ONLY by {@code IssueService} (create + the status branch of update),
     * alongside the {@code closedAt} logic and inside the same "all reads first, then
     * mutate" ordering. No {@code updatable = false}: unlike {@code projects.issue_seq}
     * there is no native writer to protect it from.
     *
     * <p>{@code null} means "we do not know when this started" — a fact the cycle-time
     * report prints as {@code missingStartCount} rather than papering over. V18's
     * best-effort backfill leaves it null for anything it cannot resolve from
     * {@code issue_history}, and <strong>nothing may substitute {@code createdAt}</strong>
     * (that is a lead time, not a cycle time — §2.2).
     */
    @Column(name = "started_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime startedAt;
}
