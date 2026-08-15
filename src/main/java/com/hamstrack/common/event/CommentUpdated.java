package com.hamstrack.common.event;

import java.util.UUID;

/** A comment was updated. Replaces {@code broadcast(ws,"COMMENT_UPDATED",{projectId,issueNumber})}. */
public record CommentUpdated(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
