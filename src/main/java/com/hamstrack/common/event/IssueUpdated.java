package com.hamstrack.common.event;

import java.util.List;
import java.util.UUID;

/**
 * An issue was updated. Replaces {@code broadcast(ws,"ISSUE_UPDATED",{projectId,issueNumber})}.
 *
 * <p>{@code changeSet} carries the field-level diff {@code IssueService.update}
 * already computes for history (§4.1 recommendation) — for future consumers only;
 * the SSE listener ignores it and emits the unchanged payload. Never null (empty
 * when nothing tracked changed).
 */
public record IssueUpdated(UUID workspaceId, UUID projectId, long issueNumber,
                           List<FieldChange> changeSet) implements DomainEvent {

    public IssueUpdated {
        changeSet = changeSet == null ? List.of() : List.copyOf(changeSet);
    }
}
