package com.hamstrack.common.event;

import java.util.UUID;

/** A comment was added. Replaces {@code broadcast(ws,"COMMENT_ADDED",{projectId,issueNumber})}. */
public record CommentAdded(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
