package com.hamstrack.common.event;

import java.util.UUID;

/** An issue was deleted. Replaces {@code broadcast(ws,"ISSUE_DELETED",{projectId,issueNumber})}. */
public record IssueDeleted(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
