package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Reusable binding: which priorities a project offers and which one is the
 * default for new issues (via {@link PrioritySetItem}). Projects with a NULL
 * binding use the single {@code isSystemDefault} set.
 */
@Entity
@Table(name = "priority_sets")
@Getter
@Setter
public class PrioritySet extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_system_default", nullable = false)
    private boolean systemDefault = false;
}
