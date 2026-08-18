package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.RolesProperties;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.exception.StrandedProjectsException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.dto.DuplicateRoleRequest;
import com.hamstrack.workspace.dto.PreviewRoleRequest;
import com.hamstrack.workspace.dto.RoleAssignmentPreviewResponse;
import com.hamstrack.workspace.dto.RoleAssignmentView;
import com.hamstrack.workspace.dto.RoleBlocker;
import com.hamstrack.workspace.dto.RolePermissionEntry;
import com.hamstrack.workspace.dto.RoleRef;
import com.hamstrack.workspace.dto.RoleResponse;
import com.hamstrack.workspace.dto.RoleUsageResponse;
import com.hamstrack.workspace.dto.UpdateRoleRequest;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.event.RolePermissionsChanged;
import com.hamstrack.workspace.exception.BuiltInRoleNotEditableException;
import com.hamstrack.workspace.exception.OwnerIsNotGrantableException;
import com.hamstrack.workspace.exception.RoleInUseException;
import com.hamstrack.workspace.exception.RoleLimitReachedException;
import com.hamstrack.workspace.exception.RoleNameConflictException;
import com.hamstrack.workspace.exception.RoleReassignWidensException;
import com.hamstrack.workspace.exception.RoleVersionConflictException;
import com.hamstrack.workspace.exception.SelfHeldRoleException;
import com.hamstrack.workspace.exception.UnknownPermissionException;
import com.hamstrack.workspace.exception.UnknownRoleException;
import com.hamstrack.workspace.exception.WorkspaceGrantCeilingException;
import com.hamstrack.workspace.repository.RoleRepository;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>The custom-role editor</strong> (HD-127, S4) — duplicate, edit, delete-with-reassign,
 * usage and the derived assignment preview.
 *
 * <h2>Creation is duplicate-only, on purpose</h2>
 *
 * <p>There is no bare {@code POST /roles}, and that is product posture rather than an
 * oversight. The grant ceiling is a <strong>subset</strong> rule and not a ladder, so a role
 * composed from an empty checklist can assign nobody — not Contributor, not Commenter, not
 * even Viewer-plus-anything — and discovering that as a 403 six weeks later is the precise
 * Jira complaint this epic was built against. Duplication always starts from a superset.
 * "From scratch" stays reachable by duplicating <strong>Viewer</strong>, which grants
 * nothing; that is a named, deliberate act rather than the default.
 *
 * <h2>Tenancy</h2>
 *
 * <p>Every method resolves through {@link WorkspaceAccessService#requireMember} first, so an
 * unknown workspace and a non-member are one indistinguishable 404 and a 403 is reachable
 * only by a proven member. Beyond that, two different questions about a role id, answered
 * with two different codes on purpose:
 * <ul>
 *   <li>a role id in the <strong>path</strong> is an <em>address</em> →
 *       {@link RoleCatalog#requireAddressable}, <strong>404</strong>;</li>
 *   <li>a role id in the <strong>body</strong> is a <em>value</em> →
 *       {@link RoleCatalog#requireAssignable}, <strong>422</strong>, one indistinguishable
 *       answer for foreign, wrong-scope and nonsense.</li>
 * </ul>
 * Unifying them would make the pair an oracle for whether a role exists in another tenant.
 *
 * <p>Every usage count and every bulk reassign carries the workspace id. Built-in roles are
 * <em>shared</em> rows ({@code workspace_id IS NULL}), so an unscoped
 * {@code COUNT(*) WHERE role_id = …} would publish another tenant's headcount from a
 * workspace-scoped endpoint — the single most likely tenancy defect in this slice.
 *
 * <h2>The definition ceiling</h2>
 *
 * <p>Distinct from the <em>assignment</em> ceiling and narrower: see
 * {@link #requireWithinDefinitionCeiling}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final WorkspaceAccessService workspaceAccess;
    private final RoleCatalog roleCatalog;
    private final RoleRepository roleRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    /** Doors 4 and 5 of the stranding enumeration — the bulk ones (§6). */
    private final ProjectAdminGuard projectAdminGuard;
    /**
     * <strong>For its grant ceiling, not for membership.</strong> The WORKSPACE half of
     * {@link #requireReassignWithinCeiling} calls
     * {@code WorkspaceMemberService.requireWithinGrantCeiling} — the very predicate
     * {@code updateRole} and {@code WorkspaceService.inviteMember} apply — rather than
     * re-deriving it, so the Owner exemption and {@code OwnerIsNotGrantableException} come
     * for free and cannot drift. Re-implementing it here is how the reassign path acquired
     * no ceiling at all in round 1.
     */
    private final WorkspaceMemberService workspaceMembers;
    private final RolesProperties properties;
    /**
     * Bound first, then lock — applied as the first statement of <em>every</em> write in this
     * service, not only {@link #duplicate}, which is the one that takes a lock explicitly.
     */
    private final LockTimeout lockTimeout;
    /** Cache eviction happens AFTER COMMIT; see {@code RoleCacheEvictionListener}. */
    private final ApplicationEventPublisher events;

    // ============================================================ reads

    /**
     * Built-ins plus this workspace's own, one query.
     *
     * <p><strong>Open to every member, and the decision is about the WHOLE payload</strong>
     * (§13 OQ 1; restated in round 3, where the original wording justified it on
     * <em>names</em> and was therefore an answer to a question nobody asked). What a member
     * actually receives is every role's full permission list plus the derived
     * {@code canAssign}/{@code cannotAssign} graph — i.e. the workspace's entire
     * authorization policy. That is accepted, deliberately, on three grounds:
     * <ul>
     *   <li><strong>the graph is the product.</strong> Its whole purpose is a role picker
     *       that can grey out a role and say <em>why</em>, which is the Jira complaint this
     *       epic exists to delete. Withholding it turns "unavailable, because it grants
     *       {@code issue.delete}" back into a 403 discovered at Save time;</li>
     *   <li><strong>the audience cannot be identified from here.</strong> The obvious gate —
     *       {@code workspace.role.manage} — is the wrong predicate: a project lead holding
     *       {@code project.member.manage} in one project legitimately needs the graph and
     *       holds no workspace-level permission at all, and a workspace-scoped context
     *       cannot see a per-project grant without a query per request. Gating on the
     *       available predicate would break the picker for exactly the people it is for;</li>
     *   <li><strong>it is policy, not credentials.</strong> Nothing here is enforcement-
     *       relevant: every gate is checked server-side against the caller's own set, so
     *       knowing that "Release manager" grants {@code version.manage} tells a member what
     *       they may ask for, not how to take it. It is tenant-scoped (built-ins plus this
     *       workspace's own rows, never another tenant's), and the genuinely sensitive half —
     *       {@code includeUsage}, i.e. who holds what — <em>is</em> gated on
     *       {@code workspace.role.manage}.</li>
     * </ul>
     * The residual is honest and worth writing down: a member of the workspace can enumerate
     * its authorization policy. That is insider reconnaissance, tenant-bounded, exploitable
     * only in combination with a separate defect. If it ever has to change, the correct shape
     * is <em>not</em> a permission gate but an opt-in projection
     * ({@code includeAssignment=false} for callers that only want names), so the picker keeps
     * working for the non-administrator who needs it.
     *
     * <p><strong>The derived {@code assignment} block makes this quadratic, and what bounds
     * it is the sprawl cap</strong> — which is why {@link #duplicate} takes a row lock to
     * make that cap exact (round-2 review). Every ordered pair of same-scope roles is
     * compared, so the work is {@code (built-ins + custom)²} bit tests over an {@code EnumSet}
     * of 29, with no query per pair: at the default cap of 50 that is ~3k comparisons, which
     * is noise on an endpoint every member hits on page load. It is <em>not</em> noise if the
     * cap is advisory, and an operator who raises {@code app.roles.max-custom-per-workspace}
     * towards its 500 ceiling is choosing a hundredfold more of it — the property's javadoc
     * says so. If this ever needs to grow past that, make the block opt-in
     * ({@code includeAssignment=true}) rather than approximating the containment rule.
     *
     * <p>"Quadratic" is a claim about the code, and in round 2 the code was <strong>cubic
     * </strong>: {@link #assignmentOf} re-scanned {@code peers} once per {@code canAssign}
     * entry to compute one boolean, with a {@code UUID.equals} list walk as the inner
     * operation, eagerly, for every role. At 50 roles the conclusion survived; at 500 it was
     * seconds of CPU per request. The flag is now tracked in the loop that already holds the
     * peer, which is what makes the paragraph above true rather than aspirational — do not
     * reintroduce a scan inside this method's loops.
     *
     * <p><strong>403</strong> only when {@code includeUsage=true} without
     * {@code workspace.role.manage} · <strong>404</strong> unknown workspace or non-member.
     */
    @Transactional(readOnly = true)
    public List<RoleResponse> list(User actor, UUID workspaceId, RoleScope scope, boolean includeUsage) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        if (includeUsage) {
            ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        }
        var workspace = ctx.workspace();
        var scopes = scope == null ? EnumSet.allOf(RoleScope.class) : EnumSet.of(scope);
        var roles = roleRepository.findAvailableWithPermissions(workspace.getId(), scopes);
        // ONE snapshot per role, built from the already-fetched grants and reused for both
        // the response and the O(n^2) assignment comparison. Rebuilding it per use would be
        // free of queries but not of work; more importantly, the JOIN FETCH above is what
        // keeps this at ONE statement for 50 roles rather than 51.
        var views = new LinkedHashMap<UUID, RoleView>();
        var byScope = new EnumMap<RoleScope, List<RoleView>>(RoleScope.class);
        for (var role : roles) {
            var view = RoleView.of(role);
            views.put(role.getId(), view);
            byScope.computeIfAbsent(role.getScope(), k -> new ArrayList<>()).add(view);
        }
        var usage = includeUsage ? usageForAll(workspace) : Map.<UUID, RoleUsageResponse>of();
        return roles.stream()
                .map(role -> {
                    var view = views.get(role.getId());
                    // Absent from the grouped counts means "in play nowhere", not "unknown"
                    // — so it is a zeroed object, matching what GET /roles/{id}/usage
                    // answers for the same role. A null here would be a second, quieter
                    // answer to a question one endpoint already answers (docs review,
                    // round 2).
                    return RoleResponse.of(role, view.permissions(),
                            assignmentOf(view, byScope.get(role.getScope())),
                            includeUsage
                                    ? usage.getOrDefault(role.getId(),
                                            RoleUsageResponse.unused(role.getId()))
                                    : null);
                })
                .toList();
    }

    /**
     * One role, with the same derived assignment block {@link #list} carries.
     *
     * <p>Open to every member, for {@link #list}'s reasons — a single role is a subset of
     * what that already returns, and gating one but not the other would be a rule with no
     * argument. <strong>404</strong> for an unknown workspace, a non-member, or a role this
     * workspace cannot address; the three are indistinguishable, deliberately. No usage
     * payload here, ever: that is {@link #usage}, which is gated.
     */
    @Transactional(readOnly = true)
    public RoleResponse get(User actor, UUID workspaceId, UUID roleId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        var role = roleCatalog.requireAddressable(ctx.workspace().getId(), roleId);
        return respond(ctx.workspace(), role, null);
    }

    /**
     * Who holds this role — the sensitive half of {@link #list}, and the reason
     * {@code includeUsage} is gated there too.
     *
     * <p><strong>403</strong> without {@code workspace.role.manage} · <strong>404</strong>
     * unknown workspace, non-member, or a role this workspace cannot address. Answers for a
     * built-in as readily as for a custom role: "how many people hold Admin" is the question
     * the delete and reassign dialogs are built on.
     */
    @Transactional(readOnly = true)
    public RoleUsageResponse usage(User actor, UUID workspaceId, UUID roleId) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        var role = roleCatalog.requireAddressable(ctx.workspace().getId(), roleId);
        return usageOf(ctx.workspace(), role.getId());
    }

    /**
     * The derived assignment block for a permission list that does not exist yet —
     * <strong>persists nothing</strong>.
     *
     * <p>Runs the same validation a real write does (§10 A22), which is half its value: the
     * editor sees the same 422 for an unknown key, a wrong-scope permission or an illegal
     * {@code ownOnly} that Save would produce, rather than discovering it at Save time.
     *
     * <p><strong>403</strong> without {@code workspace.role.manage} · <strong>404</strong>
     * unknown workspace or non-member · <strong>422</strong> from {@link #validate}. No
     * ceiling check: nothing is stored, and the ceiling belongs on the write that would
     * store it.
     */
    @Transactional(readOnly = true)
    public RoleAssignmentPreviewResponse preview(User actor, UUID workspaceId, PreviewRoleRequest req) {
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        var permissions = PermissionSet.of(validate(req.permissions(), req.scope()));
        var peers = peers(ctx.workspace(), req.scope());
        // No role id yet, so nothing is excluded from the comparison — which is correct: a
        // role being composed is not yet one of the roles it could assign.
        return new RoleAssignmentPreviewResponse(
                assignmentOf(null, req.scope(), permissions, peers));
    }

    // ============================================================ writes

    /**
     * <strong>The only way to create a role</strong> (§7.1 R3).
     *
     * <p>Order is load-bearing: the workspace-wide limit is counted <em>first</em>, before any
     * work is done or any name is judged, so a workspace at its cap gets one cheap 409 rather
     * than a validation tour. The source is resolved by the <em>path</em> finder, so a
     * foreign or unknown source is 404 — it is being addressed as a resource, not supplied as
     * a value.
     *
     * <p><strong>The count is taken under a row lock on the workspace</strong>
     * ({@code WorkspaceRepository.lockForRoleCount}) — the security review's round-2
     * finding. An unlocked count-then-insert makes the cap advisory: every concurrent
     * request that observes a count below it commits, so the overshoot is the caller's
     * achievable concurrency (~200 on a default Tomcat pool), not the "51 roles" the spec
     * assumed. That matters because the count bounds a cost somebody else pays —
     * {@link #list} compares every ordered pair of same-scope roles and is open to every
     * member. Serialising a rare human action costs nothing, one lock taken first cannot
     * cycle, and the wait is bounded before it.
     *
     * <p>The mode is {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: see the repository
     * method's javadoc for why the stronger one froze issue creation across the whole
     * workspace while a duplication held it.
     *
     * <p><strong>What it refuses, and where each refusal lives</strong> — the list
     * {@code RoleController} points at rather than restating:
     * <ul>
     *   <li><strong>403</strong> — {@code workspace.role.manage}, or the definition ceiling
     *       on the copied permission set ({@link #requireWithinDefinitionCeiling}; WORKSPACE
     *       scope only, Owner exempt);</li>
     *   <li><strong>404</strong> — unknown workspace, non-member, or an unaddressable
     *       source, which is a <em>path</em> id;</li>
     *   <li><strong>409</strong> — a name conflict ({@link #requireNameAvailable}),
     *       {@code ROLE_LIMIT_REACHED} ({@code app.roles.max-custom-per-workspace}), or a
     *       lock-wait timeout with {@code Retry-After}. Only the limit carries an
     *       {@code errorType}; the timeout is separable by its header.</li>
     * </ul>
     */
    @Transactional
    public RoleResponse duplicate(User actor, UUID workspaceId, UUID sourceRoleId,
                                  DuplicateRoleRequest req) {
        // FIRST statement, before anything can queue on the lock taken below — PostgreSQL's
        // own wait is infinite, and an unbounded queue on a pooled connection is the
        // failure mode this project has already paid for twice (HD-132, HD-136).
        lockTimeout.applyToCurrentTransaction();
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        var workspace = ctx.workspace();
        // ---- reads and judgements first (the @Version rule) ----
        // The lock precedes the count it protects and follows the authorization check, so
        // an unauthorized caller never takes one.
        lockForRoleCount(workspace);
        var existing = roleRepository.countByWorkspaceIdAndBuiltInFalse(workspace.getId());
        if (existing >= properties.maxCustomPerWorkspace()) {
            throw new RoleLimitReachedException(properties.maxCustomPerWorkspace());
        }
        var source = roleCatalog.requireAddressable(workspace.getId(), sourceRoleId);
        var scope = source.getScope();
        var name = req.name() == null || req.name().isBlank()
                ? source.getName() + " copy"
                : req.name().trim();
        requireNameAvailable(workspace, scope, name, null);
        var grants = copyGrantsOf(source);
        requireWithinDefinitionCeiling(ctx, scope, PermissionSet.of(grants), name);

        // ---- mutation, immediately before the save ----
        var role = new Role();
        role.setWorkspaceId(workspace.getId());
        role.setScope(scope);
        role.setBuiltIn(false);
        role.setName(name);
        role.setDescription(req.description() != null ? req.description() : source.getDescription());
        role.setKey(generateKey(workspace.getId(), scope, name));
        var maxPosition = roleRepository.maxPosition(workspace.getId(), scope);
        role.setPosition((short) (maxPosition == null ? 0 : maxPosition + 1));
        for (var g : grants) {
            role.getPermissions().add(new RolePermission(g.permission(), g.ownOnly()));
        }
        roleRepository.save(role);
        log.info("role.duplicated workspace={} actor={} source={} role={} scope={}",
                workspace.getId(), actor.getId(), sourceRoleId, role.getId(), scope);
        // No eviction: a brand-new id was never cached.
        return respond(workspace, role, null);
    }

    /**
     * Rename, re-describe or re-compose a custom role (§7.1 R4).
     *
     * <p><strong>What it refuses, and where each refusal lives.</strong> This list is the one
     * {@code RoleController} points at rather than restating — it is maintained here, beside
     * the guards, because that is the file somebody editing a guard already has open:
     * <ul>
     *   <li><strong>403</strong> — {@code workspace.role.manage}; the definition ceiling
     *       ({@link #requireWithinDefinitionCeiling}) on <em>both</em> ends of the edit, the
     *       current role's grants included, so it applies to a name-only PATCH as well as one
     *       carrying {@code permissions} (WORKSPACE scope only, Owner exempt);</li>
     *   <li><strong>404</strong> — unknown workspace, non-member, or a role this workspace
     *       cannot address;</li>
     *   <li><strong>409</strong> — built-in ({@link #requireCustom}), stale {@code version},
     *       a name conflict ({@link #requireNameAvailable}), {@code SELF_HELD_ROLE}
     *       ({@link #requireNotSelfWidening}), {@code LAST_PROJECT_ADMIN_BULK}
     *       ({@link #requireNothingBulkStranded}), or a lock-wait timeout with
     *       {@code Retry-After};</li>
     *   <li><strong>422</strong> — the permission validation of {@link #validate}.</li>
     * </ul>
     */
    @Transactional
    public RoleResponse update(User actor, UUID workspaceId, UUID roleId, UpdateRoleRequest req) {
        // Bound first, then lock — the standing rule (HD-136). This path takes no row lock of
        // its own today, but it is a write inside the same tables the locking paths hold, so
        // it can queue behind one; and the bound has to be the FIRST statement or it bounds
        // nothing. Applying it uniformly is what keeps "which transactions are bounded" from
        // being a list somebody has to maintain.
        lockTimeout.applyToCurrentTransaction();
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        var workspace = ctx.workspace();
        // ---- reads and judgements first (the @Version rule) ----
        var role = roleCatalog.requireAddressable(workspace.getId(), roleId);
        requireCustom(role);
        if (req.version() != null && req.version() != role.getVersion()) {
            throw new RoleVersionConflictException();
        }
        // The role's OWN grants, off the managed entity — never roleCatalog.view(), which
        // answers from a 10 s per-process cache. Three guards below read this as their
        // precondition, and a guard that decides whether to run from a stale cache is
        // advisory about itself: on a node that did not serve the previous edit, a role
        // that gained project.member.manage moments ago reads as "never had it" and the
        // stranding aggregate is skipped entirely.
        var current = permissionsOf(role);
        // BOTH ends of the edit, exactly as WorkspaceMemberService.updateRole spells out for
        // a membership change: bounding only the incoming set would let an Admin STRIP a
        // role that grants more than they hold — sabotage rather than escalation, and
        // irreversible by the actor, because putting the grants back fails the very ceiling
        // that let them go. The membership endpoint refuses to demote such a holder; without
        // this the role editor was the same demotion by another door (round-3 review).
        //
        // UNCONDITIONAL, and not inside the `grants != null` branch below (round-3, second
        // pass). A rename is not a permission change, but it is the same sabotage one size
        // smaller: a role's NAME is what an administrator picks it by, so renaming "Deputy"
        // to "Intern" leaves the next admin handing out something they believe they
        // understand. Whatever an actor may not strip, they may not relabel either — and
        // `delete` is checked unconditionally, so anything less here is an asymmetry with no
        // argument behind it. PROJECT scope stays dropped inside the guard, per §11.3.
        requireWithinDefinitionCeiling(ctx, role.getScope(), current, role.getName());
        var name = req.name() == null || req.name().isBlank() ? null : req.name().trim();
        if (name != null) {
            requireNameAvailable(workspace, role.getScope(), name, role);
        }
        List<PermissionSet.Grant> grants =
                req.permissions() == null ? null : validate(req.permissions(), role.getScope());
        // The audit line's payload, read while we are still in the reads section: counting
        // holders after mutating `role` would make Hibernate AUTO-flush the entity before
        // the save, which is the documented way to bump @Version by more than one.
        List<String> added = List.of();
        long holders = 0;
        if (grants != null) {
            var next = PermissionSet.of(grants);
            // The other end, `current` having been bounded above before anything about this
            // request was judged: the incoming set must be within the actor's own too.
            requireWithinDefinitionCeiling(ctx, role.getScope(), next,
                    name != null ? name : role.getName());
            requireNotSelfWidening(workspace, actor, role, current, next);
            requireNothingBulkStranded(workspace, role, current, next,
                    "Removing project.member.manage from this role",
                    "Keep that permission on the role, or give each of those projects "
                    + "another administrator first.");
            added = current.allNotCovered(next);
            if (!added.isEmpty()) {
                holders = holderCount(workspace, role);
            }
        }

        // ---- mutation, immediately before the save ----
        if (name != null) role.setName(name);
        if (req.description() != null) role.setDescription(req.description());
        if (grants != null) {
            // Full replacement, and it has to be. RolePermission equality is on the
            // permission alone, so add()ing a key the role already holds is a silent no-op
            // and an ownOnly toggle would simply not persist. This is NOT the "deleteAllBy…
            // + flush() + re-insert" recipe the four admin set editors need: `permissions`
            // is an @ElementCollection with no child entity and no child repository, and
            // Hibernate's ActionQueue executes collection removals BEFORE creations, so a
            // re-inserted (role_id, permission) cannot collide with the old row. See the
            // Role entity javadoc, which spells this out at length — do not "improve" it.
            role.getPermissions().clear();
            for (var g : grants) {
                role.getPermissions().add(new RolePermission(g.permission(), g.ownOnly()));
            }
        }
        roleRepository.save(role);
        log.info("role.updated workspace={} actor={} role={} permissionsReplaced={}",
                workspace.getId(), actor.getId(), roleId, grants != null);
        // A widening edit is a privilege grant to every holder at once, so it gets its own
        // line naming WHAT was granted and to how many people. `permissionsReplaced=true`
        // above records that a replacement happened and nothing about what it did — which
        // is unanswerable after the fact, because the previous grants are gone. Keys and
        // counts only, no ids beyond the three already logged: Loki-safe.
        if (!added.isEmpty()) {
            log.info("role.widened workspace={} actor={} role={} added={} holders={}",
                    workspace.getId(), actor.getId(), roleId, added, holders);
        }
        // A rename changes RoleView.name, which the ceiling exception and the People tab
        // render, so this fires whether or not the permissions moved. AFTER COMMIT.
        events.publishEvent(new RolePermissionsChanged(roleId));
        return respond(workspace, role, null);
    }

    /**
     * Delete a custom role, optionally moving every holder to another one (§7.1 R5).
     *
     * <p>The guards run in the order in which their answers stop being useful: identity
     * (built-in), then the actor's own exposure (self-held), then the target role and
     * <em>what handing it to everybody would grant them</em>, then usage, then the bulk
     * stranding invariant. The last is the only one the actor cannot fix by choosing a
     * different role id.
     *
     * <p><strong>Both ends are bounded</strong> (round-3 review). The role <em>being
     * deleted</em> goes through the assignment ceiling at WORKSPACE scope before anything
     * else happens, for {@code WorkspaceMemberService.updateRole}'s reason: without it an
     * Admin could not demote a holder of an above-them role through
     * {@code PATCH /members/{id}} but could delete the role out from under them — the same
     * irreversible demotion through a door the membership endpoint closes. At PROJECT scope
     * the ceiling stays dropped, as §11.3 specifies; {@link #requireReassignWithinCeiling}
     * carries that scope instead.
     *
     * <p><strong>The reassign target has a ceiling of its own</strong>
     * ({@link #requireReassignWithinCeiling}) — round-2 security finding. {@code SELF_HELD_ROLE}
     * closes the half of the bulk-escalation route that points at the actor; without this it
     * remained wide open pointed at anybody else, and {@code requireAssignable} does not
     * close it, because it deliberately asks only <em>whose workspace</em> and <em>which
     * scope</em>.
     *
     * <p><strong>What it refuses, and where each refusal lives</strong> — the list
     * {@code RoleController} points at rather than restating:
     * <ul>
     *   <li><strong>403</strong> — {@code workspace.role.manage}; the assignment ceiling on
     *       the role <em>being deleted</em> (WORKSPACE scope, above); the ceiling on the
     *       <em>reassign target</em> ({@link #requireReassignWithinCeiling}); and
     *       {@code OwnerIsNotGrantableException.viaInvite} when the target is the built-in
     *       Owner and this role carries pending invites ({@link #requireNoOwnerInvites});</li>
     *   <li><strong>404</strong> — unknown workspace, non-member, or a role this workspace
     *       cannot address;</li>
     *   <li><strong>409</strong> — built-in ({@link #requireCustom}), {@code ROLE_IN_USE}
     *       carrying the usage payload, {@code SELF_HELD_ROLE}
     *       ({@link #requireNotSelfHeld}), {@code LAST_PROJECT_ADMIN_BULK}
     *       ({@link #requireNothingBulkStranded}), or a lock-wait timeout with
     *       {@code Retry-After};</li>
     *   <li><strong>422</strong> — a {@code reassignToRoleId} that is unknown, foreign, of
     *       the other scope, or equal to the role being deleted.</li>
     * </ul>
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID roleId, UUID reassignToRoleId) {
        // Bound first, then lock (HD-136). Not decorative here: a PROJECT-scope reassign runs
        // `UPDATE workspaces … WHERE default_project_role_id = :from`, which takes a row lock
        // on the very workspace row `duplicate` holds FOR NO KEY UPDATE while it counts the
        // sprawl cap. Without this, a delete overlapping a duplication waits for ever —
        // PostgreSQL's own wait has no bound and nothing else in the stack supplies one.
        lockTimeout.applyToCurrentTransaction();
        var ctx = workspaceAccess.requireMember(actor, workspaceId);
        ctx.permissions().require(Permission.WORKSPACE_ROLE_MANAGE);
        var workspace = ctx.workspace();
        // ---- reads and judgements first ----
        var role = roleCatalog.requireAddressable(workspace.getId(), roleId);
        requireCustom(role);
        // The ceiling on the role being DELETED, not only on the reassign target — the
        // other half of the both-ends rule (round-3 review). Deleting a role is the
        // strongest possible edit of it, so an Admin must not be able to delete a WORKSPACE
        // role granting something they do not hold; without this, `DELETE ?reassignTo=` was
        // the demotion `PATCH /members/{id}` refuses. Owner-exempt for free, and the
        // Owner-is-not-grantable clause inside cannot fire here: requireCustom has already
        // established that this is not the built-in Owner role.
        if (role.getScope() == RoleScope.WORKSPACE) {
            workspaceMembers.requireWithinGrantCeiling(ctx, RoleView.of(role));
        }
        requireNotSelfHeld(workspace, actor, role);

        RoleView target = null;
        if (reassignToRoleId != null) {
            if (reassignToRoleId.equals(roleId)) {
                // A value, so 422 — and the same answer a foreign id gets, which is the
                // point: one indistinguishable response for every unusable role reference.
                throw new UnknownRoleException(reassignToRoleId.toString());
            }
            target = roleCatalog.requireAssignable(role.getScope(), workspace.getId(), reassignToRoleId);
            // Before the usage read, because it is a question about the actor rather than
            // about the workspace's data, and because a 403 here must not depend on whether
            // the role happens to be in use.
            requireReassignWithinCeiling(ctx, role, target);
            requireNoOwnerInvites(workspace, role, target);
        }
        var usage = usageOf(workspace, roleId);
        if (target == null && usage.inUse()) {
            throw new RoleInUseException(usage);
        }
        if (target != null) {
            requireNothingBulkStranded(workspace, role, permissionsOf(role), target.permissions(),
                    "Reassigning this role to “" + target.name() + "”",
                    // Both constraints, because naming only the first one sends the caller
                    // straight into the reassign ceiling's 403 (round 2).
                    "Choose a replacement that also grants project.member.manage and is no "
                    + "wider than the role being deleted, or give each of those projects "
                    + "another administrator first.");
        }

        // ---- mutations ----
        if (target != null) {
            reassign(workspace, role, target);
        }
        roleRepository.delete(role);
        // The bulk UPDATEs above are JPQL and bypass the persistence context, so the DELETE
        // must be pushed explicitly rather than left to the ActionQueue's ordering.
        roleRepository.flush();
        log.info("role.deleted workspace={} actor={} role={} scope={} reassignedTo={}",
                workspace.getId(), actor.getId(), roleId, role.getScope(),
                target == null ? null : target.id());
        events.publishEvent(new RolePermissionsChanged(roleId));
    }

    // ============================================================ guards

    private void requireCustom(Role role) {
        // Keyed on the column, never the key string: UNIQUE NULLS NOT DISTINCT lets a
        // workspace own a custom role keyed ADMIN beside the built-in one, and that one IS
        // editable.
        if (role.isBuiltIn()) {
            throw new BuiltInRoleNotEditableException();
        }
    }

    /**
     * <strong>The definition ceiling</strong> — deliberately narrower than the assignment
     * ceiling, and deliberately asymmetric between the scopes (§11.3).
     *
     * <p><strong>WORKSPACE-scoped roles only.</strong> A workspace role has an obvious
     * comparand: the actor's own workspace {@link PermissionSet}. A PROJECT-scoped role has
     * none — the actor's project permissions differ per project, and inventing a comparand
     * at workspace level ({@code effectiveProjectPermissions(actorWorkspacePerms, null)}, i.e.
     * the four curator permissions) would forbid an Admin from duplicating Contributor. That
     * is the product's primary recipe, so a rule that breaks it is the wrong rule.
     *
     * <p><strong>The workspace Owner is exempt, within their own workspace.</strong> They are
     * the root of trust: the ceiling exists to stop escalation <em>past</em> whoever is
     * ultimately responsible, and inside one workspace that is the Owner. It is also the only
     * reason {@link Permission#PROJECT_ADMINISTER_ALL} is reachable at all — no built-in role
     * holds it, so without the exemption a permission the catalog ships would be permanently
     * unmintable. Keyed on the built-in Owner <strong>role id</strong>, never the key string,
     * for {@code WorkspaceMemberService.requireWithinGrantCeiling}'s reason: after this slice
     * a workspace may own a custom role keyed {@code OWNER}, and that role is not this
     * guardrail.
     *
     * <p>Its entire practical effect at workspace scope is therefore <em>"only an Owner may
     * mint {@code project.administer.all}"</em> — a small, valuable, exactly-right rule.
     *
     * <p><strong>Applied to BOTH ends of an edit</strong> (round-3 review), the rule
     * {@code WorkspaceMemberService.updateRole} spells out for a membership change: bounding
     * only the incoming set lets an Admin <em>strip</em> a role that grants more than they
     * hold, demoting its holders below themselves with no way back — the grants cannot be
     * restored, because putting them back is what this ceiling refuses. {@link #delete} does
     * the same with the <em>assignment</em> ceiling on the role it is deleting.
     *
     * <p>The "current role" end runs on <strong>every</strong> {@code PATCH}, not only one
     * carrying {@code permissions}. A rename is not a permission change and is the same
     * sabotage one size smaller: the name is what an administrator picks a role by, so
     * relabelling "Deputy" as "Intern" leaves the next admin handing out something they
     * believe they understand. Whatever you may not strip, you may not relabel.
     *
     * <p><strong>At PROJECT scope both ends are dropped, as specified</strong> (§11.3): the
     * early return above is unconditional, so a PROJECT role's contents are bounded by
     * {@link #requireNotSelfWidening} and {@link #requireReassignWithinCeiling} and by
     * nothing else. That is deliberate and load-bearing — a comparand invented at workspace
     * level would forbid an Admin from duplicating Contributor, which is the product's
     * primary recipe. Do not "complete" the symmetry here without re-opening §11.3.
     */
    private void requireWithinDefinitionCeiling(WorkspaceContext ctx, RoleScope scope,
                                                PermissionSet permissions, String roleName) {
        if (scope != RoleScope.WORKSPACE) {
            return;
        }
        if (ctx.workspaceRole().isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            return;
        }
        ctx.permissions().firstNotCovered(permissions).ifPresent(missing -> {
            throw new WorkspaceGrantCeilingException(roleName, missing);
        });
    }

    /**
     * <strong>The ceiling on a bulk reassign target</strong> — the other half of
     * {@link #requireNotSelfHeld}, and the round-2 Critical.
     *
     * <p>{@code SELF_HELD_ROLE} refuses a reassign aimed at the actor's own access.
     * Nothing refused one aimed at everybody else, and {@code requireAssignable} never
     * could: it asks whose workspace and which scope, and is ceiling-free by design. The
     * traced exploit was four calls at WORKSPACE scope — duplicate Member into a custom
     * role (definition ceiling passes, Member ⊆ Admin), invite a controlled address onto it
     * (assignment ceiling passes; the invite path's Owner refusal keys on the <em>granted</em>
     * role, which is not Owner), delete the custom role reassigning to built-in Owner (not
     * self-held, in use but a target is present, the stranding aggregate returns early at
     * WORKSPACE scope), and the pending invite's {@code role_id} becomes Owner —
     * {@code WorkspaceService.acceptInvite} then writes it onto the membership with no
     * re-validation. At PROJECT scope it is one call and worse: every holder in every
     * project promoted to built-in Project admin, by an actor holding
     * {@code project.member.manage} nowhere.
     *
     * <h3>Two scopes, two comparands</h3>
     *
     * <p><strong>WORKSPACE</strong> reuses {@code WorkspaceMemberService.requireWithinGrantCeiling}
     * verbatim — the same predicate {@code updateRole} and {@code inviteMember} apply — so the
     * Owner exemption and {@link com.hamstrack.workspace.exception.OwnerIsNotGrantableException}
     * are inherited rather than re-derived, and cannot drift from them. Do not re-implement it.
     *
     * <p><strong>PROJECT</strong> has no single workspace-level comparand: the actor's project
     * permissions differ per project, and they may hold none anywhere (§11.3 is the same
     * argument for dropping the <em>definition</em> ceiling there). So the rule is stated
     * about the operation instead — <em>a bulk reassign may narrow or preserve, never
     * widen</em>: the target must be covered by the role being deleted. A reassign then
     * cannot manufacture authority that did not already exist on those very rows. The
     * workspace Owner is exempt, as everywhere else.
     *
     * <p><strong>{@code reassign} also rewrites {@code workspaces.default_project_role_id}.</strong>
     * That column has no write path until S7, at which point it becomes the role every
     * member with no explicit {@code project_members} row inherits in every project — so an
     * unbounded reassign would make built-in Project admin the workspace-wide default in one
     * call. This guard closes that before the picker exists. <strong>S7 must not weaken
     * it</strong>, and the picker itself needs the same comparison on the role it writes.
     */
    private void requireReassignWithinCeiling(WorkspaceContext ctx, Role role, RoleView target) {
        if (role.getScope() == RoleScope.WORKSPACE) {
            workspaceMembers.requireWithinGrantCeiling(ctx, target);
            return;
        }
        if (ctx.workspaceRole().isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            return;
        }
        permissionsOf(role).firstNotCovered(target.permissions()).ifPresent(missing -> {
            throw new RoleReassignWidensException(role.getName(), target.name(), missing);
        });
    }

    /**
     * <strong>A reassign may not mint an Owner <em>invite</em></strong> (round-3 review).
     *
     * <p>{@code WorkspaceService.inviteMember} refuses to hand the built-in Owner role to an
     * invitation <em>even for an Owner</em> — "you promote a colleague to owner, you do not
     * invite a stranger as one". {@link #requireReassignWithinCeiling} returns early for the
     * Owner before {@link OwnerIsNotGrantableException} can fire, so an Owner deleting a
     * custom WORKSPACE role that carried a pending invite repointed that invite at built-in
     * Owner and the rule had a hole. The impact was bounded — the actor is the root of trust
     * and could promote the invitee a moment after they accept — but a call-site invariant
     * with a hole is not an invariant, and a test asserted the hole as desired behaviour.
     *
     * <p>Refuse rather than silently delete the invites: destroying a third party's pending
     * invitation is not something the actor asked for, and the refusal is satisfiable in one
     * move — reassign to any other role (an accepted member can then be promoted through
     * {@code PATCH /members/{id}}, which is the door ownership is supposed to travel).
     *
     * <p>Costs one count, and only when the target actually is the built-in Owner.
     */
    private void requireNoOwnerInvites(Workspace workspace, Role role, RoleView target) {
        if (role.getScope() != RoleScope.WORKSPACE
                || !target.isBuiltIn(BuiltInRoles.WORKSPACE_OWNER)) {
            return;
        }
        var pending = inviteRepository.countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull(
                workspace.getId(), role.getId());
        if (pending > 0) {
            throw OwnerIsNotGrantableException.viaInvite(pending);
        }
    }

    /**
     * <strong>Editing a role is a grant to everyone already holding it</strong>, evaluated
     * against nobody — the round-2 High.
     *
     * <p>§11.3 deliberately drops the definition ceiling at PROJECT scope so that a
     * workspace Admin can duplicate Contributor, which is the product's primary recipe. The
     * consequence was never worked through: an Admin who is also an explicit member of
     * project P carrying custom role R (the spec's own A1 recipe produces exactly that) can
     * PATCH all twenty project permissions into R and gain {@code issue.delete},
     * {@code project.archive}, {@code project.taxonomy.manage} and unrestricted
     * {@code attachment.delete} in P — five gates V13 withholds from Owner and Admin on
     * purpose. It is "remove your own row, add it back bigger" ({@code ProjectService.addMember}
     * refuses to be that route) reassembled out of one call.
     *
     * <p><strong>The minimal rule that closes it without breaking the recipe:</strong> refuse
     * a <em>widening</em> PATCH of a PROJECT role the actor themselves holds. Narrowing,
     * renaming and re-describing a role you hold all stay legal; so does widening a role you
     * do not hold, which is the recipe. Same verdict, same error type and same remedy as the
     * delete path — one rule, two doors.
     *
     * <p>One existence query, workspace-scoped, and the same one {@code SELF_HELD_ROLE}
     * uses. Deliberately not extended to WORKSPACE scope: there the definition ceiling
     * already binds every edit against the actor's own set, so a widening beyond it is
     * refused before this could be asked.
     */
    private void requireNotSelfWidening(Workspace workspace, User actor, Role role,
                                        PermissionSet current, PermissionSet next) {
        if (role.getScope() != RoleScope.PROJECT || current.firstNotCovered(next).isEmpty()) {
            return;
        }
        if (projectMemberRepository.existsHolderInWorkspace(
                workspace.getId(), actor.getId(), role.getId())) {
            throw SelfHeldRoleException.widening();
        }
    }

    /**
     * <strong>Doors 4 and 5</strong> (§6): the change removes {@code project.member.manage}
     * from a PROJECT-scoped role, demoting every holder at once in every project.
     *
     * <p>Runs only on that exact transition, so an ordinary rename, a workspace-scoped edit,
     * or an edit to a role that never had the permission pays nothing — not even the
     * aggregate.
     *
     * @param current the role's grants <strong>read off the managed entity</strong>, never
     *                from {@code roleCatalog.view}. The guard is documented as advisory
     *                against a concurrent <em>membership</em> change; it must not also be
     *                advisory about its own precondition, and the {@code roleView} cache is
     *                per-process and up to 10 s stale on a node that did not serve the
     *                previous edit — which would skip the aggregate entirely for a role that
     *                had just gained the permission
     */
    private void requireNothingBulkStranded(Workspace workspace, Role role, PermissionSet current,
                                            PermissionSet next, String change, String remedy) {
        if (role.getScope() != RoleScope.PROJECT) {
            return;
        }
        if (!current.has(Permission.PROJECT_MEMBER_MANAGE)
                || next.has(Permission.PROJECT_MEMBER_MANAGE)) {
            return;
        }
        var stranded = projectAdminGuard.projectsAdministeredOnlyBy(workspace, role.getId());
        if (!stranded.isEmpty()) {
            throw StrandedProjectsException.bulkRoleChange(stranded, change, remedy);
        }
    }

    /**
     * Refuse a delete whose reassign would change the actor's <em>own</em> access
     * ({@link SelfHeldRoleException}). Two existence queries, both workspace-scoped: the
     * actor's workspace membership, and any project membership of theirs in this workspace.
     */
    private void requireNotSelfHeld(Workspace workspace, User actor, Role role) {
        boolean held = role.getScope() == RoleScope.WORKSPACE
                ? memberRepository.existsByWorkspaceIdAndUserIdAndRoleId(
                        workspace.getId(), actor.getId(), role.getId())
                : projectMemberRepository.existsHolderInWorkspace(
                        workspace.getId(), actor.getId(), role.getId());
        if (held) {
            throw new SelfHeldRoleException();
        }
    }

    /**
     * Display-name uniqueness within {@code (workspace, scope)}, plus the built-in names.
     *
     * <p>Built-ins have {@code workspace_id IS NULL}, so the indexed predicate cannot see
     * them; their names come from the cached catalog at zero query cost, and the list is read
     * from {@link BuiltInRoles} rather than hardcoded so a ninth built-in is covered the day
     * it is seeded. Built-in names never change at runtime, so reading those from the cache
     * is sound in a way reading {@code self}'s is not.
     *
     * @param self the role being renamed, or {@code null} on the duplicate path. Its current
     *             name is read <strong>off the managed entity</strong>: {@code roleCatalog
     *             .view(selfId).name()} answers from the 10 s per-process {@code roleView}
     *             cache, so on a node that did not serve the previous rename it would produce
     *             a spurious or a missed 409. Cosmetic, but it is the same wrong source as
     *             the two guards above and must not be left as a template
     */
    private void requireNameAvailable(Workspace workspace, RoleScope scope, String name, Role self) {
        for (var key : BuiltInRoles.all().get(scope).keySet()) {
            if (roleCatalog.builtIn(scope, key).name().equalsIgnoreCase(name)) {
                throw new RoleNameConflictException(name);
            }
        }
        if (roleRepository.existsByWorkspaceIdAndScopeAndNameIgnoreCase(
                workspace.getId(), scope, name)) {
            // A rename to the role's own current name is not a conflict with itself.
            if (self == null || !name.equalsIgnoreCase(self.getName())) {
                throw new RoleNameConflictException(name);
            }
        }
    }

    /**
     * The sprawl cap's serialisation point, wrapped so it carries the same pairing the other
     * three lock helpers do (round-3 review).
     *
     * <p>Two halves, and they refuse different mistakes. {@code MANDATORY} on the repository
     * method refuses an <em>external</em> caller with no transaction — Spring Data would
     * otherwise open its own read-only one and release the lock before the count it protects,
     * which is exactly the "silent no-op" shape {@code lockOwners} and {@code lockAdmins}
     * were hardened against. The assertion here refuses a future method in <em>this</em>
     * class that forgets {@code @Transactional}: that call would go through the repository
     * proxy and so would trip the annotation too, but only as an
     * {@code IllegalTransactionStateException} from three frames away, and the message that
     * explains what was actually wrong belongs here.
     *
     * <p>INVARIANT, convention-only, identical to the one stated at the other two helpers:
     * every transaction that calls this must ALREADY have called
     * {@code LockTimeout.applyToCurrentTransaction()}. Nothing enforces it; bound first,
     * then lock.
     */
    private void lockForRoleCount(Workspace workspace) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "the role-count lock must run inside the caller's transaction — a lock "
                + "released before the count it protects is not a lock");
        workspaceRepository.lockForRoleCount(workspace.getId());
    }

    // ============================================================ validation

    /**
     * The write-path half of the unknown-key rule, and the guard the highest-risk assumption
     * of this slice rests on: <strong>a permission's scope must equal its role's
     * scope</strong>.
     *
     * <p>A {@link PermissionSet} is a flat {@code EnumSet} with no memory of where a grant
     * came from, so a PROJECT-scoped role containing {@code workspace.member.manage} would
     * put it straight into every holder's {@code ProjectContext.permissions()} — a privilege
     * escalation assembled from an entirely legitimate role id, invisible to
     * {@code findAssignable} (the role is the right scope and the right workspace) and to
     * every log and query in the product. This is the half of the hole S1 could not guard,
     * because until now nothing could compose a permission set.
     *
     * <p>{@code ownRequired} permissions are <em>forced</em> own-only rather than refused —
     * {@code PermissionSet.of} already does that, and the editor renders a locked toggle.
     */
    private List<PermissionSet.Grant> validate(List<RolePermissionEntry> entries, RoleScope scope) {
        if (entries == null || entries.isEmpty()) {
            // A role granting nothing is legal and expected — the built-in Viewer is exactly
            // that, and duplicating it is how "from scratch" is reached.
            return List.of();
        }
        var seen = new HashSet<String>(entries.size());
        var grants = new ArrayList<PermissionSet.Grant>(entries.size());
        for (var entry : entries) {
            var key = entry.key() == null ? "" : entry.key().trim();
            if (!seen.add(key)) {
                throw UnknownPermissionException.duplicate(key);
            }
            var permission = Permission.byKey(key)
                    .orElseThrow(() -> UnknownPermissionException.unknownKey(key));
            if (permission.scope() != scope) {
                throw UnknownPermissionException.wrongScope(key, scope);
            }
            if (entry.isOwnOnly() && !permission.supportsOwn()) {
                // Silently honouring it would NARROW the grant behind the admin's back;
                // silently ignoring it would store something other than what was ticked.
                throw UnknownPermissionException.ownNotSupported(key);
            }
            grants.add(new PermissionSet.Grant(permission, entry.isOwnOnly()));
        }
        return grants;
    }

    // ============================================================ derived assignment

    private RoleAssignmentView assignmentOf(RoleView role, List<RoleView> peers) {
        return assignmentOf(role.id(), role.scope(), role.permissions(), peers);
    }

    /**
     * {@code PermissionSet.firstNotCovered} against every role of the same scope available in
     * this workspace — <strong>the same call the runtime ceiling makes</strong>, which is the
     * only reason the preview and the 403 cannot disagree.
     *
     * @param selfId excluded from the comparison, or {@code null} on the preview path where
     *               there is no stored role yet
     */
    private RoleAssignmentView assignmentOf(UUID selfId, RoleScope scope,
                                            PermissionSet permissions, List<RoleView> peers) {
        var manage = scope == RoleScope.WORKSPACE
                ? Permission.WORKSPACE_MEMBER_MANAGE
                : Permission.PROJECT_MEMBER_MANAGE;
        var managesMembers = permissions.has(manage);
        var canAssign = new ArrayList<RoleRef>();
        var cannotAssign = new ArrayList<RoleBlocker>();
        // Tracked in the loop that already has the peer in hand, rather than re-scanning
        // `peers` once per canAssign entry afterwards (round-3 review). The rescan cost n²
        // per CALL — up to n canAssign entries × an n-long UUID.equals scan — inside a method
        // already called once per role, so the ENDPOINT was cubic; and it was computed
        // EAGERLY, before the `managesMembers &&` short-circuit, so every role paid for a
        // warning only member-managers can produce. At the documented 500 ceiling that is
        // ~129M UUID comparisons on an endpoint the SPA hits on page load. Starting the
        // flag at true keeps it behaviour-identical to the old `allMatch` on an empty
        // canAssign, which is the case the comment below is about.
        var assignsOnlyEmptyRoles = true;
        for (var peer : peers) {
            if (peer.id().equals(selfId)) {
                continue;
            }
            var missing = permissions.firstNotCovered(peer.permissions());
            if (missing.isEmpty()) {
                canAssign.add(new RoleRef(peer.id(), peer.name()));
                if (!peer.permissions().isEmpty()) {
                    assignsOnlyEmptyRoles = false;
                }
            } else {
                cannotAssign.add(new RoleBlocker(peer.id(), peer.name(), missing.get().key()));
            }
        }
        // MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING fires when the role can manage a roster and
        // yet cannot hand anybody a role that DOES anything.
        //
        // Deliberately not the literal `canAssign.isEmpty()` the spec writes (§5), because
        // that condition is unreachable and would make the warning dead code: the built-in
        // Viewer grants the empty set, and the empty set is covered by every set, so EVERY
        // role can assign Viewer. "Can assign only roles that grant nothing" is the condition
        // the warning is actually about — a member-manager who can add people to a project
        // and cannot give any of them the ability to do a single thing. The wire code is
        // unchanged, so S6 renders the same copy either way.
        var warnings = managesMembers && assignsOnlyEmptyRoles
                ? List.of(RoleAssignmentView.MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING)
                : List.<String>of();
        return new RoleAssignmentView(managesMembers, List.copyOf(canAssign),
                List.copyOf(cannotAssign), warnings);
    }

    private List<RoleView> peers(Workspace workspace, RoleScope scope) {
        return roleRepository.findAvailableWithPermissions(workspace.getId(), EnumSet.of(scope))
                .stream().map(RoleView::of).toList();
    }

    // ============================================================ usage

    /**
     * Six workspace-scoped counts for one role. The workspace's own default is read off the
     * already-resolved entity rather than queried — it is a column on a row we are holding.
     */
    private RoleUsageResponse usageOf(Workspace workspace, UUID roleId) {
        return RoleUsageResponse.of(roleId,
                memberRepository.countByWorkspaceIdAndRoleId(workspace.getId(), roleId),
                inviteRepository.countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull(workspace.getId(), roleId),
                projectMemberRepository.countHoldersInWorkspace(workspace.getId(), roleId),
                projectMemberRepository.countProjectsUsingRole(workspace.getId(), roleId),
                projectRepository.countByWorkspaceIdAndDefaultProjectRoleId(workspace.getId(), roleId),
                roleId.equals(workspace.getDefaultProjectRoleId()));
    }

    /**
     * The same answer for every role at once — four grouped statements rather than six per
     * role, so {@code includeUsage=true} over fifty roles is a constant cost.
     */
    private Map<UUID, RoleUsageResponse> usageForAll(Workspace workspace) {
        var members = grouped(memberRepository.countMembersByRole(workspace.getId()), 1);
        var invites = grouped(inviteRepository.countPendingByRole(workspace.getId()), 1);
        // One statement, two columns: holders and distinct projects.
        var projectRows = projectMemberRepository.countHoldersByRole(workspace.getId());
        var holders = grouped(projectRows, 1);
        var projects = grouped(projectRows, 2);
        var defaults = grouped(projectRepository.countDefaultRoleUse(workspace.getId()), 1);
        var ids = new HashSet<UUID>();
        ids.addAll(members.keySet());
        ids.addAll(invites.keySet());
        ids.addAll(holders.keySet());
        ids.addAll(defaults.keySet());
        if (workspace.getDefaultProjectRoleId() != null) {
            ids.add(workspace.getDefaultProjectRoleId());
        }
        var out = new HashMap<UUID, RoleUsageResponse>(ids.size());
        for (var id : ids) {
            out.put(id, RoleUsageResponse.of(id,
                    members.getOrDefault(id, 0L), invites.getOrDefault(id, 0L),
                    holders.getOrDefault(id, 0L), projects.getOrDefault(id, 0L),
                    defaults.getOrDefault(id, 0L),
                    id.equals(workspace.getDefaultProjectRoleId())));
        }
        return out;
    }

    private static Map<UUID, Long> grouped(List<Object[]> rows, int valueIndex) {
        var out = new HashMap<UUID, Long>(rows.size());
        for (var row : rows) {
            out.put((UUID) row[0], ((Number) row[valueIndex]).longValue());
        }
        return out;
    }

    // ============================================================ reassign

    /**
     * Move every live reference from {@code role} to {@code target}, each statement filtered
     * to this workspace, before the role row is deleted.
     *
     * <p>Which references exist depends on the scope, and both default columns are covered
     * even though neither has a write path until S7: the FKs are real today and
     * {@code ON DELETE} is NO ACTION, so skipping them would be a 500 waiting for the
     * picker to ship. Two statements now instead of an incident later.
     *
     * <p>All bulk {@code @Modifying}, all plain — no {@code clearAutomatically}. Nothing here
     * re-reads the mutated rows, and none of them is materialized in this transaction, which
     * is the condition under which a bulk UPDATE is the right tool rather than a way to leave
     * stale managed copies behind.
     */
    private void reassign(Workspace workspace, Role role, RoleView target) {
        var targetRef = roleCatalog.reference(target.id());
        if (role.getScope() == RoleScope.WORKSPACE) {
            memberRepository.reassignRole(workspace.getId(), role.getId(), targetRef);
            inviteRepository.reassignRole(workspace.getId(), role.getId(), targetRef);
        } else {
            projectMemberRepository.reassignRole(workspace.getId(), role.getId(), targetRef);
            projectRepository.reassignDefaultProjectRole(
                    workspace.getId(), role.getId(), target.id());
            workspaceRepository.reassignDefaultProjectRole(
                    workspace.getId(), role.getId(), target.id());
        }
    }

    // ============================================================ helpers

    private RoleResponse respond(Workspace workspace, Role role, RoleUsageResponse usage) {
        var view = RoleView.of(role);
        return RoleResponse.of(role, view.permissions(),
                assignmentOf(view, peers(workspace, role.getScope())), usage);
    }

    /**
     * A role's own grants, <strong>off the entity</strong> — the truth, as opposed to
     * {@code roleCatalog.view(role.getId()).permissions()}, which is a 10 s per-process
     * cache. Every guard whose precondition is "what does this role grant today" reads this.
     */
    private static PermissionSet permissionsOf(Role role) {
        return RoleView.of(role).permissions();
    }

    /**
     * The grants a duplicate inherits — filtered, not copied verbatim.
     *
     * <p>Two filters, and both are defence in depth rather than a live vector today:
     * <ul>
     *   <li>a permission key this build does not understand is dropped, exactly as it is on
     *       the authorization path ({@code PermissionConverter} logs and returns null) — a
     *       duplicate must do what the source DOES, not carry forward a key nothing can
     *       enforce;</li>
     *   <li>a grant whose {@code scope()} does not match the role's is dropped too. That is
     *       the invariant {@link #validate} enforces on every write path, and duplication is
     *       the one path that does not go through it: without this, a wrong-scope grant that
     *       ever reached the table (bad migration, hand-written SQL) would be laundered into
     *       a fresh, blessed role — and a PROJECT role carrying
     *       {@code workspace.member.manage} lands that permission in every holder's
     *       {@code ProjectContext}, which is the escalation this whole slice rests on not
     *       being possible.</li>
     * </ul>
     * Both drops are logged at WARN: they mean a stored row is wrong, and silently repairing
     * a wrong row is how it stays wrong.
     */
    private List<PermissionSet.Grant> copyGrantsOf(Role role) {
        var grants = new ArrayList<PermissionSet.Grant>(role.getPermissions().size());
        for (var rp : role.getPermissions()) {
            var permission = rp.getPermission();
            if (permission == null) {
                continue;
            }
            if (permission.scope() != role.getScope()) {
                log.warn("role.duplicate.dropped_grant source={} scope={} permission={} "
                         + "permissionScope={} reason=wrong_scope",
                        role.getId(), role.getScope(), permission.key(), permission.scope());
                continue;
            }
            grants.add(new PermissionSet.Grant(permission, rp.isOwnOnly()));
        }
        return List.copyOf(grants);
    }

    /**
     * How many members of this workspace hold the role — the {@code holders=} field of the
     * {@code role.widened} audit line, and nothing else. Not a guard: it is read for the log
     * only, and only when an edit actually adds a grant.
     */
    private long holderCount(Workspace workspace, Role role) {
        return role.getScope() == RoleScope.WORKSPACE
                ? memberRepository.countByWorkspaceIdAndRoleId(workspace.getId(), role.getId())
                : projectMemberRepository.countHoldersInWorkspace(workspace.getId(), role.getId());
    }

    /**
     * <strong>Server-generated, never accepted from the client</strong> — uppercase the name,
     * keep {@code [A-Z0-9_]}, truncate to {@code roles.key VARCHAR(40)}, and suffix
     * {@code _2}, {@code _3}… on collision within {@code (workspace, scope)}.
     *
     * <p>This is also how the reserved-key guard is implemented: a generated key equal to a
     * built-in's key <em>in that scope</em> takes the suffix instead. The list is read from
     * {@link BuiltInRoles}, never hardcoded. It matters because
     * {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT}, so the database would
     * happily store a workspace-owned role keyed {@code ADMIN} beside the built-in one — a
     * row that satisfies nothing dangerous (nothing ranks keys any more) but confuses every
     * operator reading {@code psql}.
     */
    private String generateKey(UUID workspaceId, RoleScope scope, String name) {
        var base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "ROLE";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        var builtIns = BuiltInRoles.all().get(scope).keySet();
        var candidate = base;
        for (int n = 2; taken(workspaceId, scope, candidate, builtIns); n++) {
            var suffix = "_" + n;
            var head = base.length() + suffix.length() > 40
                    ? base.substring(0, 40 - suffix.length())
                    : base;
            candidate = head + suffix;
        }
        return candidate;
    }

    private boolean taken(UUID workspaceId, RoleScope scope, String key, java.util.Set<String> builtIns) {
        return builtIns.contains(key)
               || roleRepository.existsByWorkspaceIdAndScopeAndKey(workspaceId, scope, key);
    }
}
