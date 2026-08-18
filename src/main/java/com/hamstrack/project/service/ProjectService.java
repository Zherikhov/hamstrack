package com.hamstrack.project.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.exception.UserNotFoundException;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.entity.*;
import com.hamstrack.project.exception.*;
import com.hamstrack.project.repository.*;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.ProjectContext;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.RoleView;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    /**
     * The built-in project role keys this service names. Strings since HD-126 (S3) deleted
     * the ordinal {@code ProjectRole} enum — they are role <em>identities</em>, used for
     * assignment and for the {@code myRole} wire value, and never ranked. The one place
     * ranking would be tempting (the grant ceiling) compares permission sets instead.
     */
    private static final String PROJECT_ADMIN_KEY = "MANAGER";
    private static final String CONTRIBUTOR_KEY = "MEMBER";
    private static final String VIEWER_KEY = "VIEWER";

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProductMetrics metrics;
    /** HD-123: project memberships carry a {@code roles} row; views come from the cache. */
    private final RoleCatalog roleCatalog;
    /**
     * HD-136: the last-administrator invariant, shared verbatim with the workspace-side
     * removal that can break it for many projects at once.
     */
    private final ProjectAdminGuard projectAdminGuard;
    /** HD-136 review round 4: {@code removeMember} takes a row lock, so it bounds the wait. */
    private final LockTimeout lockTimeout;

    /**
     * <strong>Permission: {@code project.create}</strong> (HD-126 S3, §10.1) — a
     * <em>workspace</em>-scoped permission despite the {@code project.} prefix, because
     * there is no project to scope it to yet.
     *
     * <p>Δ-free: the built-in workspace Member holds it, so every workspace member can
     * still create a project exactly as before. It exists as a permission because "only
     * admins open new projects" is a policy a workspace can now express without an
     * ordinal ladder — the first thing a custom role will be used for.
     */
    @Transactional
    public ProjectResponse create(User actor, UUID workspaceId, CreateProjectRequest req) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
        ws.permissions().require(Permission.PROJECT_CREATE);
        var workspace = ws.workspace();
        // Normalize once and use the same value for the uniqueness check and the
        // insert — otherwise a future relaxed key pattern could 500 on the unique
        // constraint instead of returning a clean 409.
        var key = req.key().toUpperCase();
        if (projectRepository.existsByWorkspaceAndKey(workspace, key)) {
            throw new ProjectKeyConflictException();
        }
        var project = new Project();
        project.setWorkspace(workspace);
        project.setName(req.name());
        project.setKey(key);
        project.setDescription(req.description());
        project.setCreatedBy(actor);
        // HD-102: the creation picker's answer. Omitted → the entity's own lean
        // defaults (KANBAN, releases off, estimation off — §7 / open question 2).
        applyDelivery(project, null, req.delivery());
        projectRepository.save(project);

        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(actor);
        member.setRole(roleCatalog.reference(RoleScope.PROJECT, PROJECT_ADMIN_KEY));
        projectMemberRepository.save(member);
        metrics.projectCreated();

        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, PROJECT_ADMIN_KEY);
        return ProjectResponse.of(project, PROJECT_ADMIN_KEY,
                workspaceAccess.effectiveProjectPermissions(ws.permissions(), managerRole));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(User actor, UUID workspaceId, boolean includeArchived) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
        var workspace = ws.workspace();
        var projects = includeArchived
                ? projectRepository.findAllByWorkspace(workspace)
                : projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace);
        // One membership query for all projects instead of one per project. HD-123 keeps
        // it at one: the roles are JOIN FETCHed and every role -> permissions lookup below
        // is a cache hit, so myPermissions per row costs no query (§9.2).
        var membershipByProjectId = projectMemberRepository.findAllByUserAndProjectIn(actor, projects).stream()
                .collect(Collectors.toMap(m -> m.getProject().getId(), m -> m));
        return projects.stream()
                .map(p -> {
                    var explicit = membershipByProjectId.get(p.getId());
                    // Same scope+ownership assertion the detail path applies (H3): a list
                    // that skipped it would render controls the detail view then refuses.
                    // Cache hit, so it costs no query per row.
                    //
                    // DEGRADED rather than refusing (HD-127 §3b): one corrupt role_id must
                    // not cost the caller their entire project list. Empty means the
                    // explicit role contributes NOTHING — deliberately not a fall-through
                    // to the §5.2 default, which would WIDEN a member whose narrow row is
                    // exactly what the corruption destroyed. `myRole` goes null for the
                    // same reason WorkspaceResponse.degraded does: never echo the role the
                    // assertion just refused.
                    var degraded = explicit != null
                            && workspaceAccess.resolveRoleOrDegrade(explicit.getRole().getId(),
                                    RoleScope.PROJECT, workspace.getId(),
                                    RoleScopeViolationSource.PROJECT_MEMBERS).isEmpty();
                    RoleView effectiveRole = null;
                    if (explicit == null) {
                        effectiveRole = workspaceAccess.defaultProjectRole(workspace, p);
                    } else if (!degraded) {
                        effectiveRole = roleCatalog.view(explicit.getRole().getId());
                    }
                    return ProjectResponse.of(p, degraded ? null : legacyRole(explicit),
                            workspaceAccess.effectiveProjectPermissions(ws.permissions(), effectiveRole));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(User actor, UUID workspaceId, UUID projectId) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
        var project = projectInWorkspace(ws.workspace(), projectId);
        var ctx = workspaceAccess.projectContext(actor, ws, project);
        return ProjectResponse.of(project, legacyRole(ctx), ctx.permissions());
    }

    /**
     * Rename / re-describe / change the delivery capabilities.
     *
     * <p><strong>Permission: {@code project.edit}</strong> (HD-123 S2, §10.2). It was
     * {@code requireRole(MANAGER)} until HD-22 §3.2, then
     * {@code ScopeResolver.requireProjectCurator} — project MANAGER <em>or</em> workspace
     * OWNER/ADMIN — because {@code boardMode} joined this PATCH and the SPA's
     * {@code ProjectSettingsArea} has always admitted exactly the curator predicate, so
     * a workspace admin could reach the form and then 403 on save. That predicate is now
     * spelled out rather than hardcoded: the built-in project MANAGER holds
     * {@code project.edit}, and the built-in workspace Owner/Admin hold
     * {@code project.curate.all}, which carries it into every project of their workspace
     * (§17.2). Same verdict for every actor, one primitive instead of two.
     *
     * <p>The widening is still scoped on purpose: {@code archive}/{@code unarchive}
     * ({@code project.archive}) and member management ({@code project.member.manage}) are
     * <em>not</em> in the curator set, so a workspace OWNER/ADMIN who is not a project
     * member still cannot reach them. Tenancy is unchanged: {@code resolveProject}
     * resolves through workspace membership first, so a missing workspace, a missing
     * project and a non-member all still yield 404 — the permission is only ever
     * evaluated for someone already proved to be a member.
     *
     * <p><strong>Archived projects are frozen</strong> (security review L5): every issue
     * edit, sprint mutation and rank move already 409s on an archived project, so its
     * own settings — now including the delivery capabilities, which change how the
     * board, the backlog, the rail and the issue detail render — must not stay quietly
     * writable. {@code unarchive} is the way back, and it is deliberately still
     * MANAGER-only.
     *
     * <p><strong>HD-102:</strong> the capabilities arrive in {@code delivery}, with the
     * deprecated top-level {@code boardMode} still accepted (and reconciled — see
     * {@link #applyDelivery}). Nothing else in the codebase reads them, so this method
     * is the <em>only</em> place a capability is ever written and there is no
     * capability-conditional behaviour anywhere downstream (Rule A, §5.1).
     */
    @Transactional
    @SuppressWarnings("deprecation") // reads the legacy boardMode mirror on purpose
    public ProjectResponse update(User actor, UUID workspaceId, UUID projectId, UpdateProjectRequest req) {
        // Permission first, project state second (§10.3.6): a 403 must never depend on
        // whether the project happens to be archived.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_EDIT);
        var project = ctx.project();
        requireNotArchived(project);
        if (req.name() != null) project.setName(req.name());
        if (req.description() != null) project.setDescription(req.description());
        applyDelivery(project, req.boardMode(), req.delivery());
        projectRepository.save(project);
        // The caller's REAL project role, not a hardcoded MANAGER: a workspace
        // OWNER/ADMIN who is not a project member reaches this method, and echoing
        // MANAGER back would make the SPA render project-manager-only actions for them.
        // Read off the context resolved above — the old second resolveProject call was a
        // whole extra round of queries for an answer we already held (§9.2: −1).
        return ProjectResponse.of(project, legacyRole(ctx), ctx.permissions());
    }

    @Transactional
    public void archive(User actor, UUID workspaceId, UUID projectId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_ARCHIVE);
        var project = ctx.project();
        project.setArchivedAt(Instant.now());
        projectRepository.save(project);
    }

    @Transactional
    public void unarchive(User actor, UUID workspaceId, UUID projectId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_ARCHIVE);
        var project = ctx.project();
        project.setArchivedAt(null);
        projectRepository.save(project);
    }

    /**
     * <strong>No permission gate</strong> (§10.3.1). This used to call
     * {@code requireRole(VIEWER)}, which passed for literally everybody —
     * {@code getRole} fell back to {@code VIEWER} for a caller with no
     * {@code project_members} row, and {@code isAtLeast(VIEWER)} is true for all three
     * legacy roles. It was a gate in name only, and the new model cannot express it: the
     * built-in Viewer holds nothing at all, so keeping a "gate" here would have meant
     * inventing a narrowing nobody asked for.
     *
     * <p>Any workspace member may therefore list a project's members. The assignee
     * picker, mention autocomplete and the People tab all need it, and the workspace
     * member list is already open to every member — this discloses strictly less.
     * Tenancy is untouched: a non-member of the workspace still gets 404 from
     * {@code resolveProject}.
     */
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(User actor, UUID workspaceId, UUID projectId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        // The RESOLVED workspace id, not the path variable — same value here, but this is the
        // one that has been through the tenancy check.
        var ownerWorkspaceId = ctx.workspace().getId();
        // Every row here belongs to SOMEBODY ELSE, so each role_id goes through the same
        // scope+ownership assertion `list` and the detail path apply (H3/T2) rather than
        // being dereferenced with a bare `view`. Until HD-126 the legacy bridge threw for
        // any non-built-in role and that was the accidental guard; `.key()` answers for
        // anything, so once S4 ships custom roles a foreign or corrupt `project_members
        // .role_id` would render another workspace's role name straight into the People
        // tab. Free on a warm cache — no query per row.
        // Degraded, not refused (HD-127 §3b): the whole People tab must not 404 because of
        // one other member's row — and the refused role's key is exactly what must NOT
        // appear, so that entry renders with `role: null`.
        return projectMemberRepository.findAllByProjectWithUser(ctx.project()).stream()
                .map(m -> ProjectMemberResponse.of(m,
                        workspaceAccess.resolveRoleOrDegrade(m.getRole().getId(),
                                        RoleScope.PROJECT, ownerWorkspaceId,
                                        RoleScopeViolationSource.PROJECT_MEMBERS)
                                .map(RoleView::key).orElse(null)))
                .toList();
    }

    /**
     * Add an explicit project membership.
     *
     * <p><strong>{@code VIEWER} is written as Contributor</strong> (HD-125 review, M1).
     * {@code AddProjectMemberRequest.role} is still the legacy role <em>key</em>, and
     * the built-in role keyed {@code VIEWER} <em>changes meaning</em> under HD-123: it
     * granted everything (the fallback for "no row at all", §2.2) and now grants nothing.
     * Writing it verbatim would make this unchanged, still-documented endpoint mint
     * somebody who 403s on every write and 422s as an assignee — a user-visible change in
     * a slice whose whole contract is invisibility. So it lands on the same role
     * {@code V14} maps existing {@code VIEWER} rows to, for the same reason, and the
     * response echoes what was <em>stored</em> rather than what was asked for. S4 replaces
     * the DTO with a role id and this translation goes away with it; a genuinely
     * read-only member becomes expressible then, deliberately.
     *
     * <p><strong>Grant ceiling</strong> (§11.2, M3): the role being handed out must not
     * hold a project permission the actor lacks. Otherwise
     * {@code project.member.manage} is self-escalation to Project admin in two calls that
     * each pass their own gate — remove your own row, add it back with a bigger role.
     */
    @Transactional
    public ProjectMemberResponse addMember(User actor, UUID workspaceId, UUID projectId, AddProjectMemberRequest req) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_MEMBER_MANAGE);
        var workspace = ctx.workspace();
        var project = ctx.project();
        // Exactly one of roleId / role — 422 otherwise. The legacy key keeps its
        // VIEWER -> Contributor translation; the id path deliberately does not.
        var granted = roleCatalog.requireAssignable(RoleScope.PROJECT, workspace.getId(),
                req.roleId(), req.role() == null ? null : storedRole(req.role()));
        // Only workspace members can join a project — a bare findById would expose
        // any user's email/name across tenants via the response
        var user = userRepository.findById(req.userId())
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(UserNotFoundException::new);
        requireGrantable(ctx, actor, user.getId(), granted, "add");
        if (projectMemberRepository.existsByProjectAndUser(project, user)) {
            throw new AlreadyProjectMemberException();
        }
        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(roleCatalog.reference(granted.id()));
        projectMemberRepository.save(member);
        // Echo what was STORED, which on the roleId path is simply the role that was named.
        return ProjectMemberResponse.of(member, granted.key());
    }

    /**
     * Remove an explicit project membership.
     *
     * <p>Two guards beyond the permission, both from the HD-125 review. The
     * <strong>grant ceiling</strong> applies to the target's current role as well as to a
     * granted one (§11.2 / HD-132's "only checking the new role would let an ADMIN demote
     * an OWNER"), and the project must not lose its <strong>last administrator</strong> —
     * {@code project.member.manage} is not part of the workspace-admin curator bypass, so
     * a project with no administrator cannot get one back through any endpoint.
     *
     * <p><strong>The ceiling bounds the delta, not the row</strong> (review round 2). In an
     * {@code OPEN} workspace, deleting a {@code project_members} row does not remove anyone
     * from the project — it drops them onto the default role chain, which ends at
     * Contributor (§5.2). So the role the removal <em>leaves behind</em> is checked too.
     * Without that, a custom role holding <em>member management plus a set narrower than
     * Contributor</em> — a "QA lead", say, with {@code project.member.manage} and no
     * {@code issue.rank} — could delete its own row, pass a ceiling comparing its role
     * against itself, pass the last-administrator guard (which since HD-136 counts custom
     * roles too — it resolves the administering role ids from the <em>grant</em>, so a
     * custom role carrying {@code project.member.manage} IS counted; it refuses only when
     * the caller is the project's <strong>last</strong> administrator, and with a second one
     * present it lets the removal through) and inherit the {@code issue.rank} it was
     * deliberately denied.
     * <strong>Deliberately not called "Team lead"</strong>: the built-in of that name (V16)
     * is Contributor <em>plus</em> member management, i.e. wider than the fallback and
     * therefore not this shape at all — V16 spends twenty lines explaining that a role
     * narrower than the fallback is a one-way trap, which is exactly what this guard
     * catches, and naming the trap after the role that avoids it inverted the argument. The mirror is worse:
     * the same permission would become "promote anybody to the project default" by
     * deleting their narrow row. In {@code STRICT} there is no inherited role, so
     * {@code defaultProjectRole} answers {@code null} and there is nothing to bound.
     *
     * <p>Ordering is HD-132's: the administrator set is locked <em>first and
     * unconditionally</em>, before the target row is read, because deciding whether to
     * lock from an unlocked read is the race the lock exists to close.
     *
     * <p><strong>Both guards now live in {@link ProjectAdminGuard}</strong> (HD-136), which
     * asks who holds {@code project.member.manage} rather than who carries the built-in
     * Project admin role id — so a project whose sole administrator holds a custom role is
     * protected too — and which the workspace-side member removal calls as well, because
     * that endpoint deletes these very rows and was breaking this invariant freely.
     */
    @Transactional
    public void removeMember(User actor, UUID workspaceId, UUID projectId, UUID userId) {
        // Before anything, because lockAdmins below is a FOR UPDATE and a lock wait has no
        // bound of its own — the ordered read makes overlapping removals QUEUE, and an
        // unbounded queue on a pooled connection is the failure mode, not the deadlock.
        lockTimeout.applyToCurrentTransaction();
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_MEMBER_MANAGE);
        var project = ctx.project();
        var admins = projectAdminGuard.lockAdmins(project);
        var user = userRepository.findById(userId)
                .orElseThrow(ProjectNotFoundException::new);
        var member = projectMemberRepository.findByProjectAndUser(project, user)
                .orElseThrow(ProjectNotFoundException::new);
        // What they hold now… — asserted, not merely dereferenced; see updateMember.
        requireWithinGrantCeiling(ctx, requireProjectRole(ctx, member), GrantCeilingAction.ACTING_ON);
        // …and what this removal would leave them holding (null in a STRICT workspace).
        var inherited = workspaceAccess.defaultProjectRole(ctx.workspace(), project);
        if (inherited != null) {
            requireWithinGrantCeiling(ctx, inherited, GrantCeilingAction.LEAVING_DEFAULT);
        }
        projectAdminGuard.requireNotLastAdmin(admins, user.getId());
        projectMemberRepository.delete(member);
    }


    /**
     * <strong>Change an existing project member's role</strong> (HD-127, M4) — and
     * <strong>door 3</strong> of the stranding enumeration: a demotion strands a project
     * with no row removed at all, which neither HD-136 guard could see.
     *
     * <p>Before this existed, correcting somebody's project role meant remove + add: two
     * calls, each strand-checked separately, with the member dropped onto the workspace
     * default in between (in an {@code OPEN} workspace) and a hole in the middle where the
     * project genuinely had no administrator.
     *
     * <p><strong>The order of the first five statements is the rule, not a style.</strong>
     * <ol>
     *   <li>bound the lock wait, before anything can queue on a lock;</li>
     *   <li>resolve and authorize, before an unauthorized caller can take one;</li>
     *   <li>resolve the requested role, before the target is read — so a bad role id never
     *       depends on who it was aimed at (mirrors
     *       {@code WorkspaceMemberService.updateRole});</li>
     *   <li>lock the administrator set <strong>unconditionally</strong>, before the target
     *       row is read: deciding whether to lock from an unlocked read is the race the lock
     *       exists to close, and this is the third time the project has had to learn it
     *       (HD-132, HD-136);</li>
     *   <li>only then read the target.</li>
     * </ol>
     *
     * <p><strong>The ceiling applies to both ends</strong> — the target's current role
     * ({@code ACTING_ON}) and the requested one ({@code GRANTING}) — for HD-132's reason:
     * checking only the new role would let a narrow member-manager demote somebody wider
     * than themselves, which is the same escalation by the other door. The §4 escape may
     * exempt the {@code GRANTING} half; it never exempts {@code ACTING_ON}.
     *
     * <p><strong>The last-administrator check is skipped for a promotion.</strong> A role
     * that itself grants {@code project.member.manage} cannot strand anything, so requiring
     * a second administrator before you may widen the only one would make the invariant
     * unfixable — the same shape as {@code WorkspaceMemberService.updateRole}'s
     * {@code if (!requested.isBuiltIn(WORKSPACE_OWNER))}.
     *
     * <p>Self-demotion is allowed, subject to that check — mirroring workspace
     * self-demotion, and refused by the guard exactly when it would orphan the project.
     *
     * <p><strong>Do not "fix" the guard's two documented conservatisms on this path.</strong>
     * It counts only explicit {@code project_members} rows and does not consult
     * {@code project.administer.all}. A demotion's target is by definition a member with an
     * explicit row — the one person whose default-role inheritance does not apply — so
     * counting inheritance here would be counting a fallback that cannot reach them.
     * {@code ProjectAdminGuard.cannotBeStranded} already asks the inheritance question
     * correctly, for the case where somebody <em>else</em> stands on the fallback.
     *
     * <p>200 · <strong>403</strong> missing {@code project.member.manage}, or a ceiling
     * refusal naming the permission · <strong>404</strong> unknown workspace, non-member,
     * project not in this workspace, or a target holding no project membership here ·
     * <strong>409</strong> last administrator, or a lost lock race (with
     * {@code Retry-After}) · <strong>422</strong> an unknown, foreign or WORKSPACE-scoped
     * {@code roleId}.
     */
    @Transactional
    public ProjectMemberResponse updateMember(User actor, UUID workspaceId, UUID projectId,
                                              UUID userId, UpdateProjectMemberRequest req) {
        // FIRST statement: lockAdmins below is a FOR UPDATE and a lock wait has no bound of
        // its own — the ordered reads make overlapping edits QUEUE, and an unbounded queue
        // on a pooled connection is the failure mode.
        lockTimeout.applyToCurrentTransaction();
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_MEMBER_MANAGE);
        var project = ctx.project();
        // 422 before the lock and before the target is read.
        var requested = roleCatalog.requireAssignable(
                RoleScope.PROJECT, ctx.workspace().getId(), req.roleId());

        var admins = projectAdminGuard.lockAdmins(project);

        var user = userRepository.findById(userId).orElseThrow(ProjectNotFoundException::new);
        var member = projectMemberRepository.findByProjectAndUser(project, user)
                .orElseThrow(ProjectNotFoundException::new);
        // ANOTHER person's role_id, so it goes through the scope+ownership assertion rather
        // than a bare cache read — the pattern listMembers was moved away from in this same
        // slice. Refuse, do not degrade: this is a single-resource WRITE, and a foreign
        // role_id here would put its NAME into the ProjectGrantCeilingException detail and,
        // if WORKSPACE-scoped, supply workspace.* as the ACTING_ON comparand. Unreachable
        // today (all eleven write doors go through findAssignable) — which is exactly when
        // an assertion is cheap.
        var current = requireProjectRole(ctx, member);

        requireWithinGrantCeiling(ctx, current, GrantCeilingAction.ACTING_ON);
        requireGrantable(ctx, actor, userId, requested, "change");
        if (current.id().equals(requested.id())) {
            // Re-sending the role a member already holds is a no-op, not a rejection: the
            // SPA re-sends the current value on any partial edit, and the last-administrator
            // guard must not fire on a change that changes nothing. Step 7 is naturally
            // skipped because the grant is unchanged.
            return ProjectMemberResponse.of(member, current.key());
        }
        if (!requested.permissions().has(Permission.PROJECT_MEMBER_MANAGE)) {
            projectAdminGuard.requireNotLastAdmin(admins, userId);
        }

        // ---- mutation, immediately before the save (the @Version rule) ----
        member.setRole(roleCatalog.reference(requested.id()));
        projectMemberRepository.save(member);
        log.info("project.member.role_changed workspace={} project={} actor={} target={} from={} to={}",
                ctx.workspace().getId(), project.getId(), actor.getId(), userId,
                current.id(), requested.id());
        return ProjectMemberResponse.of(member, requested.key());
    }

    /**
     * The project grant ceiling on a role being <strong>handed out</strong>, plus
     * <strong>the §4 escape</strong>.
     *
     * <p>The escape, stated exactly: <em>a caller holding {@code project.member.manage} in a
     * project may always assign the built-in Project admin role to ANOTHER member of that
     * project, regardless of the ceiling.</em>
     *
     * <p><strong>Why it has to exist.</strong> Sixteen of the twenty project permissions are
     * reachable only from inside the project, and granting any of them needs an actor holding
     * both {@code project.member.manage} and the permission itself. So a project whose only
     * member-managers carry a custom role narrower than Project admin can never acquire what
     * none of them holds — {@code project.archive} and {@code project.taxonomy.manage} having
     * no workaround at all. The workspace Owner does not help: they are exempt from the
     * workspace ceiling but hold no {@code project.member.manage} in a project they are not a
     * member of, so they cannot assign any project role there either. The alternative to this
     * escape is a project no endpoint can repair, whose fix is a hand-written {@code UPDATE}.
     *
     * <p><strong>{@code target != actor} is load-bearing, not decorative.</strong> Without it
     * {@code project.member.manage} would imply all twenty project permissions for its
     * holder, which (a) makes the project-scope ceiling decorative and (b) breaks HD-136's H1
     * argument directly: {@code ProjectAdminGuard.adoptAll} hands out the built-in Team lead
     * on the explicit promise that nothing it grants can destroy anything, so a self-grant
     * would turn every adoption into a two-call route to {@code issue.delete}.
     * {@code addMember} already refuses to be that route ("remove your own row, add it back
     * with a bigger role"); this must not become it.
     *
     * <p><strong>Keyed on the built-in role ID</strong>, never the key string: after S4 a
     * workspace may own a custom role keyed {@code MANAGER}, and that role is not this
     * guardrail.
     *
     * <p><strong>The residual, documented rather than hidden:</strong> two cooperating
     * members, one holding {@code project.member.manage}, can bootstrap a Project admin (A
     * grants B; B, now holding everything, grants A by the ordinary ceiling). That IS the
     * recovery procedure, performed by two willing people — which is what a fixed, auditable
     * escape means. It needs an accomplice who already has a workspace account, nobody gains
     * visibility they lacked (v1 has no {@code project.view}), and both steps are logged.
     *
     * <p><strong>Secondary effect, intended — do not later "fix" it:</strong> the removal
     * ceiling is unchanged, so after A promotes B, A can no longer remove or demote B,
     * because {@code ACTING_ON} compares against B's <em>current</em> role.
     *
     * @param action a word for the log line only; the ceiling's own message is built from
     *               {@link GrantCeilingAction}
     */
    private void requireGrantable(ProjectContext ctx, User actor, UUID targetUserId,
                                  RoleView granted, String action) {
        if (granted.isBuiltIn(BuiltInRoles.PROJECT_MANAGER) && !actor.getId().equals(targetUserId)) {
            // Reachable only for a caller who already passed
            // require(PROJECT_MEMBER_MANAGE) above.
            log.info("project.admin_granted workspace={} project={} actor={} target={} reason=ceiling_escape action={}",
                    ctx.workspace().getId(), ctx.project().getId(), actor.getId(), targetUserId, action);
            return;
        }
        requireWithinGrantCeiling(ctx, granted, GrantCeilingAction.GRANTING);
    }

    // ---- membership guards ----

    /** What a requested role key actually becomes on disk — see {@link #addMember}. */
    private static String storedRole(String requested) {
        return VIEWER_KEY.equals(requested) ? CONTRIBUTOR_KEY : requested;
    }

    /**
     * §11.2 at project scope: the actor may not hand out — or act on, or leave somebody
     * with — a role holding a permission they do not hold themselves. Compares
     * <em>permission sets</em>, not role ordinals, because a custom role has no ordinal
     * (that ladder is what HD-123 removes).
     *
     * @param action which of the three the caller was doing, so the 403 says something
     *               they can act on rather than "you cannot grant" on a {@code DELETE}
     */
    private void requireWithinGrantCeiling(ProjectContext ctx, RoleView role, GrantCeilingAction action) {
        ctx.permissions().firstNotCovered(role.permissions()).ifPresent(missing -> {
            throw new ProjectGrantCeilingException(action, role.name(), missing);
        });
    }

    /**
     * A <em>third party's</em> {@code project_members.role_id}, resolved with the
     * scope/ownership assertion instead of a bare {@code roleCatalog.view} — for the two
     * single-resource writes that act on somebody else's membership ({@link #removeMember},
     * {@link #updateMember}).
     *
     * <p><strong>Refuse, never degrade, on a write path.</strong> The list reads use
     * {@code resolveRoleOrDegrade} because one corrupt row must not 404 an entire People
     * tab; here the row IS the resource, so the answer is
     * {@code WorkspaceNotFoundException}'s 404. Two things go wrong if this is a bare
     * dereference: the foreign role's <em>name</em> reaches the caller through
     * {@link ProjectGrantCeilingException}'s detail, and a WORKSPACE-scoped row supplies
     * {@code workspace.*} grants as the {@code ACTING_ON} comparand — a
     * {@code PermissionSet} does not remember which scope its grants came from.
     */
    private RoleView requireProjectRole(ProjectContext ctx, ProjectMember member) {
        return workspaceAccess.requireRole(member.getRole().getId(), RoleScope.PROJECT,
                ctx.workspace().getId(), RoleScopeViolationSource.PROJECT_MEMBERS);
    }

    // ---- helpers ----

    /**
     * The project by id <em>within an already-resolved workspace</em> — a lookup, not an
     * access check. It performs no membership check of its own and must only ever be
     * handed a {@code Workspace} that came from
     * {@code WorkspaceAccessService.requireMember}.
     *
     * <p><strong>Named for what it does, deliberately.</strong> It used to be called
     * {@code resolveProject}, which is now also the name of
     * {@code WorkspaceAccessService.resolveProject} — a method that <em>does</em> verify
     * membership, in the same call graph. The public one was renamed (from
     * {@code requireProjectMember}) precisely because a name that overstates what a method
     * checks cost this project a documented gotcha; shadowing it here with an unchecked
     * helper of the same name would have rebuilt the same trap one layer down.
     */
    private Project projectInWorkspace(Workspace workspace, UUID projectId) {
        return projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
    }

    /**
     * The caller's <em>explicit</em> project role key, or {@code "VIEWER"} when they have
     * no {@code project_members} row — the wire value of {@code ProjectResponse.myRole},
     * and <strong>nothing else</strong>: since HD-123 S2 no authorization decision in this
     * class reads a role at all.
     *
     * <p>It is deliberately NOT the effective role of §5.2: reporting the inherited
     * Contributor here would flip {@code myRole} from {@code "VIEWER"} to {@code "MEMBER"}
     * for every workspace member without an explicit row, which is a wire change this epic
     * promised not to make. {@code myPermissions} carries the effective answer instead.
     */
    private String legacyRole(ProjectContext ctx) {
        return ctx.explicitProjectRole() ? ctx.projectRole().key() : VIEWER_KEY;
    }

    /** {@link #legacyRole(ProjectContext)} from an already-batched membership row. */
    private String legacyRole(ProjectMember explicit) {
        return explicit == null
                ? VIEWER_KEY
                : roleCatalog.view(explicit.getRole().getId()).key();
    }

    /**
     * The <strong>only</strong> place a delivery capability is written (HD-102 §11.3),
     * shared by create (where {@code legacyBoardMode} is always null) and update.
     *
     * <p>Three rules, in order:
     * <ol>
     *   <li><strong>{@code preset} is derived, never settable</strong> (open question
     *       5) — a request carrying it is a <strong>400</strong> naming the field, not
     *       a silent ignore. The label is computed by {@code DeliveryPreset.of} from
     *       the capabilities, so accepting it would create a second source of truth
     *       that could disagree with the first.</li>
     *   <li><strong>The deprecated top-level {@code boardMode} and
     *       {@code delivery.board} must agree.</strong> Both present and equal → fine
     *       (an SPA mid-migration may well send both). Both present and different →
     *       <strong>400</strong>: picking a winner would silently discard half of what
     *       an out-of-date client asked for.</li>
     *   <li>Every member is <strong>partial</strong>: null leaves the capability alone,
     *       so a PATCH that only flips {@code releases} cannot disturb the board mode.
     *       On create, "alone" means the entity's lean field defaults.</li>
     * </ol>
     *
     * <p><strong>Rule A (§5.1) lives here, by omission:</strong> this method only ever
     * writes three columns on {@code projects}. No repository query, no other service
     * and no controller reads {@code releasesEnabled}/{@code estimationEnabled}/
     * {@code boardMode} to decide whether to accept a request, so no status code
     * anywhere can depend on a capability. Turning one off is a pure presentation
     * change: version, sprint and story-point data is untouched and every endpoint
     * that writes it keeps working identically (§13's non-destructive invariant).
     */
    private void applyDelivery(Project project, BoardMode legacyBoardMode, DeliveryRequest delivery) {
        if (delivery != null && delivery.preset() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "delivery.preset is derived from board/releases/estimation and cannot be set");
        }
        var board = delivery != null ? delivery.board() : null;
        if (board != null && legacyBoardMode != null && board != legacyBoardMode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "boardMode and delivery.board disagree — send only one");
        }
        if (board == null) board = legacyBoardMode;
        if (board != null) project.setBoardMode(board);
        if (delivery != null) {
            if (delivery.releases() != null) project.setReleasesEnabled(delivery.releases());
            if (delivery.estimation() != null) project.setEstimationEnabled(delivery.estimation());
        }
    }

    /**
     * An archived project's content is frozen (issues, sprints, ranks) and so are its
     * settings — same 409 and same wording as {@code IssueService}/{@code SprintService},
     * so the SPA renders one message for the whole class. Reads still work.
     */
    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }
}
