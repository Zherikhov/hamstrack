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

    @Column(nullable = false)
    private long position = 0;

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
