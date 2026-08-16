package com.hamstrack.common.event;

import java.util.UUID;

/** A comment was deleted. Replaces {@code broadcast(ws,"COMMENT_DELETED",{projectId,issueNumber})}. */
public record CommentDeleted(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
