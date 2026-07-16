package com.hamstrack.issue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Membership of an issue type in a set; position = display order in forms. */
@Entity
@Table(name = "issue_type_set_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"set_id", "type_id"}))
@Getter
@Setter
public class IssueTypeSetItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false)
    private IssueTypeSet set;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private IssueType type;

    @Column(nullable = false)
    private short position = 0;
}
