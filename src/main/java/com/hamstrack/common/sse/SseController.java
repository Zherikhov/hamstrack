package com.hamstrack.common.sse;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
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
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    @GetMapping(produces = "text/event-stream")
    public SseEmitter subscribe(@AuthenticationPrincipal User actor,
                                @PathVariable UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return sseRegistry.subscribe(workspaceId, actor.getId());
    }
}
