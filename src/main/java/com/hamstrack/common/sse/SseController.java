package com.hamstrack.common.sse;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Server-Sent Events stream with live workspace events (ISSUE_CREATED,
 * ISSUE_UPDATED, ISSUE_DELETED, NOTIFICATION), used by the SPA to refresh
 * boards and the notification bell without polling. Membership is checked on
 * subscribe; emitter lifecycle (timeouts, disconnects) is handled by
 * {@link SseRegistry}.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseRegistry sseRegistry;
    private final WorkspaceAccessService workspaceAccess;

    @GetMapping(produces = "text/event-stream")
    public SseEmitter subscribe(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId) {
        workspaceAccess.requireMember(actor, workspaceId);
        return sseRegistry.subscribe(workspaceId, actor.getId());
    }
}
