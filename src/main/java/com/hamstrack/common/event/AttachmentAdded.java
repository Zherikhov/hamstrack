package com.hamstrack.common.event;

import java.util.UUID;

/**
 * An attachment was uploaded (blob durably stored). Replaces
 * {@code broadcast(ws,"ATTACHMENT_ADDED",{projectId,issueNumber})}.
 *
 * <p>Published from the non-transactional {@code AttachmentService.upload} (blob
 * I/O runs off the tx, HD-76), i.e. with NO active transaction — the AFTER_COMMIT
 * listener therefore relies on {@code fallbackExecution = true} to fire inline for
 * this event (see {@code SseEventListener}).
 */
public record AttachmentAdded(UUID workspaceId, UUID projectId, long issueNumber) implements DomainEvent {
}
