package com.hamstrack.common.event;

import java.util.UUID;

/**
 * Marker for typed, immutable state-change events published via
 * {@link org.springframework.context.ApplicationEventPublisher} from the service
 * layer (HD-80). Side effects — SSE broadcast + notification push — are performed
 * by {@code @TransactionalEventListener(AFTER_COMMIT)} listeners in this package,
 * not inline in the services, so a new consumer (Phase-5 agent triggers, webhooks,
 * a multi-node backplane) is added by registering another listener rather than by
 * editing every emit site.
 *
 * <p>Events carry only the ids/payload the current SSE {@code Map} carried and NO
 * managed entity references: an AFTER_COMMIT listener runs after the tx has
 * committed, and with {@code open-in-view=false} navigating a lazy association on a
 * detached entity there would fail — and later (HD-81) events must be serializable
 * across a backplane. Every event carries the {@code workspaceId}, which is the SSE
 * routing key and has already been proven accessible by the publishing service.
 */
public sealed interface DomainEvent permits
        IssueCreated, IssueUpdated, IssueDeleted,
        CommentAdded, CommentUpdated, CommentDeleted,
        AttachmentAdded, AttachmentDeleted,
        NotificationRaised, WorkspaceMemberRemoved {

    UUID workspaceId();
}
