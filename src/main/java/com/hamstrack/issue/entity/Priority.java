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
public class Priority extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color = "#8B8680";

    // lucide icon name (chevrons-up, chevron-up, equal, chevron-down, minus…)
    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
