package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.event.WorkspaceMemberRemoved;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.entity.IssueHistory;
import com.hamstrack.issue.repository.IssueHistoryRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.dto.ProjectRef;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.dto.UpdateWorkspaceMemberRequest;
import com.hamstrack.workspace.dto.WorkspaceMemberResponse;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.exception.CannotRemoveSelfException;
import com.hamstrack.workspace.exception.LastWorkspaceOwnerException;
import com.hamstrack.workspace.exception.OwnerIsNotGrantableException;
import com.hamstrack.workspace.exception.WorkspaceGrantCeilingException;
import com.hamstrack.workspace.exception.WorkspaceMemberNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Administration of an <em>existing</em> workspace membership (HD-132): change a member's
 * role, or remove them from the workspace. Joining is {@code WorkspaceService}'s invite
 * flow; this is everything that happens afterwards.
 *
 * <p>Before this existed the only lever on membership was an invite, and inviting someone
 * who was already a member 409'd — so a role could never be corrected and <strong>nobody
 * could ever be removed</strong>. That second half is a security gap, not just a missing
 * feature: when someone left, revoking their access to a workspace meant deleting their
 * account.
 *
 * <p><strong>What a removal actually is.</strong> A {@link User} is global — the same
 * account belongs to several workspaces — so removal is emphatically not deleting the
 * user. It is, in one transaction and scoped to this workspace only:
 * <ol>
 *   <li>unassign their issues (with an {@code assignee} history row each, so it is
 *       auditable);</li>
 *   <li>delete their {@code project_members} rows — <strong>unless that would leave a
 *       project without an administrator</strong>, in which case the whole removal is
 *       refused with a 409 naming every such project (HD-136). Dropping those rows was
 *       breaking, wholesale and silently, the invariant {@code ProjectService.removeMember}
 *       refuses to break one project at a time; and a project with no holder of
 *       {@code project.member.manage} cannot be repaired through the API by anybody,
 *       workspace Owner included, because that permission is not part of the curator
 *       bypass;</li>
 *   <li>delete every unaccepted invite for their address — otherwise removing them makes a
 *       leftover invite <em>re</em>appear as a live join button and they walk straight back
 *       in;</li>
 *   <li>delete the {@code workspace_members} row;</li>
 *   <li>after commit, close their open SSE streams — membership is only checked when a
 *       stream is opened, so otherwise revocation is false for up to 30 minutes.</li>
 * </ol>
 *
 * <p><strong>Everything else survives on purpose, and the list of exceptions is
 * deliberately short.</strong> An <em>assignee</em> earns its place on that list because it
 * is a statement about who is responsible <em>now</em>; almost nothing else is.
 * <ul>
 *   <li>{@code components.lead_id} is <strong>untouched</strong> — HD-31 §5.4 already
 *       decided this ("a lead who merely leaves the workspace keeps the row") and already
 *       handles the consequence: {@code ComponentService.autoAssignee} re-checks membership
 *       and silently skips a departed lead, so the module degrades by design rather than by
 *       accident. Clearing it would also be the only code path in the product able to
 *       produce {@code auto_assign = true} with no lead. Revoking access is not editing
 *       project configuration.</li>
 *   <li>{@code saved_filters.owner_id} is untouched, shared filters included — a shared
 *       filter resolves in the <em>viewer's</em> context, so it keeps working.</li>
 *   <li>All historical attribution — {@code reporter_id}, {@code comments.author_id},
 *       {@code issue_history.changed_by}, {@code attachments.uploaded_by} — is untouched,
 *       because who did a thing does not change because they left.</li>
 * </ul>
 *
 * <p><strong>Authorization is {@code workspace.member.manage} plus the §11.2 grant
 * ceiling</strong> (HD-126, S3). It was the {@code WorkspaceRole} ordinal ladder until
 * this slice, deliberately: HD-124 shipped the catalog dark, and writing these against a
 * permission nothing enforced yet would have looked like protection and been none. The
 * three guards below were public for exactly this moment — S3 changed what they ask, not
 * how many places ask it, so there is still one copy of the last-Owner rule.
 *
 * <p><strong>Tenancy.</strong> Both mutations resolve through
 * {@link WorkspaceAccessService#requireMember}, so an unknown workspace and a caller who
 * is not a member are one indistinguishable 404. A member without the required role gets
 * 403 — reachable only after membership is already proven. A target who is not a member of
 * <em>this</em> workspace is a 404 about the membership that says nothing about whether
 * the account exists, which also makes a repeated DELETE cleanly idempotent.
 *
 * <p><strong>Out of scope, and now enforced rather than merely documented:</strong>
 * self-removal ("leave workspace"). It needs its own UX — confirmation, where the user lands,
 * what happens when it was their only workspace — so {@code remove} refuses when target ==
 * actor ({@link CannotRemoveSelfException}, 422). Self-<em>demotion</em> on {@code updateRole}
 * stays legal: an owner stepping down while another owner exists is an ordinary handover, and
 * {@link #requireNotLastOwner} — which does not care who the actor is — already refuses the
 * case that would orphan the workspace.
 *
 * <p><strong>Both mutations bound their lock waits.</strong> Each takes {@code FOR UPDATE}
 * on the owner set — and a removal additionally on every project-administrator row in the
 * workspace, held across the unbounded {@link #writeUnassignHistory} write — so the first
 * statement of each is {@link LockTimeout#applyToCurrentTransaction()}. The locking reads
 * are ordered so overlapping transactions queue rather than deadlock, which is exactly why
 * a bound is needed: the common outcome is a wait, and PostgreSQL's default wait is
 * infinite. Exceeding it is a 409 + {@code Retry-After}, not a 500.
 *
 * <p><strong>Neither mutation is recorded in a table.</strong> There is no audit log yet (a
 * follow-up ticket), so both methods emit a structured INFO line with actor/target/workspace
 * ids and the roles involved. Ids only, never emails or names — the lines ship to Loki.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceMemberService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final ProjectMemberRepository projectMemberRepository;
    /**
     * HD-136: the last-administrator invariant, shared with {@code ProjectService} rather
     * than reimplemented — the whole bug was that only one of the two endpoints that can
     * break it enforced it.
     */
    private final ProjectAdminGuard projectAdminGuard;
    private final IssueRepository issueRepository;
    private final IssueHistoryRepository historyRepository;
    private final RoleCatalog roleCatalog;
    /**
     * HD-136 review round 4: both mutations below take {@code FOR UPDATE} row locks, and
     * PostgreSQL waits for one for ever by default. See {@link LockTimeout} — and note that
     * the ordered reads make a plain unbounded WAIT the common outcome, not the deadlock
     * the original safety argument was written about.
     */
    private final LockTimeout lockTimeout;
    /** HD-80: SSE side effects run AFTER COMMIT, via {@code SseEventListener}. */
    private final ApplicationEventPublisher events;

    // ============================================================ mutations

    /**
     * Change an existing member's workspace role.
     *
     * <p>Setting the role a member already holds is accepted as a no-op rather than
     * rejected — the SPA re-sends the current value on any partial edit, and the last-Owner
     * guard must not fire on a change that changes nothing.
     */
    @Transactional
    public WorkspaceMemberResponse updateRole(User actor, UUID workspaceId, UUID userId,
                                              UpdateWorkspaceMemberRequest req) {
        // Bound every lock wait in this transaction BEFORE anything can queue on one —
        // this method takes the owner-set lock, and a lock wait has no bound of its own.
        lockTimeout.applyToCurrentTransaction();
        // ---- reads first (the @Version rule): resolve, then judge, then mutate ----
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        // Before the lock, so an unauthorized caller never takes one.
        requireMemberAdmin(ctx.permissions());
        // 422 for a role key this build cannot assign — resolved before the lock for the
        // same reason, and before the target is read so a bad key never depends on who it
        // was aimed at.
        var requested = roleCatalog.requireAssignable(RoleScope.WORKSPACE, req.role());

        // THEN the owner-set lock, unconditionally and before the target is read — see
        // lockOwners for why deciding whether to lock from an unlocked read is the bug.
        var owners = lockOwners(ctx.workspace());

        var target = requireMembership(ctx.workspace(), userId);
        var currentRole = roleCatalog.view(target.getRole().getId());

        // The ceiling applies to BOTH ends of the edit. Only checking the new role would
        // let an ADMIN demote an OWNER — which is not "granting a role above your own",
        // but is the same privilege escalation by the other door.
        requireWithinGrantCeiling(ctx, currentRole);
        requireWithinGrantCeiling(ctx, requested);
        // "Does this edit cost the workspace an owner?" is asked of the request and the
        // LOCKED set, not of the role read above: promoting to OWNER can never remove one,
        // and anything else must not land on the last one. Self-DEMOTION is deliberately
        // allowed (unlike self-removal) — an owner stepping down while another owner exists
        // is an ordinary handover, and this guard refuses the case that would orphan the
        // workspace whoever asks.
        if (!requested.isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            requireNotLastOwner(owners, userId);
        }

        // ---- mutation, immediately before the save ----
        target.setRole(roleCatalog.reference(requested.id()));
        memberRepository.save(target);
        // The only record this mutation leaves. There is no audit table yet (a follow-up
        // ticket), and unlike a removal a role change touches no other row, so without this
        // line "who made this account an admin last Tuesday" is unanswerable after the fact.
        // Ids only — no emails or display names, so the line is safe to ship to Loki.
        log.info("workspace.member.role_changed workspace={} actor={} target={} from={} to={}",
                ctx.workspace().getId(), actor.getId(), userId, currentRole.key(), requested.key());
        return WorkspaceMemberResponse.of(target, requested.key());
    }

    /**
     * Remove a member from the workspace and clean up everything that must not outlive their
     * access. See the class javadoc for what is deliberately left alone.
     *
     * @param adoptStrandedProjects the caller's explicit answer to a previous 409: make
     *        <em>me</em> the administrator of every project this removal would otherwise
     *        strand, and then remove them. False on a first attempt; see the
     *        {@code project.adopted} branch below and {@code ProjectAdminGuard.adoptAll}
     *        for why the flag carries no project list.
     */
    @Transactional
    public List<ProjectRef> remove(User actor, UUID workspaceId, UUID userId,
                                   boolean adoptStrandedProjects) {
        // FIRST statement, before any read and long before either lock: this transaction
        // takes two lock sets and then holds both across writeUnassignHistory, whose size
        // is the departing member's assigned work. Two overlapping removals therefore queue
        // (the reads are ordered so they do not cycle) — and an unbounded queue on a pooled
        // connection is how one tenant's offboarding takes the instance down for everyone.
        lockTimeout.applyToCurrentTransaction();
        // ---- reads first ----
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        var workspace = ctx.workspace();
        // Before the lock, so an unauthorized caller never takes one.
        requireMemberAdmin(ctx.permissions());

        // THEN the owner-set lock, unconditionally and before the target is read — see
        // lockOwners for why deciding whether to lock from an unlocked read is the bug.
        var owners = lockOwners(workspace);

        var target = requireMembership(workspace, userId);
        var targetUser = target.getUser();
        var targetRole = roleCatalog.view(target.getRole().getId());
        requireWithinGrantCeiling(ctx, targetRole);
        // Deliberately BEFORE the self-check: a sole owner deleting themselves trips both
        // rules, and "promote another member to owner first" is the more actionable of the
        // two answers — it is also the answer a future leave-workspace endpoint must give
        // them, whereas "use the other endpoint" would send them somewhere that will refuse.
        requireNotLastOwner(owners, userId);
        if (targetUser.getId().equals(actor.getId())) {
            throw new CannotRemoveSelfException();
        }

        // ---- the project-administrator lock, as late as the rule allows (HD-136) ----
        // Deliberately here and not beside lockOwners: this read locks every project-admin
        // row in the WORKSPACE, so taking it earlier would let any member-manager freeze
        // them all on a request that ends in 404 (an unknown target) or 403, and would
        // serialise two removals of two unrelated people workspace-wide. It still precedes
        // the only read it protects, because that read IS this statement.
        var stranded = projectAdminGuard.lockStrandedProjects(workspace, userId);
        // Last of the guards, because it is the only one the ACTOR cannot fix by choosing a
        // different target: a permission or ceiling failure is about who is asking, and this
        // is about what the workspace would be left with.
        //
        // Two answers, and the caller picks which one they asked for. Without the flag it is
        // a 409 naming every affected project, so the fix is a list to work through rather
        // than a hunt. With it, the actor adopts those projects here and now — because the
        // 409's own remedy ("give each another administrator") needs a permission the
        // workspace Owner reading it does not have, and a refusal only its own target can
        // satisfy is a lock-out, not a guard (security review H1).
        List<ProjectRef> adopted = List.of();
        if (adoptStrandedProjects) {
            adopted = projectAdminGuard.adoptAll(actor, workspace, stranded);
        } else {
            projectAdminGuard.requireNothingStranded(stranded);
        }

        // The issues to unassign are read as scalar (id, number) refs BEFORE the bulk
        // update — materializing them would leave stale managed copies that write the
        // departed assignee back on the next flush (the documented bulk-UPDATE desync).
        var assignedRefs = issueRepository.findAssignedRefsInWorkspace(workspace, targetUser);

        // ---- mutations ----
        issueRepository.unassignAllInWorkspace(workspace, targetUser);
        projectMemberRepository.deleteAllByUserInWorkspace(targetUser, workspace);
        // Revoking access has to include the ways back IN: an unaccepted invite left in this
        // workspace re-appears as a live join button the moment the membership row is gone.
        inviteRepository.deleteUnacceptedByWorkspaceAndEmail(workspace, targetUser.getEmail());
        memberRepository.delete(target);
        writeUnassignHistory(assignedRefs, actor, targetUser.getDisplayName());

        // Close their live SSE streams — but only once this transaction has actually
        // committed, hence a domain event rather than a direct registry call (§HD-80).
        events.publishEvent(new WorkspaceMemberRemoved(workspace.getId(), targetUser.getId()));

        // An adoption is a privilege change, and the one part of this transaction that is
        // NOT a revocation — so it gets its own line rather than a field on the removal's,
        // one per project, ids only. Without it "how did the workspace admin become
        // administrator of that project?" has no answer anywhere (HD-136 / H1).
        for (var project : adopted) {
            log.info("project.admin_adopted workspace={} project={} actor={} reason=member_removal target={}",
                    workspace.getId(), project.id(), actor.getId(), userId);
        }

        // Removals leave only an indirect trace today (unassign history rows), and none at
        // all when the member held no assigned work — so this is the only place that can
        // answer "who removed this person, and when". Ids only, safe for Loki.
        log.info("workspace.member.removed workspace={} actor={} target={} role={} unassignedIssues={} "
                 + "adoptedProjects={}",
                workspace.getId(), actor.getId(), userId, targetRole.key(), assignedRefs.size(),
                adopted.size());
        // Returned, not just logged: a grant the actor cannot see is a side effect, not
        // consent — the controller answers 200 with this list when it is non-empty.
        return adopted;
    }

    // ============================================================ guards
    //
    // Public and separately callable on purpose: HD-126 (S3) re-expressed each of these
    // against the permission model, and the one thing that must not happen is a second
    // implementation of the last-Owner rule living next to this one and disagreeing with
    // it. There is one copy; S3 changed what it asks, not how many places ask it.

    /**
     * May this caller administer membership at all? <strong>{@code workspace.member.manage}</strong>
     * (§10.1) — the same gate {@code WorkspaceService.inviteMember} applies, which is why
     * that method calls this rather than repeating it. Δ-free: the built-in Owner and
     * Admin hold it and Member does not, which is exactly the {@code isAtLeast(ADMIN)}
     * this replaced.
     */
    public void requireMemberAdmin(PermissionSet actorPermissions) {
        actorPermissions.require(Permission.WORKSPACE_MEMBER_MANAGE);
    }

    /**
     * The grant ceiling (roles-permissions-proposal §11.2): nobody may hand out — or act
     * on a member holding — a role that grants something they do not hold themselves.
     *
     * <p><strong>Expressed on permission SETS, like the project twin</strong>
     * ({@code ProjectService.requireWithinGrantCeiling}), because the ordinal ladder this
     * used to compare no longer exists and a custom role has no ordinal.
     * {@link PermissionSet#firstNotCovered} compares width per grant, so an own-only
     * holder can never mint an unrestricted one.
     *
     * <p><strong>Plus one rule sets cannot express: the built-in Owner.</strong>
     * Owner and Admin are seeded with <em>identical</em> permission sets on purpose (V13:
     * "Owner is a guardrail on assignment, not a bigger role"), so set containment alone
     * would let an Admin demote or remove an Owner — a real widening over
     * {@code isAtLeast}, and the escalation §11.1 exists to prevent, since the demoted
     * Owner cannot be restored by anyone but an Owner. So handing out or acting on the
     * built-in Owner role additionally requires <em>being</em> a built-in Owner. It keys
     * on the role id, not the key string: once S4 ships the editor a workspace may own a
     * custom role keyed {@code OWNER}, and that role is not this guardrail.
     *
     * <p>Note the one asymmetry with the invite path, which additionally refuses Owner
     * outright even for an Owner: you do not <em>invite</em> a stranger as an owner, but
     * you do promote a colleague to one. That extra rule stays where it belongs, at the
     * invite call site.
     */
    public void requireWithinGrantCeiling(WorkspaceContext ctx, RoleView role) {
        ctx.permissions().firstNotCovered(role.permissions()).ifPresent(missing -> {
            throw new WorkspaceGrantCeilingException(role.name(), missing);
        });
        if (role.isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)
                && !ctx.workspaceRole().isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            throw new OwnerIsNotGrantableException();
        }
    }

    /**
     * The workspace's owner set, read under a row lock — <strong>the first thing either
     * mutation does, unconditionally</strong>, before the target membership is even read.
     *
     * <p><strong>Why unconditionally, when only owners can trip the guard.</strong> An
     * earlier version locked lazily: it decided whether to lock from the target's role as
     * read a moment earlier, unlocked. That decision is itself a stale read, and
     * {@code WorkspaceMember} has no {@code @Version} to catch it. The interleaving that
     * breaks it needs three actors and no locks at all: T2 reads A as ADMIN and so skips
     * locking; T1 promotes A to OWNER and commits (also lock-free — A was not an owner when
     * T1 looked); T3 removes the other owner, locks {O, A}, counts 2, passes; T2 then flushes
     * A down to MEMBER. Zero owners. Deciding whether to lock by reading the very thing the
     * lock protects is not a race you can shrink — it has to go.
     *
     * <p>Taking the lock first instead makes the owner set the <em>single ordered resource</em>
     * every membership mutation contends on, which is also why it stays deadlock-free: both
     * methods acquire exactly this one lock, in {@code ORDER BY id} order, before any other
     * row lock. <strong>Do not</strong> "fix" the same staleness by adding
     * {@code @Lock(PESSIMISTIC_WRITE)} to {@code findByWorkspaceAndUserId} — that locks the
     * target row first and inverts the order against this one, which is how two concurrent
     * owner removals deadlock instead of queueing.
     *
     * <p>It buys a second guarantee for free: while this lock is held no other membership
     * mutation can commit, so the target row read <em>after</em> it — and therefore the grant
     * ceiling's view of the target's current role — cannot be invalidated mid-request either.
     * (Invite acceptance inserts a membership without this lock, but it can only ever add a
     * member and can never grant OWNER, so it cannot threaten the invariant.)
     *
     * <p><strong>The cost is deliberate</strong>: a handful of row locks on operations that
     * are rare by nature — a human changing somebody's role or offboarding them. It is not a
     * hot path and it must not be optimised back into a conditional lock.
     *
     * <p>Keyed by the built-in Owner role <em>id</em>, not by a role name, which is what
     * let it outlive S3's deletion of the role enum. A workspace with no owners at all (only reachable
     * from pre-existing bad data) locks nothing and serialises nothing — it is already in the
     * state this guard exists to prevent, and cannot be pushed further into it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedOwners lockOwners(Workspace workspace) {
        // INVARIANT, convention-only (HD-136 review round 6): every transaction that calls
        // this must ALREADY have called LockTimeout.applyToCurrentTransaction(). Nothing
        // here enforces it — this method is public and MANDATORY, so a future caller can
        // take the lock with no bound on the wait, and PostgreSQL then waits for ever. The
        // omission compiles, passes review and stays invisible until a production pile-up
        // exhausts the pool. Keep the two paired: bound first, then lock.
        //
        // The annotation above cannot fire for the two calls in THIS class — they are
        // self-invocations and do not pass through the proxy — so the invariant is also
        // asserted directly. Both matter: the annotation refuses an external caller who has
        // no transaction, this refuses a future method here that forgets @Transactional.
        // Without either, such a caller gets a transaction that commits on return and
        // releases every FOR UPDATE before requireNotLastOwner reads the result, which is
        // the unlocked read this whole shape exists to delete (HD-132, third time).
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "lockOwners must run inside the caller's transaction — a lock released "
                + "before the guard that reads it is not a lock");
        var rows = memberRepository.lockAllByWorkspaceAndRoleId(workspace, BuiltInRoles.WORKSPACE_OWNER);
        var userIds = new HashSet<UUID>(rows.size());
        // getUser() is an uninitialised proxy and reading its id does not load the row —
        // the same property WorkspaceAccessService.projectContext relies on.
        rows.forEach(m -> userIds.add(m.getUser().getId()));
        return new LockedOwners(Set.copyOf(userIds));
    }

    /**
     * The workspace's owners as of a {@link #lockOwners} call, with those rows locked for the
     * rest of the transaction.
     *
     * <p>A type rather than a bare {@code Set} so the guard below cannot be handed an
     * <em>unlocked</em> answer: the only way to obtain one is {@link #lockOwners}, which
     * always takes the lock. That is the whole safety property, and a {@code List<UUID>}
     * parameter would have made it a convention.
     */
    public record LockedOwners(Set<UUID> userIds) {
        public boolean includes(UUID userId) {
            return userIds.contains(userId);
        }

        public int size() {
            return userIds.size();
        }
    }

    /**
     * A workspace must never lose its last OWNER — by demotion or by removal, and whether
     * the actor is administering someone else or themselves. 409, because the caller's
     * permissions are fine; the workspace's invariant is what refuses.
     *
     * <p>"Is the target an owner?" is answered from the <strong>locked</strong> set, never
     * from a {@code role_id} read before it. That is the point of the two-step: the earlier
     * read can be stale, the locked set cannot.
     */
    public void requireNotLastOwner(LockedOwners owners, UUID targetUserId) {
        if (!owners.includes(targetUserId)) {
            return;
        }
        if (owners.size() <= 1) {
            throw new LastWorkspaceOwnerException();
        }
    }

    // ============================================================ helpers

    private WorkspaceMember requireMembership(Workspace workspace, UUID userId) {
        return memberRepository.findByWorkspaceAndUserId(workspace, userId)
                .orElseThrow(WorkspaceMemberNotFoundException::new);
    }

    /**
     * One {@code assignee} history row per unassigned issue — the same field name, the same
     * display-name values and the same shape {@code IssueService} writes for a single edit,
     * so the issue's activity feed renders it without knowing a removal caused it.
     *
     * <p>Built against {@code getReferenceById} PROXIES, never loaded entities: the bulk
     * UPDATE has already nulled {@code assignee_id} in the database, so materializing the
     * issues here would put stale managed copies in the persistence context. An
     * uninitialized proxy is only ever dereferenced for its id, which the FK insert needs
     * and which we already have. Written in one {@code saveAll}, i.e. one batched insert
     * set rather than N round trips.
     *
     * <p><strong>Unbounded, knowingly.</strong> The ref list and this row list are both sized
     * by however many issues the departing member was assigned, so offboarding someone who
     * held tens of thousands would build two large in-memory lists and one very large insert
     * batch. Left as-is deliberately: it is self-inflicted, single-tenant (the cascade is
     * workspace-scoped, so it cannot be driven by another customer) and bounded by a rare
     * human action — a throughput ticket, not a security one. Whoever picks it up: chunk the
     * refs and interleave the history writes with the bulk update, the way
     * {@code UsageCounts.rowsIn} chunks its {@code IN} lists.
     */
    private void writeUnassignHistory(List<Object[]> refs, User actor, String removedName) {
        if (refs.isEmpty()) return;
        var rows = new ArrayList<IssueHistory>(refs.size());
        for (var ref : refs) {
            var h = new IssueHistory();
            h.setIssue(issueRepository.getReferenceById((UUID) ref[0]));
            h.setChangedBy(actor);
            h.setField("assignee");
            h.setOldValue(removedName);
            h.setNewValue(null);
            rows.add(h);
        }
        historyRepository.saveAll(rows);
    }
}
