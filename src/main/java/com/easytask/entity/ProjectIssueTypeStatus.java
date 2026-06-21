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
@Table(name = "project_issue_type_status", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_issue_type_id", "status_id"}),
        @UniqueConstraint(columnNames = {"project_issue_type_id", "position"})
})
public class ProjectIssueTypeStatus extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_issue_type_id", nullable = false)
    private ProjectIssueType projectIssueType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private int position;
}
