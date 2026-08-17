package com.hamstrack.project.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "key"}))
@Getter
@Setter
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 10)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * Kanban vs Scrum (HD-22 §3.5) — a presentation switch, never a permission: the
     * sprint API behaves identically either way. Written through
     * {@code PATCH …/projects/{projectId}} by a project curator, read on
     * {@code ProjectResponse} (already fetched by NavRail and both settings areas, so
     * it costs no new request on the hot path). VARCHAR(10) + Java enum, never a PG
     * ENUM type.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "board_mode", nullable = false, length = 10)
    private BoardMode boardMode = BoardMode.KANBAN;

    /**
     * Delivery capability: <strong>releases</strong> (HD-102, delivery-paths-proposal
     * §2.3). Governs the Releases page, the rail item and the fix/affects pickers —
     * <em>on screen only</em>.
     *
     * <p><strong>Rule A (§5.1): a capability gates the UI, never the API.</strong>
     * Nothing in {@code VersionService}/{@code IssueService} reads this flag, so a
     * project with releases off still creates versions and accepts
     * {@code fixVersionIds} with the same status codes as one with it on. Switching it
     * is provably non-destructive: no version row and no issue↔version link is ever
     * touched by a capability change (§13).
     *
     * <p>Primitive {@code boolean} is correct <em>here</em>; the request DTO
     * ({@code DeliveryRequest}) must use boxed {@code Boolean} so a partial PATCH that
     * omits it does not 400 under Jackson 3's {@code FAIL_ON_NULL_FOR_PRIMITIVES}.
     * New projects default to {@code false} (§7 / open question 2 — lean); every
     * project that existed before V12 was backfilled to {@code true}, because an
     * upgrade must never take away a capability a team already had.
     */
    @Column(name = "releases_enabled", nullable = false)
    private boolean releasesEnabled = false;

    /**
     * Delivery capability: <strong>estimation</strong> (story points) — same contract
     * as {@link #releasesEnabled}: it hides the points input and the point sums, and
     * nothing else. {@code issues.story_points} is never read, written or cleared on
     * the strength of this flag, and an issue that carries a value still renders it
     * (Rule B, §5.2).
     */
    @Column(name = "estimation_enabled", nullable = false)
    private boolean estimationEnabled = false;

    // Project-scoped issue counter. Maintained ONLY by the atomic native
    // UPDATE ... RETURNING in ProjectRepository.incrementAndGetIssueSeq —
    // updatable=false keeps JPA from ever writing it, so a stale managed
    // Project (whose in-memory value lags the native increments) can't clobber
    // the DB counter on flush and desync issue numbers (bit demo seeding).
    @Column(name = "issue_seq", nullable = false, updatable = false)
    private long issueSeq = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // Taxonomy bindings; NULL = the system-default workflow / priority set
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "priority_set_id")
    private PrioritySet prioritySet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_set_id")
    private FieldSet fieldSet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_type_set_id")
    private IssueTypeSet issueTypeSet;

    /**
     * The project role a workspace member inherits here when they have no explicit
     * {@code project_members} row and the workspace is {@code OPEN} (HD-123 §5.2).
     * {@code null} → the workspace's default → the built-in Contributor.
     *
     * <p>Setting it to the built-in <strong>Viewer</strong> is how a single project is
     * made strict inside an open workspace — and is also the exact seam private projects
     * will use (§8.5), which is why no new column is needed for them.
     *
     * <p>Raw id for the same reason as on {@code Workspace}: the resolver needs the id
     * and only the id, and a lazy {@code @ManyToOne} would cost one SELECT per project on
     * {@code ProjectService.list}. {@link #defaultProjectRole} is a read-only navigation
     * view; write through {@link #setDefaultProjectRoleId}.
     */
    @Column(name = "default_project_role_id")
    private UUID defaultProjectRoleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_project_role_id", insertable = false, updatable = false)
    private Role defaultProjectRole;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
