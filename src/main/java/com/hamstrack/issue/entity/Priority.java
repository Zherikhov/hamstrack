package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Global catalog entry (formerly the {@code IssuePriority} enum). Projects
 * offer a subset via their {@link PrioritySet}. {@code scopeWorkspaceId} NULL
 * = global row managed by the system admin; non-NULL is reserved for future
 * workspace-scoped entries.
 */
@Entity
@Table(name = "priorities")
@Getter
@Setter
public class Priority extends CreatedOnlyEntity implements Scoped {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(name = "scope_project_id")
    private UUID scopeProjectId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * {@code #RRGGBB} — an IDENTITY hue, not ink (HD-176 / ADR-0027); see
     * {@code Status.color} for why this initialiser and not the column default is the
     * default the application actually uses, and why both are kept equal by hand. V27 moved
     * it off {@code #8B8680}, a warm grey from the retired visual language.
     */
    @Column(nullable = false, length = 7)
    private String color = "#667085";

    // lucide icon name (chevrons-up, chevron-up, equal, chevron-down, minus…)
    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
