package com.easytask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "project_issue_type", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "issue_type_id"}),
        @UniqueConstraint(columnNames = {"project_id", "position"}),
        @UniqueConstraint(columnNames = {"id", "project_id"})
})
public class ProjectIssueType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_type_id", nullable = false)
    private IssueType issueType;

    @Column(nullable = false)
    private int position;
}
