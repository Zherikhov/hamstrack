package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Reusable binding: which statuses a project uses (board column order via
 * {@link WorkflowStatus}) and which transitions are allowed
 * ({@link WorkflowTransition}; no rows for a source status = open). Projects
 * with a NULL binding use the single {@code isSystemDefault} workflow.
 */
@Entity
@Table(name = "workflows")
@Getter
@Setter
public class Workflow extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_system_default", nullable = false)
    private boolean systemDefault = false;
}
