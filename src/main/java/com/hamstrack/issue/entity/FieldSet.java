package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Reusable binding: which custom fields a project shows (via
 * {@link FieldSetItem}). Projects with a NULL binding use the
 * {@code isSystemDefault} set ("No fields" — empty).
 */
@Entity
@Table(name = "field_sets")
@Getter
@Setter
public class FieldSet extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_system_default", nullable = false)
    private boolean systemDefault = false;
}
