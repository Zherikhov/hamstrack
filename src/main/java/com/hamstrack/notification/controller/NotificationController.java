package com.hamstrack.notification.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.notification.dto.NotificationResponse;
import com.hamstrack.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal User actor) {
        return notificationService.list(actor);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal User actor) {
        return Map.of("count", notificationService.countUnread(actor));
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal User actor,
                                         @PathVariable UUID id) {
        return notificationService.markRead(actor, id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal User actor) {
        notificationService.markAllRead(actor);
    }
}
