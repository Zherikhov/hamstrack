package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Global catalog entry. A status reaches a board only through a
 * {@link Workflow} assigned to the project. {@code scopeWorkspaceId} NULL =
 * global row managed by the system admin (workspace scoping reserved).
 */
@Entity
@Table(name = "statuses")
@Getter
@Setter
public class Status extends CreatedOnlyEntity implements Scoped {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(name = "scope_project_id")
    private UUID scopeProjectId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * {@code #RRGGBB} — an IDENTITY hue, not ink (HD-176 / ADR-0027): the readable
     * foreground is derived from it at render time, so a low-contrast value here is a
     * legitimate choice and never a defect.
     *
     * <p><strong>THIS INITIALISER, NOT THE COLUMN DEFAULT, IS THE DEFAULT THE APPLICATION
     * USES</strong> — Hibernate always sends a non-null property on INSERT, so
     * {@code V27}'s {@code ALTER COLUMN … SET DEFAULT} governs raw-SQL writers only. The
     * two are kept equal by hand; {@code ddl-auto=validate} compares neither defaults nor
     * widths, so a drift between them boots perfectly clean. Same reason {@code length = 7}
     * must stay equal to the column's {@code VARCHAR(7)}.
     */
    @Column(nullable = false, length = 7)
    private String color = "#667085";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusCategory category;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
