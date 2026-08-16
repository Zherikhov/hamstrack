package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A curated module of one {@link Project} (HD-31,
 * labels-components-versions-proposal §5) — "Billing", "iOS app", "Ingest
 * pipeline" — with an optional {@link #lead} and an optional {@link #autoAssign}
 * switch. Single-valued per issue ({@code issues.component_id}).
 *
 * <p><strong>Tenancy:</strong> {@code project} is the owner and {@code workspace}
 * is the denormalized tenant scope (as {@code Issue} already carries), so a
 * tenant-scoped query never joins through {@code projects} and the composite FK
 * {@code issues (component_id, workspace_id) → components (id, workspace_id)} can
 * exist at all — a cross-tenant assignment is unrepresentable, not merely rejected
 * in Java (§3.8). Every repository lookup is {@code …AndProject}, never a bare id
 * (§3.1).
 *
 * <p>Names are normalized server-side (NFC, control chars stripped, whitespace runs
 * collapsed) and unique <em>case-insensitively</em> per project via the
 * {@code components_project_name_uk} functional index — display casing is
 * preserved, and the same name in a sibling project is allowed.
 *
 * <p>Components are <em>content</em>, not bound taxonomy: nothing here enters
 * {@code ProjectConfigResponse} (§3.2).
 */
@Entity
@Table(name = "components")
@Getter
@Setter
public class Component extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Nullable: {@code ON DELETE SET NULL} survives the lead's account deletion. A
     * lead who merely leaves the workspace keeps the row — the response still shows
     * them and auto-assign silently skips (§5.4).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id")
    private User lead;

    /**
     * Assign a NEWLY created issue to {@link #lead} when the request carries no
     * explicit assignee (§5.1). Never fires on update — changing a component later
     * must not silently reassign someone's work. Requires a lead: {@code true} with
     * no lead is a 422 at the service boundary.
     */
    @Column(name = "auto_assign", nullable = false)
    private boolean autoAssign;

    /** Archived components stay on the issues carrying them (dimmed) but leave every picker. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
