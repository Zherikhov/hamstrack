package com.hamstrack.issue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Membership of a status in a workflow; position = board column order. */
@Entity
@Table(name = "workflow_statuses",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "status_id"}))
@Getter
@Setter
public class WorkflowStatus {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private short position = 0;
}
