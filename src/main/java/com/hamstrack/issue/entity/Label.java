package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.BaseEntity;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A free-form, colored tag scoped to one {@link Workspace} (HD-30,
 * labels-components-versions-proposal §4). Reusable across every project of the
 * workspace; many labels per issue via {@link IssueLabel}.
 *
 * <p><strong>Tenancy:</strong> {@code workspace} is a plain {@code NOT NULL} owner —
 * NOT the {@code Scoped} catalog shape. There is no global label tier and there must
 * never be one: a global label catalog would surface one Cloud tenant's vocabulary to
 * another. Every repository lookup is {@code …AndWorkspace}, never a bare id (§3.1).
 *
 * <p>Names are normalized server-side (NFC, control chars stripped, whitespace runs
 * collapsed) and unique <em>case-insensitively</em> per workspace via the
 * {@code labels_workspace_name_uk} functional index — display casing is preserved.
 * Issues reference a label by <strong>id</strong>, so rename/recolor are free.
 */
@Entity
@Table(name = "labels")
@Getter
@Setter
public class Label extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 60)
    private String name;

    /** {@code #RRGGBB} or {@code #RRGGBBAA}; validated at the service boundary. */
    @Column(nullable = false, length = 9)
    private String color;

    @Column(length = 200)
    private String description;

    /** Nullable: {@code ON DELETE SET NULL} survives the creator's account deletion. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** Archived labels stay attached to issues (dimmed) but leave every picker. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
