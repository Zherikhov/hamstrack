package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.dto.*;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal User user,
                                    @Valid @RequestBody CreateWorkspaceRequest req) {
        return workspaceService.create(user, req);
    }

    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal User user) {
        return workspaceService.listForUser(user);
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return workspaceService.get(user, id);
    }

    @GetMapping("/{id}/members")
    public List<WorkspaceMemberResponse> members(@AuthenticationPrincipal User user,
                                                 @PathVariable UUID id) {
        return workspaceService.listMembers(user, id);
    }

    @PostMapping("/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> invite(@AuthenticationPrincipal User user,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody InviteMemberRequest req) {
        workspaceService.inviteMember(user, id, req);
        return Map.of("message", "Invite sent to " + req.email());
    }

    @PostMapping("/accept-invite")
    public WorkspaceResponse acceptInvite(@AuthenticationPrincipal User user,
                                          @RequestParam String token) {
        return workspaceService.acceptInvite(user, token);
    }
}
