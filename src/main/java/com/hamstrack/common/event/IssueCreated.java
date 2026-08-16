package com.hamstrack.common.event;

import java.util.UUID;

/** An issue was created. Replaces {@code broadcast(ws,"ISSUE_CREATED",{projectId,issueNumber})}. */
public record IssueCreated(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
