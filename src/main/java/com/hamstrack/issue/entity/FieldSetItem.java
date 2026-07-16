package com.hamstrack.issue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** Membership of a field in a set, with per-set form behavior. */
@Entity
@Table(name = "field_set_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"set_id", "field_id"}))
@Getter
@Setter
public class FieldSetItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false)
    private FieldSet set;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "field_id", nullable = false)
    private FieldDef field;

    @Column(nullable = false)
    private short position = 0;

    // Must be filled on create; can't be cleared afterwards
    @Column(nullable = false)
    private boolean required = false;

    @Column(name = "show_on_create", nullable = false)
    private boolean showOnCreate = true;
}
