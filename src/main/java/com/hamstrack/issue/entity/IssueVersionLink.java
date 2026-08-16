package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One {@link Version} linked to one {@link Issue} in one {@link VersionLinkType}
 * role (HD-32). ONE table serves both roles — one batch loader, one filter path,
 * one cascade story, and a third role later becomes a value rather than a
 * migration (§6.2).
 *
 * <p>Surrogate UUID PK + a {@code UNIQUE (issue_id, version_id, link_type)}
 * business key — the established shape of every join table in this schema
 * ({@code workflow_statuses}, {@code field_set_items}, {@code issue_field_values},
 * {@code issue_labels}) and what lets the entity extend {@link CreatedOnlyEntity}.
 * {@code link_type} is part of that key on purpose: the same version may be both
 * the fix version and an affects version of one issue (§6.4).
 *
 * <p><strong>{@link Issue} deliberately carries no {@code @OneToMany} back to these
 * rows.</strong> A collection mapping would drag every issue's version links into
 * each issue flush and defeat the batched page loading; the service loads them
 * explicitly through {@code IssueVersionLinkRepository}, exactly as it does for
 * {@code IssueLabel} and {@code IssueFieldValue}.
 *
 * <p><strong>{@link #workspace} is denormalized on purpose</strong> (owner decision,
 * §3.8, binding on every join table of this epic). It is not a convenience column:
 * {@code issue_versions} references BOTH parents through composite foreign keys
 * that include it — {@code (issue_id, workspace_id) → issues (id, workspace_id)}
 * and {@code (version_id, workspace_id) → versions (id, workspace_id)} — so a row
 * pairing one tenant's issue with another tenant's version cannot be written at
 * all. Always populate it from the ISSUE's workspace (one private factory,
 * {@code VersionService.newRow}, is the only place that builds a row): a
 * mismatched value now fails loudly at insert, which is exactly the intent.
 */
@Entity
@Table(name = "issue_versions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"issue_id", "version_id", "link_type"}))
@Getter
@Setter
public class IssueVersionLink extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private Version version;

    /**
     * The tenant this row belongs to — always the issue's workspace (which the
     * service has already proven equals the version's). The composite FKs above
     * make a wrong value an insert-time error.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    /**
     * FIX or AFFECTS, stored as {@code VARCHAR(10)} — never a PG ENUM type
     * (Hibernate 7 + PG enums throw JDBC cast errors on INSERT).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 10)
    private VersionLinkType linkType;
}
