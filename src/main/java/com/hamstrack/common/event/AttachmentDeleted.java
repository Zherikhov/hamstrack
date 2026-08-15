package com.hamstrack.common.event;

import java.util.UUID;

/** An attachment was deleted. Replaces {@code broadcast(ws,"ATTACHMENT_DELETED",{projectId,issueNumber})}. */
public record AttachmentDeleted(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
