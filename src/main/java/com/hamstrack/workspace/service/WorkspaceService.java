package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
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
public class WorkspaceService {

    /**
     * The built-in workspace role key the creator is seeded with. A string since HD-126
     * (S3) deleted the ordinal enum; it names a role, it does not rank one.
     */
    private static final String OWNER_KEY = "OWNER";

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
        member.setRole(roleCatalog.reference(RoleScope.WORKSPACE, OWNER_KEY));
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
                workspace, roleCatalog.builtIn(RoleScope.WORKSPACE, OWNER_KEY));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> listForUser(User actor) {
        // One query for the memberships (roles JOIN FETCHed); each role's permission set
        // comes from the cache, so myPermissions costs nothing per row (§9.2).
        // The role goes through the same scope+ownership assertion `get()` applies (H3) —
        // a list that skipped it would report a myPermissions the detail path refuses.
        //
        // DEGRADED rather than refusing (HD-127 §3b): this is a directory of N workspaces
        // and one corrupt role_id must not cost the caller every other entry. `get()` on
        // that same workspace still 404s, which is the intended asymmetry — a list is a
        // directory, a detail read is an authorization.
        return memberRepository.findAllByUserIdWithWorkspace(actor.getId()).stream()
                .map(m -> workspaceAccess.resolveRoleOrDegrade(m.getRole().getId(),
                                RoleScope.WORKSPACE, m.getWorkspace().getId(),
                                RoleScopeViolationSource.WORKSPACE_MEMBERS)
                        .map(role -> WorkspaceResponse.of(m.getWorkspace(), role))
                        .orElseGet(() -> WorkspaceResponse.degraded(m.getWorkspace())))
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
        // Same rule as listForUser two methods up, and for a sharper reason: every row
        // here is somebody ELSE's membership, so each role_id gets the scope+ownership
        // assertion instead of a bare `view`. The legacy bridge used to throw for a
        // non-built-in role, which made this fail closed by accident; `.key()` answers for
        // anything, so a foreign or corrupt role_id would otherwise print another
        // workspace's role name into the People tab once S4 ships custom roles.
        //
        // Degraded, not refused, for that very reason: the whole People tab must not 404
        // because of one other member's row — and the refused role's key is exactly what
        // must NOT appear, so the entry renders with `role: null`.
        return memberRepository.findAllByWorkspaceWithUser(workspace).stream()
                .map(m -> WorkspaceMemberResponse.of(m,
                        workspaceAccess.resolveRoleOrDegrade(m.getRole().getId(),
                                        RoleScope.WORKSPACE, workspace.getId(),
                                        RoleScopeViolationSource.WORKSPACE_MEMBERS)
                                .orElse(null)))
                .toList();
    }

    /**
     * <strong>Permission: {@code workspace.member.manage}, plus the §11.2 grant
     * ceiling</strong> (HD-126 S3, §10.1). The ceiling is deliberately <em>not</em> a
     * permission — it compares the role being handed out against the actor's own grants,
     * which no single catalog entry can express — and it lives in
     * {@code WorkspaceMemberService} so the invite path and the member-administration
     * paths cannot drift apart. Same predicate, one copy.
     */
    @Transactional
    public void inviteMember(User actor, UUID workspaceId, InviteMemberRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        var workspace = ctx.workspace();
        memberService.requireMemberAdmin(ctx.permissions());
        // 422 for a role key this build cannot assign, before anything is judged about it.
        var granted = roleCatalog.requireAssignable(
                RoleScope.WORKSPACE, workspace.getId(), req.roleId(), req.role());
        // OWNER is never grantable via INVITE — not even by an Owner (you promote a
        // colleague to owner, you do not invite a stranger as one). The one rule specific
        // to this call site; the ceiling's Owner guardrail is the weaker, general form.
        if (granted.isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            throw new OwnerIsNotGrantableException();
        }
        memberService.requireWithinGrantCeiling(ctx, granted);
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
        invite.setRole(roleCatalog.reference(granted.id()));
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
                // The one bare view() left in the product was here, and it is the worst
                // place for one: invites are fetched BY EMAIL across every workspace, so
                // this renders a role key to somebody who is not yet a member of the
                // workspace that owns it — a corrupt or foreign role_id would print another
                // tenant's role name onto a stranger's onboarding screen. Degraded rather
                // than refused, like every other collection read: one bad invite must not
                // empty the whole "join a team" list.
                .map(i -> PendingInviteResponse.of(i,
                        workspaceAccess.resolveRoleOrDegrade(i.getRole().getId(),
                                        RoleScope.WORKSPACE, i.getWorkspace().getId(),
                                        RoleScopeViolationSource.WORKSPACE_INVITES)
                                .map(RoleView::key).orElse(null)))
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
        // ---- the invite seam (HD-127 round-3 review) ----
        //
        // This is the one write path that copies a role id from one table to another
        // without ever having judged it: `workspace_invites.role_id` was resolved through
        // requireAssignable when the invite was ISSUED, and then trusted here, possibly
        // weeks later. Fails closed either way — requireMember runs requireRole on every
        // subsequent request, so a wrong-scope or foreign id yields 404 rather than
        // workspace.* grants in a ProjectContext — but "fails closed later" is not the same
        // as "cannot be written", and the row it would write is UNREMOVABLE THROUGH THE API:
        // WorkspaceMemberService.requireWorkspaceRole refuses rather than degrades, so
        // nobody can DELETE the member this let in. Validate before the insert instead, and
        // refuse (this is a single-resource write about the caller, not a collection read).
        // The source tag is WORKSPACE_INVITES so the ERROR names the table the wrong row is
        // actually in.
        var granted = workspaceAccess.requireRole(invite.getRole().getId(), RoleScope.WORKSPACE,
                workspace.getId(), RoleScopeViolationSource.WORKSPACE_INVITES);
        var member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUser(actor);
        // `granted`, not `invite.getRole()`. The same id today — requireRole resolves the one
        // it was handed — but writing the unasserted reference would leave the guard above
        // decorative, one refactor away from a row nobody judged. The rule is the one
        // ProjectService.addMember and WorkspaceMemberService.updateRole already follow:
        // resolve, then write what you resolved.
        member.setRole(roleCatalog.reference(granted.id()));
        memberRepository.save(member);

        invite.setAcceptedAt(Instant.now());
        inviteRepository.save(invite);
        metrics.inviteAccepted();

        // Joining a team completes first-login onboarding (Cloud; no-op otherwise)
        userRepository.markOnboarded(actor.getId(), Instant.now());

        // The view asserted above, not a fresh bare read: this response carries
        // myPermissions, and deriving it from an unjudged role id would echo a permission
        // set the caller's very next request refuses.
        return WorkspaceResponse.of(workspace, granted);
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
