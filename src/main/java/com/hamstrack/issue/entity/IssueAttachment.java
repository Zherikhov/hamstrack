package com.hamstrack.issue.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "issue_attachments")
@Getter
@Setter
public class IssueAttachment extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    /**
     * The tenant, denormalised onto the row (HD-191, V26) — set once from the resolved
     * context at creation and never re-derived.
     *
     * <p><strong>A plain {@code UUID}, deliberately not a {@code @ManyToOne} to
     * {@code Workspace}.</strong> Nothing navigates from an attachment to its workspace, and a
     * lazy association here would be an N+1 waiting for the first list endpoint that renders
     * one. What reads this column is the storage-usage trigger (which must know the tenant
     * without walking two parents from inside an {@code ON DELETE CASCADE}), the reconciler,
     * and the per-project breakdown.
     *
     * <p>{@code updatable = false} because the value is a fact about the issue this row hangs
     * off, and the database agrees: {@code issue_attachments_issue_ws_fk} is a composite FK on
     * {@code (issue_id, workspace_id)} against {@code issues (id, workspace_id)}, so a row
     * whose workspace disagreed with its issue's could not be written at all.
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "storage_key", nullable = false, columnDefinition = "TEXT")
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;
}
