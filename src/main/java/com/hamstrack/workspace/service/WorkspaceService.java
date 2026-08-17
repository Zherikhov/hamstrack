package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.WorkspaceSource;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.util.TokenUtils;
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
// HD-123 S1: the WorkspaceRole ordinal ladder is a documented legacy bridge here — the
// built-in role keys ARE the enum names, so behaviour is byte-identical. S3 replaces the
// two invite gates with workspace.member.manage + the grant-ceiling rule (§11.2) and
// deletes the enum.
@SuppressWarnings("deprecation")
public class WorkspaceService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final ProductMetrics metrics;
    /**
     * Memberships and invites now carry a {@code roles} row. Resolving a built-in role
     * costs no query: its id is a compile-time constant and the reference is a proxy
     * whose only use is to have its foreign key written.
     */
    private final RoleCatalog roleCatalog;
    /**
     * HD-132: the owner of the membership-administration rules. Injected here only for its
     * two guards ({@code requireMemberAdmin} / {@code requireWithinGrantCeiling}) so the
     * invite path shares one implementation of the grant ceiling with PATCH/DELETE
     * {@code /members/{userId}} instead of keeping the copy that used to live inline.
     */
    private final WorkspaceMemberService memberService;

    // User-initiated creation (the API path) — completes first-login onboarding.
    @Transactional
    public WorkspaceResponse create(User actor, CreateWorkspaceRequest req) {
        return create(actor, req, true);
    }

    /**
     * @param completesOnboarding whether this creation counts as the user
     *   choosing to make their own team. False for auto-provisioned workspaces
     *   (demo seeding) — those must NOT complete onboarding, or the welcome
     *   screen would never appear for a Cloud user who also got a demo workspace.
     */
    @Transactional
    public WorkspaceResponse create(User actor, CreateWorkspaceRequest req, boolean completesOnboarding) {
        var slug = generateSlug(req.name());
        var workspace = new Workspace();
        workspace.setName(req.name());
        workspace.setSlug(slug);
        workspace.setCreatedBy(actor);
        workspaceRepository.save(workspace);

        var member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(actor);
        member.setRole(roleCatalog.reference(WorkspaceRole.OWNER));
        memberRepository.save(member);

        // No per-workspace taxonomy seeding since M1: statuses/types/priorities
        // live in the global catalog and reach projects through bindings

        // Metric source classification: the only signal on this method is the
        // completesOnboarding flag. The demo seeder is the single caller that
        // passes false, so false => demo and true => a real user-initiated
        // creation. There is no distinct "onboarding" workspace-creation call
        // site today (OnboardingController completes onboarding + demo-seeds but
        // creates no workspace of its own), so WorkspaceSource.ONBOARDING is
        // reserved for future use and not emitted here.
        metrics.workspaceCreated(completesOnboarding ? WorkspaceSource.USER : WorkspaceSource.DEMO);

        if (completesOnboarding) {
            // Creating a team completes first-login onboarding (Cloud; no-op otherwise)
            userRepository.markOnboarded(actor.getId(), Instant.now());
        }

        return WorkspaceResponse.of(
                workspace, roleCatalog.builtIn(RoleScope.WORKSPACE, WorkspaceRole.OWNER.name()));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listForUser(User actor) {
        // One query for the memberships (roles JOIN FETCHed); each role's permission set
        // comes from the cache, so myPermissions costs nothing per row (§9.2).
        // The role goes through the same scope+ownership assertion `get()` applies (H3) —
        // a list that skipped it would report a myPermissions the detail path refuses.
        return memberRepository.findAllByUserIdWithWorkspace(actor.getId()).stream()
                .map(m -> WorkspaceResponse.of(m.getWorkspace(),
                        workspaceAccess.requireRole(m.getRole().getId(), RoleScope.WORKSPACE,
                                m.getWorkspace().getId(), "workspace_members")))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get(User actor, UUID workspaceId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        return WorkspaceResponse.of(ctx.workspace(), ctx.workspaceRole());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> listMembers(User actor, UUID workspaceId) {
        var workspace = workspaceAccess.requireMember(actor, workspaceId).workspace();
        return memberRepository.findAllByWorkspaceWithUser(workspace).stream()
                .map(m -> WorkspaceMemberResponse.of(m, roleCatalog.view(m.getRole().getId()).asWorkspaceRole()))
                .toList();
    }

    @Transactional
    public void inviteMember(User actor, UUID workspaceId, InviteMemberRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        var workspace = ctx.workspace();
        var actorMember = ctx.membership();
        // HD-123 S1: the actor's role comes from the cached view of their roles row. The
        // built-in keys ARE the enum names, so both gates below behave exactly as before.
        var actorRole = roleCatalog.view(actorMember.getRole().getId()).asWorkspaceRole();
        // HD-132: both gates now live in WorkspaceMemberService so the invite path and the
        // member-administration paths cannot drift apart — same predicate, one copy.
        memberService.requireMemberAdmin(actorRole);
        // OWNER is never grantable via INVITE (you promote a colleague to owner, you do not
        // invite a stranger as one) — the one rule that is specific to this call site.
        if (req.role() == WorkspaceRole.OWNER) {
            throw new InsufficientWorkspaceRoleException();
        }
        memberService.requireWithinGrantCeiling(actorRole, req.role());
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
        invite.setRole(roleCatalog.reference(req.role()));
        invite.setTokenHash(TokenUtils.sha256(rawToken));
        invite.setInvitedBy(actor);
        invite.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600)); // 7 days
        inviteRepository.save(invite);
        metrics.inviteSent();

        mailService.sendWorkspaceInviteEmail(req.email(), workspace.getName(), rawToken);
    }

    // Accept via the emailed token link.
    @Transactional
    public WorkspaceResponse acceptInvite(User actor, String rawToken) {
        var hash = TokenUtils.sha256(rawToken);
        var invite = inviteRepository.findByTokenHash(hash)
                .orElseThrow(WorkspaceNotFoundException::new);
        return acceptInvite(actor, invite);
    }

    // Pending invites addressed to the caller's email — the onboarding
    // "join a team" screen accepts these without needing the token link.
    @Transactional(readOnly = true)
    public List<PendingInviteResponse> listPendingInvites(User actor) {
        return inviteRepository
                .findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc(actor.getEmail())
                .stream()
                .filter(i -> !i.isExpired())
                // Already a member (e.g. accepted a different invite to the same ws) — hide it
                .filter(i -> !memberRepository.existsByWorkspaceAndUser(i.getWorkspace(), actor))
                .map(i -> PendingInviteResponse.of(i, roleCatalog.view(i.getRole().getId()).key()))
                .toList();
    }

    // Accept a specific invite by id (from the onboarding screen).
    @Transactional
    public WorkspaceResponse acceptInvite(User actor, UUID inviteId) {
        var invite = inviteRepository.findById(inviteId)
                .orElseThrow(WorkspaceNotFoundException::new);
        return acceptInvite(actor, invite);
    }

    // Decline an invite addressed to the caller. Removes it (single-use,
    // email-bound); an admin can always re-invite.
    @Transactional
    public void declineInvite(User actor, UUID inviteId) {
        var invite = inviteRepository.findById(inviteId)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (!invite.getEmail().equalsIgnoreCase(actor.getEmail())) {
            throw new WorkspaceNotFoundException();
        }
        inviteRepository.delete(invite);
        metrics.inviteDeclined();
    }

    private WorkspaceResponse acceptInvite(User actor, WorkspaceInvite invite) {
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
        metrics.inviteAccepted();

        // Joining a team completes first-login onboarding (Cloud; no-op otherwise)
        userRepository.markOnboarded(actor.getId(), Instant.now());

        return WorkspaceResponse.of(workspace, roleCatalog.view(invite.getRole().getId()));
    }

    // Marks first-login onboarding complete (the "Create a team" choice; joining
    // completes it via acceptInvite). Idempotent.
    @Transactional
    public void completeOnboarding(User actor) {
        userRepository.markOnboarded(actor.getId(), Instant.now());
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
