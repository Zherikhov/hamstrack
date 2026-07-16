package com.hamstrack.issue.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Global catalog entry: a custom field definition. Projects show fields
 * through a {@link FieldSet}. {@code key} is the immutable machine name.
 * {@code config} holds type-specific settings: {@code options[{id,label,color}]}
 * for selects, {@code min}/{@code max} for numbers.
 */
@Entity
@Table(name = "field_defs")
@Getter
@Setter
public class FieldDef extends CreatedOnlyEntity {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(nullable = false, length = 50, updatable = false)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FieldType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode config;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;
}
