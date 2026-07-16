package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Reusable binding: which issue types a project offers for new issues and
 * type changes (via {@link IssueTypeSetItem}). Projects with a NULL binding
 * use the single {@code isSystemDefault} set. Existing issues keep their type
 * even when it leaves the set — only creation/type-change is restricted.
 */
@Entity
@Table(name = "issue_type_sets")
@Getter
@Setter
public class IssueTypeSet extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_system_default", nullable = false)
    private boolean systemDefault = false;
}
