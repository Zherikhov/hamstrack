package com.hamstrack.issue.entity;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One label attached to one issue (HD-30). Surrogate UUID PK + a
 * {@code UNIQUE (issue_id, label_id)} business key — the established shape of every
 * join table in this schema ({@code workflow_statuses}, {@code field_set_items},
 * {@code issue_field_values}) and what lets the entity extend
 * {@link CreatedOnlyEntity}.
 *
 * <p><strong>{@link Issue} deliberately carries no {@code @OneToMany} back to these
 * rows.</strong> A collection mapping would drag every issue's labels into each issue
 * flush and defeat the batched page loading; the service loads them explicitly through
 * {@code IssueLabelRepository}, exactly as it does for {@code IssueFieldValue}.
 *
 * <p><strong>{@link #workspace} is denormalized on purpose</strong> (owner decision,
 * HD-30 fix round 2 — §3.8, binding on every join table of this epic). It is not a
 * convenience column: {@code issue_labels} references BOTH parents through composite
 * foreign keys that include it — {@code (issue_id, workspace_id) → issues
 * (id, workspace_id)} and {@code (label_id, workspace_id) → labels (id, workspace_id)}
 * — so a row pairing one tenant's issue with another tenant's label cannot be written
 * at all. The tenant invariant stops being purely service-enforced (one forgotten
 * {@code …AndWorkspace} away from a leak) and becomes structural. Always populate it
 * from the ISSUE's workspace: a mismatched value now fails loudly at insert, which is
 * exactly the intent.
 */
@Entity
@Table(name = "issue_labels",
        uniqueConstraints = @UniqueConstraint(columnNames = {"issue_id", "label_id"}))
@Getter
@Setter
public class IssueLabel extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "label_id", nullable = false)
    private Label label;

    /**
     * The tenant this row belongs to — always the issue's workspace (which the service
     * has already proven equals the label's). Mapped {@code insertable/updatable} as
     * usual; the composite FKs above make a wrong value an insert-time error.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;
}
