package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.dto.*;
import com.hamstrack.workspace.service.WorkspaceMemberService;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace management: create/list/get workspaces, list members, invite by
 * email and accept invites, and — since HD-132 — administer an existing
 * membership (change a role, remove a member). The workspace is the tenant
 * boundary — every nested resource is resolved through the caller's membership,
 * and a non-member gets 404 (never 403) so workspace existence is not revealed.
 * Creating a workspace makes the caller OWNER; taxonomy is the global catalog
 * (since M1), so workspace creation no longer seeds any issue types or statuses.
 *
 * <p><strong>Member administration</strong> ({@code PATCH}/{@code DELETE
 * /{id}/members/{userId}}) requires {@code workspace.member.manage} — the same
 * permission the invite path checks (HD-126, S3). Two further rules apply to both verbs:
 * the <em>grant ceiling</em> (§11.2 — nobody may hand out, or act on a member holding, a
 * role that grants something they do not hold themselves, plus the built-in Owner
 * guardrail) and the <em>last-Owner</em> guard (409 — a workspace must never lose its last
 * owner, including when an owner acts on themselves).
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    /** HD-132: administering an EXISTING membership (role change / removal). */
    private final WorkspaceMemberService workspaceMemberService;

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

    /**
     * Change an existing member's workspace role (HD-132).
     *
     * <p>200 · <strong>403</strong> caller is a member but below ADMIN, or the edit breaks
     * the grant ceiling (nobody may act on — or hand out — a role stronger than their own)
     * · <strong>404</strong> unknown workspace, caller not a member, or the target holds no
     * membership <em>here</em> (which says nothing about whether the account exists) ·
     * <strong>409</strong> it would demote the workspace's last owner.
     */
    @PatchMapping("/{id}/members/{userId}")
    public WorkspaceMemberResponse updateMember(@AuthenticationPrincipal User user,
                                                @PathVariable UUID id,
                                                @PathVariable UUID userId,
                                                @Valid @RequestBody UpdateWorkspaceMemberRequest req) {
        return workspaceMemberService.updateRole(user, id, userId, req);
    }

    /**
     * Remove a member from the workspace (HD-132) — their {@code workspace_members} row and
     * their {@code project_members} rows in this workspace, plus the one reference that must
     * not outlive access: the issue <em>assignee</em>, which is a statement about who is
     * responsible now. The account itself is global and is untouched, as is every piece of
     * historical attribution, their saved filters, and any component they lead (HD-31 §5.4
     * keeps a departed lead and skips them at auto-assign time).
     *
     * <p>Removal also revokes the ways back in: every unaccepted invite for that address in
     * this workspace is deleted (a leftover one would otherwise re-appear as a live join
     * button the moment the membership row is gone), and their open SSE streams are closed
     * once the transaction commits.
     *
     * <p>204 · 403/404/409 exactly as {@code PATCH} above, plus <strong>422</strong> when the
     * target is the caller: self-removal ("leave workspace") is a different feature with its
     * own UX and is not built yet, so this endpoint refuses rather than quietly doing it. A
     * sole owner deleting themselves gets the 409 instead — "promote another owner first" is
     * the answer that will still be true once leaving exists. A second DELETE for an
     * already-removed member is a clean 404, not a 500.
     */
    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal User user,
                             @PathVariable UUID id,
                             @PathVariable UUID userId) {
        workspaceMemberService.remove(user, id, userId);
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
