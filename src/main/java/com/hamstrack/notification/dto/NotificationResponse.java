package com.hamstrack.notification.dto;

import com.hamstrack.notification.entity.Notification;

import java.time.Instant;
import java.util.UUID;

/**
 * <p>{@code workspaceId} is here so a client never has to recover the tenant by parsing
 * {@code link}: that string's shape is a rendering detail built by whichever service
 * raised the notification, and a producer whose link is shaped differently — or absent —
 * would silently yield "no workspace" to a parsing client while the row itself is
 * correctly tenanted.
 */
public record NotificationResponse(
        UUID id,
        UUID workspaceId,
        String type,
        String title,
        String body,
        String link,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse of(Notification n) {
        return new NotificationResponse(
                n.getId(),
                // Reads the id off the LAZY proxy — no SELECT, so no JOIN FETCH is wanted.
                // Hibernate short-circuits the identifier getter inside the proxy (it resolves
                // a getter METHOD even though the entity is mapped by field access), so this
                // does not initialise. Measured, not assumed: NotificationProxyQueryCountTest
                // pins the feed at one statement from a cold persistence context, and turns red
                // for any accessor here that is not the id.
                n.getWorkspace().getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getLink(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
