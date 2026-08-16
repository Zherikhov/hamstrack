package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueFieldValue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        long number,
        String key,
        String title,
        String description,
        IssueTypeResponse type,
        StatusResponse status,
        PriorityResponse priority,
        AssigneeInfo assignee,
        AssigneeInfo reporter,
        UUID parentId,
        String parentKey,
        String parentTitle,
        UUID parentTypeId,
        int childCount,
        int doneChildCount,
        LocalDate dueDate,
        // HD-30 — the issue's workspace labels, ordered by lower(name); [] when none.
        // Batch-loaded per page (never per issue) by LabelService.labelsByIssue.
        List<LabelRef> labels,
        // HD-31 — the issue's project component, or null. A ToOne: it rides the
        // existing LEFT JOIN FETCH blocks, so it needs no batch loader.
        ComponentRef component,
        // HD-32 — the versions this issue ships in / is broken in, each ordered by
        // lower(name); [] when none. Batch-loaded per page (never per issue, and BOTH
        // roles in one query) by VersionService.versionsByIssue.
        List<VersionRef> fixVersions,
        List<VersionRef> affectsVersions,
        List<FieldValueResponse> fields,
        int version,
        Instant createdAt,
        Instant updatedAt,
        OffsetDateTime closedAt
) {
    public record AssigneeInfo(UUID id, String displayName, String avatarUrl) {
    }

    /**
     * A parent's display summary — the parent key, title and type id for chips/breadcrumbs.
     */
    public record ParentRef(UUID id, String key, String title, UUID typeId) {
    }

    /**
     * Direct-children roll-up: total and DONE-category counts.
     */
    public record Rollup(int childCount, int doneChildCount) {
        public static final Rollup EMPTY = new Rollup(0, 0);
    }

    /**
     * Full factory. {@code parent} may be null (no parent); {@code rollup} may be
     * null (treated as {@link Rollup#EMPTY}); {@code versions} may be null (treated
     * as {@link IssueVersionRefs#EMPTY}). List/board callers pre-compute all of them
     * via batched queries; a single-issue GET can navigate the proxy and count.
     */
    public static IssueResponse of(Issue i, Collection<IssueFieldValue> fieldValues,
                                   List<LabelRef> labels, IssueVersionRefs versions,
                                   ParentRef parent, Rollup rollup) {
        var projectKey = i.getProject().getKey() + "-" + i.getNumber();
        var r = rollup != null ? rollup : Rollup.EMPTY;
        var v = versions != null ? versions : IssueVersionRefs.EMPTY;
        return new IssueResponse(
                i.getId(), i.getNumber(), projectKey,
                i.getTitle(), i.getDescription(),
                IssueTypeResponse.of(i.getType()),
                StatusResponse.of(i.getStatus()),
                PriorityResponse.of(i.getPriority()),
                i.getAssignee() == null ? null :
                        new AssigneeInfo(i.getAssignee().getId(), i.getAssignee().getDisplayName(), i.getAssignee().getAvatarUrl()),
                new AssigneeInfo(i.getReporter().getId(), i.getReporter().getDisplayName(), i.getReporter().getAvatarUrl()),
                parent == null ? null : parent.id(),
                parent == null ? null : parent.key(),
                parent == null ? null : parent.title(),
                parent == null ? null : parent.typeId(),
                r.childCount(), r.doneChildCount(),
                i.getDueDate(),
                labels == null ? List.of() : labels,
                ComponentRef.of(i.getComponent()),
                v.fixVersions(), v.affectsVersions(),
                FieldValueResponse.of(fieldValues),
                i.getVersion(),
                i.getCreatedAt(), i.getUpdatedAt(),
                i.getClosedAt());
    }
}
