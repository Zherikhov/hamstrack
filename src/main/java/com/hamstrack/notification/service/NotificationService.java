package com.hamstrack.notification.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.notification.dto.NotificationResponse;
import com.hamstrack.notification.entity.Notification;
import com.hamstrack.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
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
    private final SseRegistry sseRegistry;

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

    /** Create a notification and push it via SSE to the recipient's active connections. */
    @Transactional
    public void create(User recipient, UUID workspaceId, String type, String title, String body, String link) {
        var n = new Notification();
        n.setUser(recipient);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        notificationRepository.save(n);

        // Push in real-time if the user is connected
        sseRegistry.sendToUser(workspaceId, recipient.getId(), "NOTIFICATION", NotificationResponse.of(n));
    }
}
