package com.hamstrack.project.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.IssueTypeSet;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Workflow;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "projects",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "key"}))
@Getter
@Setter
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 10)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "issue_seq", nullable = false)
    private long issueSeq = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // Taxonomy bindings; NULL = the system-default workflow / priority set
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private Workflow workflow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "priority_set_id")
    private PrioritySet prioritySet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_set_id")
    private FieldSet fieldSet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_type_set_id")
    private IssueTypeSet issueTypeSet;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
