package com.hamstrack.notification.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.event.NotificationRaised;
import com.hamstrack.notification.dto.NotificationResponse;
import com.hamstrack.notification.entity.Notification;
import com.hamstrack.notification.repository.NotificationRepository;
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

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(User user) {
        return notificationRepository
                .findAllByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 30))
                .stream()
                .map(NotificationResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread(User user) {
        return notificationRepository.countByUserAndReadAtIsNull(user);
    }

    @Transactional
    public NotificationResponse markRead(User user, UUID id) {
        var n = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!n.getUser().getId().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
     */
    @Transactional
    public void create(User recipient, UUID workspaceId, String type, String title, String body, String link) {
        var n = new Notification();
        n.setUser(recipient);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        notificationRepository.save(n);

        eventPublisher.publishEvent(
                new NotificationRaised(workspaceId, recipient.getId(), NotificationResponse.of(n)));
    }
}
