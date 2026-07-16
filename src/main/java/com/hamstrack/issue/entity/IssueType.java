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
public class IssueType extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color = "#6B7280";

    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
