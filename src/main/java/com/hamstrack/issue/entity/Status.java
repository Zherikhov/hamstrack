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
public class Status extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color = "#6B7280";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusCategory category;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
