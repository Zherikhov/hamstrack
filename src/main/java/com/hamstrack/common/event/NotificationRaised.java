package com.hamstrack.common.event;

import com.hamstrack.notification.dto.NotificationResponse;

import java.util.UUID;

/**
 * A notification row was inserted (in the caller's tx) and must be pushed live to
 * the recipient's active SSE connections. Replaces the inline
 * {@code sseRegistry.sendToUser(ws, recipient, "NOTIFICATION", payload)} that
 * {@code NotificationService.create} used to do — the row still commits with the
 * caller's tx; only the SSE push is deferred to the AFTER_COMMIT listener.
 */
public record NotificationRaised(UUID workspaceId, UUID recipientUserId,
                                 NotificationResponse payload) implements DomainEvent {
}
