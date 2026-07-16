package com.hamstrack.issue.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One filled custom field on one issue. Absent row = empty field. The JSONB
 * shape is type-dependent — see {@link FieldType}; validated by
 * {@code FieldValueService} before every write.
 */
@Entity
@Table(name = "issue_field_values",
        uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "field_id"}))
@Getter
@Setter
public class IssueFieldValue extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "field_id", nullable = false)
    private FieldDef field;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode value;
}
