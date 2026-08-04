package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.dto.PendingInviteResponse;
import com.hamstrack.workspace.dto.WorkspaceResponse;
import com.hamstrack.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Invites addressed to the current user — the onboarding "join a team" surface.
 * Lists pending invites for the caller's email and accepts/declines them by id
 * (no emailed token needed). Acceptance is still bound to the invited address,
 * so a leaked invite id can't be redeemed by a different account (404).
 */
@RestController
@RequestMapping("/api/invites")
@RequiredArgsConstructor
public class InviteController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public List<PendingInviteResponse> list(@AuthenticationPrincipal User user) {
        return workspaceService.listPendingInvites(user);
    }

    @PostMapping("/{id}/accept")
    public WorkspaceResponse accept(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return workspaceService.acceptInvite(user, id);
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        workspaceService.declineInvite(user, id);
    }
}
