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

/**
 * The current user's in-app notifications (mentions today, whatever a later producer
 * raises tomorrow): listing, unread counter and read receipts. The path names no
 * workspace — the feed spans every workspace the caller belongs to — so tenancy here is a
 * predicate rather than a path resolution, and it lives in {@code NotificationRepository}'s
 * queries: a row is returned, counted and markable only while its recipient is a member of
 * the workspace it came from (HD-135). A row in a workspace the caller has left is
 * invisible, and on {@code /{id}/read} that is a <strong>404</strong> — the same answer as
 * an unknown id and as another user's id, and never a 403.
 *
 * <p>Real-time delivery happens over the workspace SSE stream, which is separately
 * membership-gated at subscribe time and closed on removal; these endpoints back the
 * notification bell.
 */
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
