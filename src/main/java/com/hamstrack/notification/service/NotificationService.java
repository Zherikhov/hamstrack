package com.hamstrack.notification.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.event.NotificationRaised;
import com.hamstrack.notification.dto.NotificationResponse;
import com.hamstrack.notification.entity.Notification;
import com.hamstrack.notification.repository.NotificationRepository;
import com.hamstrack.workspace.entity.Workspace;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The notification inbox. These endpoints name no workspace in their path — the feed spans
 * every workspace the caller is <strong>currently</strong> a member of — so tenancy here is
 * not a path resolution but a predicate, and it lives in
 * {@link NotificationRepository}'s queries rather than in any method below (HD-135 §4.4).
 *
 * <p><strong>Nothing in this class re-queries membership</strong>, and nothing should:
 * resolving it in Java and then passing an id list into an {@code IN} would put the rule in
 * the caller — where the next caller can forget it — and an empty list is a broken
 * {@code IN ()} besides.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(User user) {
        return notificationRepository
                .findVisibleForUser(user, PageRequest.of(0, 30))
                .stream()
                .map(NotificationResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(User user) {
        return notificationRepository.countVisibleUnread(user);
    }

    /**
     * Reads as well as writes: the 200 carries the full DTO, so an unfiltered lookup here
     * would hand a departed member the comment excerpt they can no longer list. Unknown id,
     * someone else's id and a workspace the caller has left are one answer — 404 — on
     * purpose.
     */
    @Transactional
    public NotificationResponse markRead(User user, UUID id) {
        var n = notificationRepository.findVisibleById(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        n.setReadAt(Instant.now());
        return NotificationResponse.of(notificationRepository.save(n));
    }

    @Transactional
    public void markAllRead(User user) {
        notificationRepository.markAllReadForUser(user, Instant.now());
    }

    /**
     * Insert the notification row inside the caller's transaction and publish a
     * {@link NotificationRaised} event. The row commits atomically with the caller
     * (e.g. a mention notification and its comment); the SSE push to the recipient's
     * active connections is performed AFTER commit by the domain-event listener, so it
     * fires once, only if the tx committed — identical net behavior to the old inline
     * {@code sseRegistry.sendToUser(...)} deferral.
     *
     * <p><strong>{@code workspace} is the entity, not an id</strong> (HD-135 §6.4). The
     * method used to take a {@code UUID} lifted from the request path while the row it
     * wrote described an issue resolved somewhere else entirely, so "the two agree" was a
     * property of a resolution two classes away. Passing the workspace the domain object
     * already carries makes it a property of the type instead: a producer cannot write a
     * notification into a workspace the content did not come from, and — with the column
     * {@code NOT NULL} and no overload that omits it — cannot write one into no workspace
     * at all. A producer that has only a string has not resolved its domain object yet.
     */
    @Transactional
    public void create(User recipient, Workspace workspace, String type,
                       String title, String body, String link) {
        var n = new Notification();
        n.setUser(recipient);
        n.setWorkspace(workspace);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        notificationRepository.save(n);

        eventPublisher.publishEvent(
                new NotificationRaised(workspace.getId(), recipient.getId(), NotificationResponse.of(n)));
    }
}
