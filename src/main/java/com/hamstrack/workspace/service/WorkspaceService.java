package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.util.TokenUtils;
import com.hamstrack.issue.service.IssueTypeService;
import com.hamstrack.issue.service.StatusService;
import com.hamstrack.workspace.dto.*;
import com.hamstrack.workspace.entity.*;
import com.hamstrack.workspace.exception.*;
import com.hamstrack.workspace.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final IssueTypeService issueTypeService;
    private final StatusService statusService;

    @Transactional
    public WorkspaceResponse create(User actor, CreateWorkspaceRequest req) {
        var slug = generateSlug(req.name());
        var workspace = new Workspace();
        workspace.setName(req.name());
        workspace.setSlug(slug);
        workspace.setCreatedBy(actor);
        workspaceRepository.save(workspace);

        var member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(actor);
        member.setRole(WorkspaceRole.OWNER);
        memberRepository.save(member);

        issueTypeService.seedDefaults(workspace);
        statusService.seedDefaults(workspace);

        return WorkspaceResponse.of(workspace, WorkspaceRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listForUser(User actor) {
        return memberRepository.findAllByUserIdWithWorkspace(actor.getId()).stream()
                .map(m -> WorkspaceResponse.of(m.getWorkspace(), m.getRole()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var member = requireMembership(actor, workspace);
        return WorkspaceResponse.of(workspace, member.getRole());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> listMembers(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        requireMembership(actor, workspace);
        return memberRepository.findAllByWorkspace(workspace).stream()
                .map(WorkspaceMemberResponse::of)
                .toList();
    }

    @Transactional
    public void inviteMember(User actor, UUID workspaceId, InviteMemberRequest req) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var actorMember = requireMembership(actor, workspace);
        if (!actorMember.getRole().isAtLeast(WorkspaceRole.ADMIN)) {
            throw new InsufficientWorkspaceRoleException();
        }
        // OWNER is never grantable via invite, and no one can grant a role above their own
        if (req.role() == WorkspaceRole.OWNER || !actorMember.getRole().isAtLeast(req.role())) {
            throw new InsufficientWorkspaceRoleException();
        }
        // Check not already a member
        userRepository.findByEmail(req.email().toLowerCase()).ifPresent(user -> {
            if (memberRepository.existsByWorkspaceAndUser(workspace, user)) {
                throw new AlreadyWorkspaceMemberException();
            }
        });

        var rawToken = TokenUtils.generateRawToken();
        var invite = new WorkspaceInvite();
        invite.setWorkspace(workspace);
        invite.setEmail(req.email().toLowerCase());
        invite.setRole(req.role());
        invite.setTokenHash(TokenUtils.sha256(rawToken));
        invite.setInvitedBy(actor);
        invite.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600)); // 7 days
        inviteRepository.save(invite);

        mailService.sendWorkspaceInviteEmail(req.email(), workspace.getName(), rawToken);
    }

    @Transactional
    public WorkspaceResponse acceptInvite(User actor, String rawToken) {
        var hash = TokenUtils.sha256(rawToken);
        var invite = inviteRepository.findByTokenHash(hash)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (invite.isExpired() || invite.isAccepted()) {
            throw new WorkspaceNotFoundException();
        }
        // The invite is bound to the invited address — a leaked/forwarded link must not
        // let a different account join with the invite's role
        if (!invite.getEmail().equalsIgnoreCase(actor.getEmail())) {
            throw new WorkspaceNotFoundException();
        }
        var workspace = invite.getWorkspace();
        if (memberRepository.existsByWorkspaceAndUser(workspace, actor)) {
            throw new AlreadyWorkspaceMemberException();
        }
        var member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(actor);
        member.setRole(invite.getRole());
        memberRepository.save(member);

        invite.setAcceptedAt(Instant.now());
        inviteRepository.save(invite);

        return WorkspaceResponse.of(workspace, invite.getRole());
    }

    // Returns membership or throws 404 — never reveals workspace existence to non-members
    private WorkspaceMember requireMembership(User actor, Workspace workspace) {
        return memberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
    }

    private String generateSlug(String name) {
        var base = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) base = "workspace";
        var slug = base;
        // Random suffix instead of a counter: two concurrent creates of the same name
        // would both compute "name-1" with a counter; random suffixes diverge
        while (workspaceRepository.existsBySlug(slug)) {
            slug = base + "-" + randomSuffix();
        }
        return slug;
    }

    private String randomSuffix() {
        var chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        var sb = new StringBuilder(6);
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
