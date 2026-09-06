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
 * through a {@link FieldSet}.
 * {@code config} holds type-specific settings: {@code options[{id,label,color}]}
 * for selects, {@code min}/{@code max} for numbers.
 *
 * <h2>{@code key} is the machine name, and it is fixed once created — with exactly one
 * exception (ADR-0036, HD-275)</h2>
 * <strong>A field's identity is this row's UUID, never the text of its key.</strong>
 * {@code issue_field_values}, {@code field_set_items} and every other reference point at
 * {@code id}, so nothing in the product moves when a key changes; the only thing that depends
 * on the key's text is the HQL of saved filters, which is stored verbatim and resolved at read
 * time and is never rewritten by anybody. That dependency is what makes the key fixed in the
 * general case.
 *
 * <p>The exception is the population where that dependency provably cannot bite:
 * {@code AdminFieldService.updateField} permits a rename <strong>exactly while a built-in
 * {@code FieldRegistry} search name has claimed the key</strong> ({@code ShadowedFields}). In
 * that state the tenant's field is unreachable from HQL both before and after — the registry
 * still answers the old name — so no stored query can change meaning. Outside it the rename is
 * refused 422, and after a rename out of the shadow the key is fixed again, because the general
 * argument is true again. System fields are refused outright (409): {@code DemoDataService}
 * resolves the global {@code severity}/{@code environment} defs <em>by key</em>, and that is the
 * one runtime by-key lookup this design rests on staying rare.
 *
 * <p>{@code V1__init_schema.sql} still calls this column an "immutable machine name" inline. That
 * comment is now wrong and stays wrong: an applied migration is never edited. This javadoc is the
 * accurate statement of the rule.
 */
@Entity
@Table(name = "field_defs")
@Getter
@Setter
public class FieldDef extends CreatedOnlyEntity implements Scoped {

    @Column(name = "scope_workspace_id")
    private UUID scopeWorkspaceId;

    @Column(name = "scope_project_id")
    private UUID scopeProjectId;

    /** See the class javadoc: fixed once created, except while a built-in search name shadows it. */
    @Column(nullable = false, length = 50)
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

    /**
     * System field: shipped by default (seeded), shown in the catalog, and
     * cannot be deleted — only archived. Set only by migrations, never the API.
     */
    @Column(name = "is_system", nullable = false)
    private boolean system;
}
