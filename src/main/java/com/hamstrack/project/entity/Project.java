package com.hamstrack.project.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

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

    public boolean isArchived() {
        return archivedAt != null;
    }
}
