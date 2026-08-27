package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.WorkspaceProperties;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.observability.ProductMetrics.WorkspaceSource;
import com.hamstrack.common.ratelimit.InviteThrottle;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.tx.AfterCommit;
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
import java.util.Locale;
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
    /** HD-130 §10: {@code app.workspace.default-project-access-mode}, for NEW workspaces only. */
    private final WorkspaceProperties workspaceProperties;
    /**
     * HD-190: the invitation ceilings — per-sender volume (in memory) and the persisted
     * recipient-keyed cooldown + daily cap, spent in that order. Injected here rather than bound to
     * a path because the recipient key makes a refusal a tenancy statement; see
     * {@code InviteThrottle}.
     */
    private final InviteThrottle inviteThrottle;

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
        // HD-130 §10: the mode a NEW workspace starts in, from configuration — never a
        // profile, because access modes are a product feature and not a plan feature. It
        // never moves an existing workspace: V14 set every one of them OPEN and the only way
        // to change one is PATCH /api/workspaces/{ws}. Demo seeding comes through this same
        // method on purpose, so a DC operator who configures STRICT gets a strict demo
        // workspace — no bypass is added for seeding (epic §11.5).
        workspace.setProjectAccessMode(workspaceProperties.defaultProjectAccessMode());
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
     *
     * <p><strong>Check order is the contract</strong> (HD-190 §4.2): workspace resolved
     * (<strong>404</strong> for a non-member and an unknown workspace alike) → {@code
     * workspace.member.manage} (403) → role assignable / OWNER-not-grantable / grant ceiling
     * (422, 403) → already a member (409) → <strong>the invitation ceilings (429)</strong> →
     * write the invite and send the mail. Nothing above the ceilings sends mail, so a refusal
     * there costs neither the caller's own budget nor a stranger's recipient cap; and the 429 is
     * spent after tenancy, so — unlike every other 429 in this API — it is only ever seen by a
     * proven member.
     *
     * <p><strong>Seam for a per-workspace cap on outstanding invitations</strong> (HD-190 §6.4,
     * open question Q1 — deliberately not built here). If it is ever wanted it belongs
     * <em>between</em> the already-a-member check and {@code inviteThrottle.require}, counting
     * {@code workspace_invites} rows with {@code acceptedAt IS NULL AND expiresAt > now()}, and it
     * is a <strong>409 stock cap</strong> in the shape of {@code app.roles.max-custom-per-workspace}
     * — not a 429, and <em>not</em> under {@code app.rate-limit.enabled}, because that switch turns
     * off brute-force protection and an operator turning that off has not asked to remove an
     * unrelated stock cap. Its prerequisite is a pending-invite list plus a revoke endpoint:
     * neither exists today, so its refusal ("revoke some") would prescribe an action its reader
     * cannot perform. Nothing below is arranged around its absence.
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
        // Folded with Locale.ROOT ONCE, HERE, and compared with = everywhere downstream (HD-120).
        // Not lower(), not equalsIgnoreCase: this value identifies the INVITATION — it is what
        // acceptInvite matches an account against — and there an extra match lets the wrong person
        // accept somebody else's invitation, so the comparison stays exact.
        //
        // THE THROTTLE BELOW NEEDS THE OPPOSITE and gets it elsewhere. For a ceiling an extra match
        // raises a count and refuses SOONER, so over-folding is fail-safe and under-folding is the
        // hole: keyed on this value alone, victim+1@ / victim+2@ / v.i.c.t.i.m@googlemail.com are
        // distinct strings that reach one human, and both recipient ceilings read zero every time.
        // So the throttle counts MailAddresses.throttleKey(email) — derived inside the throttle, not
        // here, so no call site can forget it — while this value stays exactly what was typed.
        var email = req.email().toLowerCase(Locale.ROOT);
        // Check not already a member
        userRepository.findByEmail(email).ifPresent(user -> {
            if (memberRepository.existsByWorkspaceAndUser(workspace, user)) {
                throw new AlreadyWorkspaceMemberException();
            }
        });

        // HD-190 — the invitation ceilings, and this is the ONLY place they may be spent.
        //
        // Everything above refuses without sending mail, so a caller can exhaust neither their own
        // budget nor a stranger's recipient cap by probing invalid roles or workspaces they cannot
        // see.
        //
        // That is a claim about everything ABOVE this line, and NOT about this line. The two halves
        // inside are spent in order, so a request refused by the RECIPIENT ceilings has already
        // spent one unit of the caller's own hourly and daily SENDER budget: that budget is an
        // in-memory counter incremented inside its own compute, so nothing here gives it back, not
        // even a rollback. Accepted, and worth stating rather than implying otherwise — the cost is
        // self-inflicted, keyed on the caller, and ages out with the caller's own window. The
        // direction that would matter is the reverse, and it does not happen: a sender-budget
        // refusal never spends a victim's recipient cap, which is why the sender half runs first.
        //
        // And everything above has already answered the tenancy question: a 429 from here is
        // only ever seen by a proven member, which is exactly why these ceilings are NOT an
        // interceptor — a recipient-keyed refusal spent before the workspace is resolved would
        // answer a cross-tenant question to a non-member, where this project requires a 404.
        //
        // The supplier is evaluated only if the cooldown fires: it decides whether the refusal may
        // claim the earlier invitation is still waiting in the invitee's inbox. Removing a member
        // deletes their unaccepted invites (HD-132), so that claim goes stale in exactly the
        // workflow — remove, realise the mistake, re-invite — that lands inside the cooldown.
        //
        // Scoped to THIS workspace, not to the actor: the cooldown ignores workspaces, so it can
        // fire on a send from one the actor has since been removed from, and their own sent invites
        // survive that removal. An actor-scoped check would then report the live state of a row they
        // can no longer reach. They may see this row: membership and member.manage were proven a few
        // lines up.
        // FOR HD-133 (UNIQUE(workspace_id, email) on workspace_invites), AND FOR ANY OTHER CHECK
        // THAT CAN REFUSE THIS REQUEST: it goes ABOVE this line, never below it. The throttle
        // RECORDS its mail_send_event here, in this transaction, before the insert further down.
        // Anything that rolls the transaction back afterwards — a constraint violation, a late 409,
        // an exception from a new validation — unwrites that row, which hands callers a free way to
        // probe the recipient ceilings without ever spending them: the refusal is observed, the
        // count is not. HD-190's own ordering follows the same rule and is why every tenancy, role
        // and already-a-member check is above.
        //
        // AND THE INVERSE IS THE SHARPER HALF OF THE SAME ROLLBACK: a rollback taken after the
        // throttle passed used to leave all three of MAIL SENT, CEILING NOT SPENT, and a join link
        // whose workspace_invites row never existed. Repeated, that is the mail-bomb these ceilings
        // exist to stop, delivered free of charge, plus a recipient whose link answers 404.
        // HD-181 CLOSED THE MAIL HALF ONLY: the send at the end of this method is now registered on
        // AfterCommit, so no rollback can deliver it. The other two are unchanged and still argue
        // for the ordering rule above — a rollback still unwrites the mail_send_events row while
        // the refusal that caused it was observed, which is the free probe. HD-133's
        // UNIQUE(workspace_id, email) is what makes that reachable: a re-invite that clears a short
        // cooldown and then collides at flush has exactly this shape. Moving the duplicate check
        // above this line is still the fix, and is now the whole of what is left.
        //
        // (V21's header carries the rule too — the probe half of it; that migration is applied and
        // must not be edited, so this comment is the fuller copy. Both exist because whoever adds
        // the constraint opens a new migration, the entity and this method, and has no reason to
        // read the header of an applied one.)
        //
        // AND NOTHING SLOW GOES BETWEEN THIS LINE AND THE COMMIT. The throttle takes
        // pg_advisory_xact_lock on the RECIPIENT KEY and holds it to commit, and a recipient address
        // is something two tenants legitimately share — so any wait added below is a wait one tenant
        // can impose on another by inviting the same person.
        //
        // WHAT KEEPS SMTP OUT OF THAT LOCK CHANGED IN HD-181, and the old reason was weaker than it
        // read. It used to be "the send is @Async", but @Async is a hand-off to a BOUNDED pool whose
        // rejection policy is CallerRunsPolicy: with the queue full — i.e. under exactly the load
        // where this matters — the dispatch runs the send INLINE on this thread, which put a whole
        // SMTP round trip (plus, for critical mail, its retries) inside this lock. The send is now
        // registered on AfterCommit, so it is ordered after the commit that releases the advisory
        // lock, and a caller-runs send costs the caller latency rather than costing every tenant
        // sharing this recipient key a lock hold. @Async still belongs on the mailer for latency;
        // it is no longer what makes this section safe. What is still true, and is the rule to keep:
        // nothing slow may be ADDED between this line and the commit.
        inviteThrottle.require(actor.getId(), email, workspace.getId(),
                () -> inviteRepository.existsByWorkspaceAndEmailAndAcceptedAtIsNullAndExpiresAtAfter(
                        workspace, email, Instant.now())
                        ? "That invitation is still valid — ask them to check their inbox, "
                          + "including spam."
                        : null);

        var rawToken = TokenUtils.generateRawToken();
        var invite = new WorkspaceInvite();
        invite.setWorkspace(workspace);
        invite.setEmail(email);
        invite.setRole(roleCatalog.reference(granted.id()));
        invite.setTokenHash(TokenUtils.sha256(rawToken));
        invite.setInvitedBy(actor);
        invite.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600)); // 7 days
        inviteRepository.save(invite);
        metrics.inviteSent();

        // The folded value, not req.email(): the throttle counted it, workspace_invites stores it and
        // the log line took its domain from it, so mailing anything else would make "the address we
        // bounded is the address we wrote to" false by one character. Case only, today — but it is
        // the line a future normalisation would silently not apply to.
        //
        // What makes it SAFE to write to a folded address is the ASCII-only local part enforced on
        // InviteMemberRequest, and the two must move together: this fold is toLowerCase(Locale.ROOT),
        // which collapses U+212A KELVIN SIGN onto plain k, so without that constraint this line
        // would hand a workspace name and a live join token to whoever owns the ASCII spelling. Any
        // widening of what the DTO accepts has to be re-argued HERE, at the send.
        //
        // HD-181 — registered ON the commit, not dispatched from inside it, so a rollback taken
        // anywhere above delivers nothing. The workspace name is read into a local first (the id is
        // read eagerly by the concatenation below, before any deferral):
        // the lambda must not touch the EntityManager at all, which is a stronger rule than "no
        // lazy association" and is the one AfterCommit now states (a read there fails loudly; a
        // write joins an already-committed transaction and is discarded in silence).
        //
        // DOMAIN ONLY in the description, never the address — the same rule RecipientMailThrottle
        // applies to its own send line, and this description is written verbatim into a shipped log.
        // The address is not lost: workspace_invites holds it, and the workspace id below is what
        // takes an operator to the row.
        var workspaceName = workspace.getName();
        AfterCommit.run("workspace-invite email to a " + MailAddresses.domainOf(email)
                        + " address for workspace " + workspace.getId(),
                () -> mailService.sendWorkspaceInviteEmail(email, workspaceName, rawToken));
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
        // Exact equals for the reason spelled out on the accept path below: both sides are
        // Locale.ROOT-folded on write, so an ignore-case compare would only widen this to
        // Unicode confusables of somebody else's address. Declining is destructive - it
        // deletes the invitation - so the wrong account must not reach it either (HD-120).
        if (!invite.getEmail().equals(actor.getEmail())) {
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
        // let a different account join with the invite's role.
        //
        // Exact equals, and the exactness IS the binding. Both sides are already canonical
        // when they are written: inviteMember folds the invited address with Locale.ROOT,
        // and every site that creates a users row folds the same way. So a case-insensitive
        // compare here can never rescue a legitimate invitee - it can only ADD matches, and
        // what it adds is Unicode confusables. equalsIgnoreCase compares through
        // Character.toUpperCase/toLowerCase, which collapse dotless i (U+0131), dotted
        // capital I (U+0130) and long s (U+017F) onto ASCII i / s. Locale.ROOT folding does
        // not, and neither does the UNIQUE on users.email, so each of those spells a
        // genuinely DIFFERENT account: an invite addressed to <dotless-i>van@x.com was
        // redeemable by ivan@x.com.
        //
        // The Kelvin sign (U+212A) is NOT a fourth member of that list, and it was written
        // here as one: toLowerCase(Locale.ROOT) DOES collapse it onto plain k, so it never
        // survives the fold on either side of this comparison. Redemption is safe against it
        // for a different mechanism than the one above - AuthService folds every users.email
        // write with the same Locale.ROOT, so the Kelvin and the ASCII spelling insert the
        // SAME string and the second registration is refused by the UNIQUE. There is no
        // second account for a wrong person to be. What that character does carry is a
        // SENDING-side hazard, where the same fold silently retargets an invitation at the
        // ASCII person's real inbox - closed at the boundary, by InviteMemberRequest
        // refusing a non-ASCII local part.
        //
        // And that constraint does NOT make this comparison redundant, which is the easy wrong
        // conclusion to draw from the paragraph above. It bounds the INVITED address only. The
        // confusable half of the dangerous pair is the ACCOUNT's address - RegisterRequest
        // carries no such restriction, so users.email may perfectly well hold <dotless-i>van@x.com
        // - and it is an ASCII invite plus a non-ASCII account that equalsIgnoreCase would match.
        //
        // So do not "restore" the case-insensitive form. Against a pair of values that are
        // canonical on write, case-insensitivity is not tolerance for how someone typed
        // their address - it is a second spelling of somebody else's. The general rule:
        // fold once, at the boundary, and compare exactly ever after (HD-120).
        // Pinned by InviteEmailBindingTest.
        if (!invite.getEmail().equals(actor.getEmail())) {
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
        var base = name.toLowerCase(Locale.ROOT)
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
