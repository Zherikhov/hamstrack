package com.hamstrack.issue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Membership of a priority in a set; at most one item per set is the default. */
@Entity
@Table(name = "priority_set_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"set_id", "priority_id"}))
@Getter
@Setter
public class PrioritySetItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false)
    private PrioritySet set;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "priority_id", nullable = false)
    private Priority priority;

    @Column(nullable = false)
    private short position = 0;

    @Column(name = "is_default", nullable = false)
    private boolean defaultForNewIssues = false;
}
