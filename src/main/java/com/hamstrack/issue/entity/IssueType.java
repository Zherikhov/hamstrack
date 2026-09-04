package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Global catalog entry, offered to all projects (per-project type sets are a
 * planned M3 feature). {@code scopeWorkspaceId} NULL = global row managed by
 * the system admin (workspace scoping reserved).
 */
@Entity
@Table(name = "issue_types")
@Getter
@Setter
public class IssueType extends CreatedOnlyEntity implements Scoped {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(name = "scope_project_id")
    private UUID scopeProjectId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * {@code #RRGGBB} — an IDENTITY hue, not ink (HD-176 / ADR-0027); see
     * {@code Status.color} for why this initialiser and not the column default is the
     * default the application actually uses, and why both are kept equal by hand.
     */
    @Column(nullable = false, length = 7)
    private String color = "#667085";

    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    private short position = 0;

    // Hierarchy level: higher = higher in the tree (may parent strictly-lower
    // levels). Epic=2, Story/Task/Bug=1, Sub-task=0. See issue-hierarchy-proposal §4.1.
    @Column(name = "hierarchy_level", nullable = false)
    private short hierarchyLevel = 1;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
