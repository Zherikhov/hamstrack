package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.WorkspaceProperties;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.observability.ProductMetrics.WorkspaceSource;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.ratelimit.InviteThrottle;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.tx.AfterCommit;
import com.hamstrack.common.util.TokenUtils;
import com.hamstrack.workspace.dto.*;
import com.hamstrack.workspace.entity.*;
import com.hamstrack.workspace.exception.*;
import com.hamstrack.workspace.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
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
    /**
     * HD-158 §6.3: this class now takes a row lock on {@code workspace_invites} — on both accept
     * paths and on the withdrawal — and a lock wait has no bound of its own. Bound, then lock.
     */
    private final LockTimeout lockTimeout;

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
     * <p><strong>Check order is the contract</strong> (HD-190 §4.2, HD-133 §4.1): workspace
     * resolved (<strong>404</strong> for a non-member and an unknown workspace alike) → {@code
     * workspace.member.manage} (403) → role assignable / OWNER-not-grantable / grant ceiling
     * (422, 403) → already a member (409) → <strong>the caller's own invitation volume (429)</strong>
     * → <strong>a pending invitation to this address (409 {@code DUPLICATE_INVITE})</strong> →
     * <strong>the recipient's ceilings (429)</strong> → write the invite and send the mail. Nothing
     * above sends mail, so a refusal costs a stranger's recipient cap nothing; and every 429 here
     * is spent after tenancy, so — unlike every other 429 in this API — it is only ever seen by a
     * proven member.
     *
     * <p><strong>The two ceilings are split around the duplicate check on purpose, and putting
     * them back together is the mistake to avoid</strong> (HD-133 round 2). The recipient half
     * <em>records</em> a {@code mail_send_events} row inside this transaction, so everything that
     * can refuse must sit above it — otherwise a rollback unwrites the record while the caller has
     * already observed the refusal, which is a free probe of a stranger's ceilings. The sender half
     * is an in-memory counter no rollback returns, so it carries none of that hazard and is spent
     * <em>first</em>, which is what keeps a refused request from being free: with both halves
     * below the duplicate check, a repeat POST to a pending address cost the caller nothing on an
     * endpoint that has no principal throttle interceptor. Both call sites carry the full
     * statement; a new check placed below the recipient half reopens the hole no matter what the
     * check is for, and a new check placed below the sender half is one more free refusal.
     *
     * <p><strong>Seam for a per-workspace cap on outstanding invitations</strong> (HD-190 §6.4,
     * open question Q1 — deliberately not built here). If it is ever wanted it belongs
     * <em>between</em> the duplicate check and {@code inviteThrottle.requireRecipientCeilings},
     * counting
     * {@code workspace_invites} rows with {@code acceptedAt IS NULL}, and it
     * is a <strong>409 stock cap</strong> in the shape of {@code app.roles.max-custom-per-workspace}
     * — not a 429, and <em>not</em> under {@code app.rate-limit.enabled}, because that switch turns
     * off brute-force protection and an operator turning that off has not asked to remove an
     * unrelated stock cap. Its prerequisite — a pending-invite list plus a revoke endpoint, so that
     * its refusal ("withdraw some") prescribes an action its reader can perform — is satisfied
     * since HD-158, which is a change from what this paragraph used to say. It is still not built,
     * and nothing below is arranged around its absence. If it is, count {@code acceptedAt IS NULL}
     * and nothing narrower: that is the predicate of both the index and the list, and a cap that
     * ignored expired rows would refuse to admit the very rows an administrator must clear.
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

        // HD-190's SENDER half, and it is spent HERE — above the duplicate refusal — while the
        // RECIPIENT half stays below it. The two used to be one call (InviteThrottle.require);
        // they are two methods now, and this is the split.
        //
        // WHY THEY SPLIT, because a reader who does not know will put them back together. The
        // ordering rule stated at length below is about TRANSACTIONAL state: the recipient half
        // WRITES a mail_send_events row in this transaction, so a refusal raised after it rolls
        // that row back while the caller has already OBSERVED the refusal — the ceiling reported,
        // the slot unspent, which is a free probe of a stranger's ceilings. None of that applies
        // to this line. The sender budget is an in-memory
        // hourly/daily counter keyed on the caller (ADR-0015); no rollback returns it, so there is
        // nothing here for a refusal below to unwrite, and spending it above one is safe.
        //
        // AND NOT MERELY SAFE — REQUIRED, or the rule below eats itself. Every new refusal on this
        // path lands ABOVE the recipient half by that rule, so while both halves travelled
        // together each check added made one more refusal FREE. That is not hypothetical: with the
        // duplicate check above the combined call, a repeat POST to a pending address spent NO
        // budget of either kind, and this endpoint has no PrincipalThrottleInterceptor to charge
        // the caller instead (those cover reports/insights/search/filters only) — so an account
        // holding workspace.member.manage could loop cheap 409s indefinitely. Spending the sender
        // half first restores "every refusal costs the caller something" without moving the
        // recorded event above anything.
        //
        // The consequence to know: a caller who is BOTH over their hourly volume AND re-inviting a
        // pending address now sees the 429 rather than the 409. Correct in that order — the volume
        // ceiling is a statement about the caller and holds whatever the address turns out to be,
        // and answering it first also declines to say whether that address is already invited.
        inviteThrottle.requireSenderVolume(actor.getId());

        // HD-133 — ONE STANDING OFFER PER ADDRESS PER WORKSPACE, and this is the sentence rather
        // than the enforcement. The enforcement is workspace_invites_pending_email_uk (V22): two
        // concurrent requests both find nothing here and both insert, and the index arbitrates.
        // What this buys is that the ordinary case gets a message naming a remedy instead of a
        // constraint violation — and, far more importantly, the ORDER below.
        //
        // *** THIS CHECK IS ABOVE inviteThrottle.requireRecipientCeilings, AND THAT IS THE WHOLE
        // POINT. *** The long comment underneath states the rule and names this ticket: that half
        // RECORDS its mail_send_events row in this transaction, so a refusal that rolls back
        // afterwards unwrites the record while the caller has already seen the refusal — invite a
        // victim's address, catch the 409, pay nothing, and repeat to map a stranger's ceilings.
        // Do not move it down, and do not add a cheaper-looking check below.
        //
        // AND IT IS BELOW inviteThrottle.requireSenderVolume, WHICH IS THE OTHER HALF OF THE SAME
        // FIX. "Pay nothing" was true of this refusal too for one review round, when both halves
        // of the throttle sat below here: the 409 was free. The sender half is now spent above,
        // for the reasons given at that line.
        //
        // AND THE QUERY FOLDS IN SQL, NOT IN JAVA, for a reason that is exactly this hazard in
        // miniature: `email` above is already toLowerCase(Locale.ROOT), so an equals() against it
        // looks equivalent and is not. Locale.ROOT's fold and PostgreSQL's lower() are different
        // functions, and InviteMemberRequest constrains only the LOCAL PART to ASCII — the domain
        // may be internationalised. On a character where they disagree, Java says "free", the
        // index says "taken", and the result is a violation at flush, a rollback, and the free
        // ceiling probe the ordering rule exists to prevent. Ask the database the question the
        // index answers; the partial index is the access path, so it costs one indexed lookup.
        //
        // BELOW the already-a-member check, deliberately: a person can be both a member and the
        // addressee of a leftover unaccepted row (member removal deletes those, joining does not),
        // and "they are already in this workspace" is the more useful answer to the more important
        // question. Only one 409 is emitted and the membership one wins.
        //
        // AN EXPIRED ROW BLOCKS. That is PostgreSQL's answer, not a product choice — a partial
        // index predicate must be IMMUTABLE and now() is STABLE, so `accepted_at IS NULL` is the
        // only enforceable form. Hence two wordings off the same errorType, and hence the refusal
        // must name the withdrawal: HD-158 shipped it, it works on expired rows, it needs the
        // permission this caller just proved, and findUnacceptedForWorkspace puts the blocking row
        // on the same screen as the form. If a retention sweep ever removes lapsed rows, the
        // lapsed wording goes quiet on its own and the index still cannot change.
        inviteRepository.findPendingByWorkspaceAndFoldedEmail(workspace.getId(), email)
                .ifPresent(existing -> {
                    throw existing.isExpired()
                            ? DuplicateInviteException.lapsed(email)
                            : DuplicateInviteException.live(email);
                });

        // HD-190 — the RECIPIENT ceilings, and this is the ONLY place they may be spent. The
        // sender half was spent further up, above the duplicate refusal; the comment there says
        // why the two are not one call.
        //
        // Everything above refuses without sending mail, so a caller can exhaust neither their own
        // budget nor a stranger's recipient cap by probing invalid roles or workspaces they cannot
        // see.
        //
        // That is a claim about everything ABOVE this line, and NOT about this line. The sender
        // half runs first, so a request refused HERE has already spent one unit of the caller's own
        // hourly and daily volume: that budget is an in-memory counter incremented inside its own
        // compute, so nothing here gives it back, not even a rollback. Accepted, and worth stating
        // rather than implying otherwise — the cost is self-inflicted, keyed on the caller, and
        // ages out with the caller's own window. The direction that would matter is the reverse,
        // and it does not happen: a sender-budget refusal never spends a victim's recipient cap,
        // which is why the sender half runs first.
        //
        // And everything above has already answered the tenancy question: a 429 from here is
        // only ever seen by a proven member, which is exactly why these ceilings are NOT an
        // interceptor — a recipient-keyed refusal spent before the workspace is resolved would
        // answer a cross-tenant question to a non-member, where this project requires a 404.
        //
        // NO COOLDOWN ADDENDUM IS PASSED, AND THE CONDITION UNDER WHICH ONE WOULD WANT REVIVING IS
        // NAMED HERE RATHER THAN THE TICKET THAT REMOVED IT. HD-190 gave the cooldown an optional
        // sentence — "that invitation is still valid — ask them to check their inbox" — emitted
        // only when a live unaccepted row to this exact address existed in this workspace. The
        // duplicate refusal above matches a strict SUPERSET of that (same workspace, same address
        // folded, unaccepted, expiry irrelevant) and sits one step earlier, so no request can reach
        // the cooldown with such a row still standing: the sentence was unreachable the moment the
        // pre-check landed, and an unreachable sentence is a claim a future reader will trust. Its
        // finder went with it. If the duplicate refusal is ever NARROWED — scoped tighter than the
        // whole workspace, or made to ignore some class of unaccepted row — an addendum becomes
        // reachable again and the claim would have to be re-checked against the row before being
        // printed, which is why the Supplier parameter survives on InviteThrottle.
        // FOR ANY CHECK THAT CAN REFUSE THIS REQUEST: it goes ABOVE this line, never below it.
        // HD-133's duplicate check is above, which is what that ticket was mostly about. The throttle
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
        // the refusal that caused it was observed, which is the free probe. HD-133's partial unique
        // index is what made that reachable: a re-invite that clears a short cooldown and then
        // collides at flush has exactly this shape. The duplicate check above this line is that
        // fix. What remains is the residue the pre-check cannot cover — a genuine concurrent
        // insert, translated at the saveAndFlush below. Three routes, and only one of them is
        // honest residue: CLOSED by the cooldown when one sender repeats; NOT CLOSED for two
        // different senders, because the cooldown is keyed per (sender, recipient) so the second
        // sender's count is zero and their rollback unwrites their own event — one bit learned,
        // a concurrent winner required; and HARMLESS with rate limiting off, where there is no
        // ceiling left to probe. An earlier revision of this sentence said the residue was
        // reachable ONLY with rate limiting off, and pointed at the catch below — which states
        // the opposite. The catch is right.
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
        inviteThrottle.requireRecipientCeilings(actor.getId(), email, workspace.getId());

        var rawToken = TokenUtils.generateRawToken();
        var invite = new WorkspaceInvite();
        invite.setWorkspace(workspace);
        invite.setEmail(email);
        invite.setRole(roleCatalog.reference(granted.id()));
        invite.setTokenHash(TokenUtils.sha256(rawToken));
        invite.setInvitedBy(actor);
        invite.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600)); // 7 days
        // saveAndFlush, so that the race the pre-check above CANNOT close surfaces here — as a
        // translated DataIntegrityViolationException — instead of at commit, where it would escape
        // as a 500 after the controller had already built a 201. The pre-check is the sentence;
        // workspace_invites_pending_email_uk is the invariant, and two requests that both found the
        // address free both arrive at this line.
        //
        // WHEN THAT IS ACTUALLY REACHABLE, AND WHAT THE ROLLBACK COSTS ON EACH ROUTE. Two
        // requests reach this line together, and the throttle's behaviour between them depends on
        // WHO SENT THEM — which is the distinction an earlier draft of this comment flattened.
        //
        // SAME SENDER, twice: closed, and closed by the cooldown rather than by luck.
        // RecipientMailThrottle takes pg_advisory_xact_lock on the recipient key and holds it to
        // commit, and two addresses that collide in this index share a throttle key wherever the
        // two folds agree — which is every address this endpoint accepts. Not "necessarily": the
        // throttle keys off the JAVA fold and the index off PostgreSQL's lower(), which this file
        // insists elsewhere are different functions, so "strictly coarser" would be composing two
        // things it has just said do not compose. The conclusion is unchanged, because
        // InviteMemberRequest constrains the local part to ASCII and the two folds cannot disagree
        // there; widen what the DTO accepts and this sentence is one of the places to re-check.
        // The second transaction waits,
        // then counts the first's now-committed event, then is refused by the cooldown — which is
        // keyed on the (sender, recipient) PAIR and therefore sees it. A 429, not a collision.
        //
        // TWO DIFFERENT SENDERS, both holding workspace.member.manage here: NOT closed, and this
        // is the honest statement of the residue. The advisory lock still serialises them, but
        // samePair() is 0 for the second sender, so no cooldown fires; if the recipient's daily
        // cap is not yet reached either, that sender records its event, collides at the flush
        // below, and the rollback unwrites the event it just wrote. It has then learned one bit —
        // "this recipient's daily cap had room" — without spending a slot for it.
        //
        // ACCEPTED, WITH THE REASONS, BECAUSE THE STRUCTURAL FIX COSTS MORE THAN THE BIT. Every
        // round of this needs a concurrent WINNER who does spend a recipient slot and does send
        // real mail; the loser still burns a unit of its own in-memory sender volume; and the
        // unwritten row corresponds to no delivery, since the send is registered AfterCommit. The
        // close would be @Transactional(REQUIRES_NEW) on RecipientMailThrottle.record, so the
        // append outlives the rollback — and it is REJECTED HERE FOR TWO REASONS, both worse than
        // the leak. (1) It takes a SECOND POOLED CONNECTION while this transaction holds the
        // recipient advisory lock, which puts a wait for the connection pool inside the one lock
        // this method's own rule says nothing slow may sit inside — and that lock is shared with
        // every other tenant inviting the same person. (2) It makes EVERY rollback on this path
        // permanently spend a recipient slot for mail that was never sent, so a lock timeout or a
        // statement budget would silently consume a stranger's daily allowance. A second advisory
        // lock keyed on (workspace, address) is the wrong fix for the same reason it always was: a
        // lock-ordering obligation bought to improve a status code.
        //
        // WITH RATE LIMITING OFF, no lock is taken at all, the inserts race outright, and the
        // loser lands in the catch below — and there the rollback costs nothing, because with the
        // master switch off there are no ceilings left to probe, only the forensic row.
        //
        // The rule that survives all three routes, stated as a property because a list of
        // configurations goes stale one profile before it does: THE ROLLBACK BELOW CAN UNWRITE A
        // MAIL_SEND_EVENTS ROW WHOSE REFUSAL THE CALLER HAS ALREADY SEEN, AND NOTHING ON THIS PATH
        // PREVENTS THAT — the pre-check above only makes it rare. So no check that can refuse this
        // request may be added between the throttle and the commit.
        //
        // Do NOT close this with a second advisory lock keyed on (workspace, address). It would add
        // a lock-ordering obligation against the recipient lock, on a path whose design rule is
        // that nothing slow may sit between the throttle and the commit, to improve a status code
        // in a configuration that has already switched its own protections off.
        try {
            inviteRepository.saveAndFlush(invite);
        } catch (DataIntegrityViolationException e) {
            // ONLY the pending-email index means "somebody else won the race". Anything else is a
            // genuine fault and must keep its 500 rather than masquerade as a plausible conflict —
            // the shape that makes an incident hard to diagnose. THIS CLASS logs only the
            // constraint NAME: no SQL, no exception message, no user input. That is a claim about
            // these two log lines and NOT about the request — Hibernate's SqlExceptionHelper logs
            // the PSQLException at ERROR before this catch is even entered, which is why
            // logServerErrorDetail=false is set on the datasource (see application.properties).
            // Without it PostgreSQL's DETAIL puts a third party's full address in the log:
            // `Key (workspace_id, lower(email))=(…, victim@example.com) already exists`.
            //
            // AND THE CATCH TYPE IS PART OF THE CONTRACT, not an incidental choice. It catches
            // Spring's DataIntegrityViolationException, which is what a REPOSITORY call produces:
            // saveAndFlush goes through the persistence-exception translator, so Hibernate's own
            // org.hibernate.exception.ConstraintViolationException always arrives wrapped. A future
            // writer of this table who inserts through an EntityManager instead gets the
            // UNwrapped exception, this catch does not see it, and a 23505 that means "somebody
            // else won the race" leaves as a 500. It fails safe — no wrong 409, no leak — and it
            // is unreachable from this method today, which is why nothing here catches the wider
            // type speculatively. It is written down because the interface javadoc on
            // WorkspaceInviteRepository explicitly invites such a writer.
            if (!isDuplicateInvite(e)) throw e;
            // The winner's row is live by construction (it was inserted moments ago with a
            // seven-day TTL), so the lapsed wording cannot apply and the row is not re-read to
            // find that out — this transaction is already doomed and a read here would fail on the
            // broken session rather than answer.
            throw DuplicateInviteException.live(email);
        }
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

    /**
     * <strong>Every invitation this workspace has issued and not had accepted</strong> (HD-158
     * §4.1) — the administrator's counterpart to {@link #listPendingInvites}, which is the
     * <em>invitee's</em> view of their own.
     *
     * <p><strong>Permission: {@code workspace.member.manage}</strong>, and the answer for a member
     * who lacks it is a <strong>403</strong>, not an empty list and not a count. This list is
     * <em>nothing but</em> addresses — there is no residue worth rendering read-only once they are
     * removed — so "read-only for everyone" and "not an address-enumeration surface" cannot both
     * hold, and the second wins. An empty array would be a statement we know to be false, rendered
     * as "no invitations are waiting" on a workspace that has ten; this project already refuses
     * that shape elsewhere. A count without addresses would disclose without granting any ability
     * to act on the disclosure.
     *
     * <p>That 403 does <strong>not</strong> contradict the 404 posture. Tenancy is answered first
     * and answered the way it always is — {@code requireMember} cannot tell a non-member from an
     * unknown workspace and neither can its caller. 403 is what a <em>proven member</em> gets, the
     * same as on every other gated call in this codebase.
     *
     * <p>Newest first, expired rows included and labelled rather than filtered — see
     * {@code WorkspaceInviteRepository.findUnacceptedForWorkspace} for why a list narrower than
     * the table is a list that cannot explain the next refusal.
     *
     * <p>Roles are resolved with the degrade, exactly as {@link #listMembers} and
     * {@link #listPendingInvites} do: one corrupt {@code role_id} renders that row with
     * {@code role: null} and {@code roleId: null} instead of emptying an admin screen. The source
     * tag names {@code workspace_invites} so the ERROR points at the table the wrong row is in.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceInviteResponse> listInvites(User actor, UUID workspaceId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        memberService.requireMemberAdmin(ctx.permissions());
        return inviteRepository.findUnacceptedForWorkspace(workspaceId).stream()
                .map(i -> WorkspaceInviteResponse.of(i,
                        workspaceAccess.resolveRoleOrDegrade(i.getRole().getId(),
                                        RoleScope.WORKSPACE, workspaceId,
                                        RoleScopeViolationSource.WORKSPACE_INVITES)
                                .orElse(null)))
                .toList();
    }

    /**
     * <strong>Withdraw an invitation</strong> (HD-158 §4.2) — a hard delete of the
     * {@code workspace_invites} row.
     *
     * <p><strong>Permission: {@code workspace.member.manage}</strong>, and the grant ceiling is
     * deliberately <em>not</em> applied even though {@link #inviteMember} applies it. A ceiling
     * exists to stop somebody handing out authority they do not hold; withdrawing is subtraction
     * and grants the revoker nothing. Applying it would produce a refusal — "you may not withdraw
     * this invitation because it carries a permission you lack" — that protects nobody and leaves
     * an unrevokable standing grant in the workspace, which is the state this exists to end. An
     * invitation can never carry the built-in Owner ({@link OwnerIsNotGrantableException}), so no
     * invitation can outrank every holder of the permission.
     *
     * <p><strong>The delete is sufficient by construction.</strong> The emailed link resolves
     * through {@code findByTokenHashForUpdate} and accept-by-id through {@code findByIdForUpdate},
     * and both {@code orElseThrow} a 404; the invitee's own {@code GET /api/invites} stops listing
     * it on the next fetch. Nothing else has to be written for "a withdrawn invitation cannot be
     * accepted" to hold.
     *
     * <p><strong>And it frees nothing measured over time — no {@code mail_send_events} row is
     * touched here, deliberately</strong> (HD-158 §5, ADR-0015). {@code V21} exists because the
     * first design derived the invite cooldown from <em>this very table</em>, and review killed it
     * on the observation that a row here is deleted by paths the sender does not control — one of
     * them pressed by the victim, since {@code declineInvite} deletes. Its header named the future
     * hazard directly: correctness that depends on the continued <em>absence</em> of a delete
     * endpoint breaks silently in a future ticket. <strong>This method is that future
     * ticket.</strong> A refund here would defeat the whole control with two legitimate calls and
     * no exploit ({@code invite -> revoke -> invite}), and it would be a cross-tenant write
     * besides — the daily cap counts one <em>inbox</em> instance-wide, so workspace A would be
     * deciding how much mail workspace B may send a stranger. The rule to carry forward, phrased
     * as a property because a list of paths goes stale one path before it does:
     *
     * <blockquote>A revocation may free only a resource whose count it actually reduces —
     * outstanding rows, a uniqueness slot, a stock cap. It may never free a resource measured over
     * time: sends, cooldowns, daily ceilings. <strong>Deleting the record of an offer does not
     * delete the record of a delivery.</strong></blockquote>
     *
     * <p>What it <em>does</em> free is stock, and it does so with no code: <strong>HD-133's
     * uniqueness slot</strong> ({@code workspace_invites_pending_email_uk}, V22), because the row
     * is gone and the index has nothing to collide with. That is now the load-bearing half of this
     * endpoint rather than a side effect — an unaccepted invitation blocks a fresh one to the same
     * address <em>even after it expires</em> (the index predicate cannot mention expiry), so this
     * DELETE is the <em>only</em> way to re-offer access at a different role or with a fresh link,
     * and {@code DuplicateInviteException} sends its reader here by name. Two obligations follow:
     * this endpoint must keep working on expired rows, and {@link #listInvites} must keep showing
     * them — <strong>the set of rows that can block an invitation and the set an administrator can
     * see must be one set.</strong>
     *
     * <p><strong>Do not add a withdrawal-specific sentence to the cooldown refusal.</strong> There
     * used to be an addendum there (<em>"that invitation is still valid — ask them to check their
     * inbox"</em>) which suppressed itself after a withdrawal because its supplier looked for this
     * row; HD-133 made it unreachable — the duplicate refusal now rejects a superset of its
     * condition one step earlier — and it was removed with its finder. Reviving it in any form
     * would be one more bit about state the caller can already read from the list, and the supplier
     * would have to tell "withdrawn here" from "expired" from "never existed", recreating exactly
     * the row-state coupling ADR-0015 removed.
     *
     * <p><strong>404 for an already-withdrawn one, 409 for an accepted one, and the difference is
     * not cosmetic.</strong> Because withdrawal deletes, "already withdrawn" is <em>physically
     * identical</em> to "never existed" and to "belongs to another tenant" — the house style
     * already answers 404 for a repeat member removal, and anything else would require inventing a
     * tombstone whose only purpose is to make a second DELETE feel nicer. That 404 prescribes
     * nothing and asserts nothing false; the caller's actual goal is already true, which is why
     * the client renders it as success. An accepted invitation is the opposite: it is the one
     * state here whose reader can act, so it gets a 409 that says how.
     *
     * @throws WorkspaceNotFoundException 404 — unknown workspace, non-member, or no such
     *     invitation <em>in this workspace</em>. One exception and one message for all of them on
     *     purpose: an id from another tenant must not be told apart from a fabricated one, and the
     *     detail is not an existence oracle any more than the status code is. <strong>Round-1
     *     review asked whether an already-withdrawn invitation should say "Invitation not found"
     *     rather than "Workspace not found"; the answer is no, and the reason is not that it would
     *     leak today.</strong> It would not: only a proven member reaches this line, so the one
     *     party who can tell the two details apart already knows their own membership. But that is
     *     a property of the current CALLER SET, not of the code — the same shape as the lock
     *     waiver {@link #declineInvite} just had to undo — and a second string here would make
     *     every branch a future ticket adds under this method re-argue which of the two it picks,
     *     with a cross-tenant id told apart from a fabricated one the first time somebody picks
     *     wrong. One string forecloses that by construction, and it costs the caller nothing they
     *     could act on: 404 and 409 are the branch that matters, and the client already renders
     *     this 404 as success. If the distinction is ever wanted for a test, it belongs in the
     *     EXCEPTION TYPE, where an assertion can read it and no client can.
     * @throws InviteAlreadyAcceptedException 409 {@code INVITE_ALREADY_ACCEPTED}.
     */
    @Transactional
    public void revokeInvite(User actor, UUID workspaceId, UUID inviteId) {
        // Bound every lock wait in this transaction BEFORE anything can queue on one — this
        // method takes a row lock and a lock wait has no bound of its own (HD-158 §6.3).
        lockTimeout.applyToCurrentTransaction();
        // Tenancy first: 404 for a non-member and an unknown workspace, indistinguishably.
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        // Then the permission, and before the lock — an unauthorized caller never takes one.
        // That this is possible here and NOT on the invitee's by-id paths is a property of where
        // each authorizing fact lives, not an inconsistency: see
        // WorkspaceInviteRepository.findByIdForUpdate.
        memberService.requireMemberAdmin(ctx.permissions());
        // Two-key finder, never findById-then-compare: the workspace is part of the question.
        // FOR UPDATE because this is the first write to this table by an actor other than the
        // invitee, so it is the first that can interleave with acceptInvite — and WorkspaceInvite
        // has no @Version, so the loser of that race gets a StaleStateException 500 rather than a
        // clean 409. Under the lock, whichever side arrives second reads a settled row: verified
        // both ways — an accept committing under a waiting withdrawal yields this 409, and a
        // withdrawal committing under a waiting accept yields the accept's 404 with no membership
        // row written.
        var invite = inviteRepository.findByIdAndWorkspaceIdForUpdate(inviteId, workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (invite.isAccepted()) {
            throw new InviteAlreadyAcceptedException(inviteeName(invite.getEmail()));
        }
        // Read the two facts the audit line needs BEFORE the delete: both are lazy, and the
        // project reads first and mutates last. (The locking finder deliberately does not fetch
        // the role — see findByTokenHashForUpdate — so this is the one place it is loaded, one
        // extra single-row select on a path that already took a lock.)
        var roleKey = invite.getRole().getKey();
        var recipientDomain = MailAddresses.domainOf(invite.getEmail());
        // Expired rows are withdrawable: the list offers the control on every row it shows, so it
        // must work on every row it shows, and nothing else in this product ever removes one.
        inviteRepository.delete(invite);
        // The ONLY attribution a withdrawal leaves (HD-158 round 1). The spec argued none was
        // needed because "the row it deleted is described entirely by ids the operator already
        // has" — which is a claim a SOFT delete could keep and this one cannot: after the 204
        // there is no row to join those ids back to, the metric below is tagless, and "who
        // withdrew the invitation to the new CFO, and when" becomes unanswerable anywhere in the
        // system. Withdrawing a standing grant is a security-relevant act and its two neighbours
        // both log one (workspace.member.role_changed, workspace.member.removed).
        //
        // Ids, plus the recipient DOMAIN only — never the local part. That is the same rule the
        // invite send follows a few methods up, and for the same reason: this line ships to Loki,
        // where an address would outlive the workspace_invites row that legitimately held it.
        // The address is not lost while it matters: it is on the row until this delete. This line
        // pairs with the send line by workspace and recipient domain, which are the only two
        // fields that line carries — it names no invite id, so do not write "the id ties them".
        log.info("workspace.invite.revoked workspace={} actor={} invite={} role={} recipientDomain={}",
                workspaceId, actor.getId(), inviteId, roleKey, recipientDomain);
        // The missing term in the invitation lifecycle — without it a withdrawn invitation is
        // indistinguishable from an ignored one, and the acceptance ratio HD-190 leans on cannot
        // tell a workspace cleaning up from a workspace being ignored. On the 204 only.
        metrics.inviteRevoked();
    }

    /**
     * Who the accepted invitation let in, for the 409's sentence. The address is the fallback
     * rather than a failure: {@code users.email} and {@code workspace_invites.email} are both
     * {@code Locale.ROOT}-folded on write so the lookup normally hits, and an address the caller
     * submitted themselves and can read on the very row they clicked discloses nothing new if it
     * does not.
     */
    private String inviteeName(String email) {
        return userRepository.findByEmail(email)
                .map(User::getDisplayName)
                .filter(name -> !name.isBlank())
                .orElse(email);
    }

    // Accept via the emailed token link.
    @Transactional
    public WorkspaceResponse acceptInvite(User actor, String rawToken) {
        // Bound the wait before taking the lock below (HD-158 §6.3). First statement, because a
        // bound applied after a locking read bounds nothing.
        lockTimeout.applyToCurrentTransaction();
        var hash = TokenUtils.sha256(rawToken);
        var invite = inviteRepository.findByTokenHashForUpdate(hash)
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

    // Accept a specific invite by id (from the onboarding screen). Like declineInvite, this locks
    // a caller-supplied id BEFORE the email equality that authorizes it, which reads as the
    // opposite of revokeInvite's "permission first, then the lock". It is not: a revoker's
    // authorizing fact lives in another table and is knowable before this row is touched, while
    // the invitee's authorizing fact IS this row. Stated once, on the finder both paths share.
    @Transactional
    public WorkspaceResponse acceptInvite(User actor, UUID inviteId) {
        lockTimeout.applyToCurrentTransaction();
        var invite = inviteRepository.findByIdForUpdate(inviteId)
                .orElseThrow(WorkspaceNotFoundException::new);
        return acceptInvite(actor, invite);
    }

    /**
     * Decline an invite addressed to the caller. Removes it (single-use, email-bound); an admin
     * can always re-invite.
     *
     * <p><strong>Locked, and the reason it was not is the reason it now is.</strong> HD-158 §6.3
     * waived a lock here because "it is the invitee acting on their own row, it cannot race their
     * own accept" — true of the world that sentence was written in, where the invitee was this
     * table's only writer, and falsified by {@link #revokeInvite} in the very same ticket. What
     * the waiver leaves behind is not a crash and not corruption: an administrator's withdrawal
     * committing between an unlocked read here and the flush makes the DELETE affect zero rows,
     * Hibernate raises {@code StaleStateException}, and {@code GlobalExceptionHandler} turns that
     * into a 409 reading <em>"Someone else is changing this right now — try again in a
     * moment"</em>. The invitee is told to retry a request that can never succeed again, because
     * their invitation is permanently gone — <strong>a refusal prescribing an action its reader
     * cannot perform</strong>, which is a mistake this project has now shipped four times. Under
     * the lock the second arrival reads a settled row and answers definitely: the withdrawal
     * committed first, so there is no row, so this is the 404 below.
     *
     * <p>The general form, because a list of a table's writers goes stale one writer before the
     * list does: <em>a waiver justified by "there is only one writer" expires the moment a ticket
     * adds the second, and the ticket that adds it is the one that owes the fix.</em>
     *
     * @throws WorkspaceNotFoundException 404 — no such invitation, one addressed to somebody
     *     else, or one withdrawn while this call waited for the lock.
     */
    @Transactional
    public void declineInvite(User actor, UUID inviteId) {
        // Bound the wait before taking the lock (HD-158 §6.3). First statement, because a bound
        // applied after a locking read bounds nothing.
        lockTimeout.applyToCurrentTransaction();
        // Locked, and taken BEFORE the address check that authorizes it — see
        // WorkspaceInviteRepository.findByIdForUpdate for why that ordering is the same rule
        // revokeInvite follows above, not the opposite of it.
        var invite = inviteRepository.findByIdForUpdate(inviteId)
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

    /**
     * <strong>The name of the partial unique index HD-133 added (V22)</strong>, and the only
     * constraint whose violation this class is allowed to turn into a 409.
     *
     * <p>Not derived from anything: PostgreSQL reports the index name and Hibernate hands it
     * through, so a rename in a future migration must be mirrored here — at which point the
     * duplicate insert stops being translated and starts 500-ing, which is loud rather than silent.
     */
    private static final String PENDING_EMAIL_UNIQUE_CONSTRAINT = "workspace_invites_pending_email_uk";

    /**
     * Is this integrity violation the pending-invite index losing a race, or a real fault?
     *
     * <p>Translating <em>every</em> {@code DataIntegrityViolationException} on this path into a
     * 409 would hide a genuine 500-class bug behind a plausible-looking conflict — the shape that
     * makes an incident hard to diagnose. {@code workspace_invites} also carries a unique
     * {@code token_hash} and three foreign keys, any of which failing here means something quite
     * different and must keep its 500.
     *
     * <p><strong>THIS METHOD logs only the constraint NAME</strong>: no SQL, no exception message,
     * no user input — and on this path the input in question is a third party's email address.
     * Keeping that true of the whole REQUEST needs one more thing, because Hibernate's
     * {@code SqlExceptionHelper} logs the driver exception at ERROR before this is called:
     * {@code logServerErrorDetail=false} on the datasource, which masks PostgreSQL's DETAIL
     * (`Key (workspace_id, lower(email))=(…, victim@example.com) already exists`). Same shape as
     * {@code ComponentService.isNameConflict} / {@code LabelService.isNameConflict}.
     *
     * <p><strong>THE FALLBACK'S TRIGGER IS A SERVER MESSAGE LOCALE, NOT A SILENT DRIVER.</strong>
     * {@link #constraintNameOf} reads the name Hibernate's dialect extracted, and the PostgreSQL
     * extractor finds it by matching the literal English fragment
     * {@code violates unique constraint "} — so on a server whose {@code lc_messages} is anything
     * else it returns null for a perfectly well-formed 23505. Without a fallback the race would
     * then answer <strong>500 instead of 409</strong>, in a configuration nobody would notice
     * until it happened.
     *
     * <p>So the fallback matches the constraint's own name, which PostgreSQL quotes verbatim in
     * every locale, against the messages in the cause chain. <strong>Both</strong> branches are
     * gated on SQLSTATE 23505 first, so a message that merely mentions the index (a lock error, a
     * dump of the statement) cannot qualify — and neither can a 23503 whose name the dialect DID
     * extract, which is the half this method used to state and the code used to skip. It
     * deliberately does NOT test {@code instanceof DuplicateKeyException}: that is a product of
     * {@code SQLErrorCodeSQLExceptionTranslator}, and under JPA {@code HibernateJpaDialect}
     * translates a constraint violation into a plain {@link DataIntegrityViolationException}, so
     * the branch was unreachable — a fallback that never fires is not a fallback.
     */
    private static boolean isDuplicateInvite(DataIntegrityViolationException e) {
        String constraint = constraintNameOf(e);
        // SQLSTATE FIRST, AND FOR BOTH BRANCHES (corrected in HD-167's review, together with its
        // copy in EmailUniqueness — one gap, two spellings). The name alone is not sufficient in
        // either branch: Hibernate's PostgreSQL delegate routes 23502/23503/23514 through the SAME
        // constraint-name extractor, so a non-unique integrity violation bearing this index's name
        // would have reached the primary branch and been answered "already invited".
        boolean duplicate = isUniqueViolation(e)
                && (constraint != null
                        ? PENDING_EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)
                        : namesPendingEmailIndex(e));
        if (duplicate) {
            log.debug("Invite insert lost the pending-address race on constraint [{}]",
                    constraint != null ? constraint : PENDING_EMAIL_UNIQUE_CONSTRAINT);
        } else {
            log.warn("Invite insert failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return duplicate;
    }

    /**
     * <strong>Depth-bounded, like its two neighbours — and this is the walk that makes their
     * bounds reachable</strong>, because it runs first on every call. Why a
     * {@code t != t.getCause()} guard is not enough: see {@link #namesPendingEmailIndex}.
     * Same shape as {@code GlobalExceptionHandler.sqlStateOf}, deliberately — one idiom.
     */
    private static String constraintNameOf(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof ConstraintViolationException cve) return cve.getConstraintName();
        }
        return null;
    }

    /**
     * The locale-proof fallback: does this failure name <em>our</em> index?
     *
     * <p>The name alone is not enough — a lock timeout, a statement cancellation or any error that
     * happens to quote the failing statement would mention the index too, and answering those a
     * 409 would tell a caller "somebody else invited this address" when nobody did. That half is
     * {@link #isUniqueViolation}, asked above of <strong>both</strong> branches; it used to live
     * here, which left the primary branch matching on a bare name. What remains here is the part
     * that is genuinely this branch's: PostgreSQL quotes an identifier verbatim in every locale,
     * which is exactly why the name is the part worth matching and the surrounding words are not.
     *
     * <p><strong>Every cause-chain walk in this trio is depth-bounded</strong> — this one,
     * {@link #isUniqueViolation} and {@link #constraintNameOf} alike — for the reason
     * {@code GlobalExceptionHandler.sqlStateOf} gives and in the same shape: a {@code t !=
     * t.getCause()} guard catches a one-step self-reference and nothing else, so a two-step cause
     * cycle (A → B → A) would spin forever on a thread that is already handling a failure.
     *
     * <p><strong>A bound is worth only what the FIRST walk is worth.</strong> This paragraph used
     * to be written about this method, and was true of it — while {@code constraintNameOf}, which
     * runs first on every single call of {@link #isDuplicateInvite}, was merely
     * self-reference-guarded. The two bounds here and in {@link #isUniqueViolation} were therefore
     * unreachable for the one input they were written for: the walk spun before it reached them,
     * and this sentence described a protection the code did not have (measured 2026-08-28 — the
     * cycle wedged Surefire until the JVM was killed by hand). Stated about the group rather than
     * about a member, because that is the property that has to hold: a bound skipped on a walk
     * that runs earlier silently disarms every bound after it. Fixed together with the
     * byte-identical copy in {@code EmailUniqueness} — which is where the seal for it lives
     * ({@code EmailUniquenessTranslationTest.aTwoStepCauseCycleTerminates}) — because fixing one
     * and leaving the other is how an idiom becomes two idioms.
     */
    private static boolean namesPendingEmailIndex(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            String message = t.getMessage();
            if (message != null && message.contains(PENDING_EMAIL_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <strong>Is this a uniqueness violation at all?</strong> — asked of <em>both</em> branches of
     * {@link #isDuplicateInvite}, which is the whole point of it being a method.
     *
     * <p>SQLSTATE alone is not enough (23505 on this insert could equally be {@code token_hash});
     * the name alone is not enough either, for the two different reasons the two branches give.
     * Together they are the same decision the dialect would have made, reached without depending on
     * the server speaking English.
     */
    private static boolean isUniqueViolation(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SQLException se && SQLSTATE_UNIQUE_VIOLATION.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** PostgreSQL {@code unique_violation}. Not localised, unlike the message that carries it. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Generous enough that no real chain reaches it, small enough that a cycle cannot hang. */
    private static final int MAX_CAUSE_DEPTH = 20;
}
