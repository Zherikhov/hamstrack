package com.hamstrack.project.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.exception.UserNotFoundException;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.dto.*;
import com.hamstrack.project.entity.*;
import com.hamstrack.project.exception.*;
import com.hamstrack.project.repository.*;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.ProjectContext;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.RoleView;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProductMetrics metrics;
    /** HD-123: project memberships carry a {@code roles} row; views come from the cache. */
    private final RoleCatalog roleCatalog;

    @Transactional
    public ProjectResponse create(User actor, UUID workspaceId, CreateProjectRequest req) {
        var ws = workspaceAccess.requireMember(actor, workspaceId);
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
        member.setRole(roleCatalog.reference(ProjectRole.MANAGER));
        projectMemberRepository.save(member);
        metrics.projectCreated();

        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, ProjectRole.MANAGER.name());
        return ProjectResponse.of(project, ProjectRole.MANAGER,
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
                    var effectiveRole = explicit != null
                            ? workspaceAccess.requireRole(explicit.getRole().getId(),
                                    RoleScope.PROJECT, workspace.getId(), "project_members")
                            : workspaceAccess.defaultProjectRole(workspace, p);
                    return ProjectResponse.of(p, legacyRole(explicit),
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
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        return projectMemberRepository.findAllByProjectWithUser(project).stream()
                .map(m -> ProjectMemberResponse.of(m, roleCatalog.view(m.getRole().getId()).asProjectRole()))
                .toList();
    }

    /**
     * Add an explicit project membership.
     *
     * <p><strong>{@code VIEWER} is written as Contributor</strong> (HD-125 review, M1).
     * {@code AddProjectMemberRequest.role} is still the legacy {@code ProjectRole}, and
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
        var granted = assignableRole(req.role());
        requireWithinGrantCeiling(ctx, granted, GrantCeilingAction.GRANTING);
        // Only workspace members can join a project — a bare findById would expose
        // any user's email/name across tenants via the response
        var user = userRepository.findById(req.userId())
                .filter(u -> workspaceMemberRepository.existsByWorkspaceAndUser(workspace, u))
                .orElseThrow(UserNotFoundException::new);
        if (projectMemberRepository.existsByProjectAndUser(project, user)) {
            throw new AlreadyProjectMemberException();
        }
        var member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(roleCatalog.reference(granted.id()));
        projectMemberRepository.save(member);
        return ProjectMemberResponse.of(member, storedRole(req.role()));
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
     * Without that, a future "Team lead" (member management plus a set narrower than
     * Contributor) could delete its own row, pass a ceiling comparing its role against
     * itself, miss the last-administrator guard — it is not the built-in Project admin —
     * and inherit the {@code issue.rank} it was deliberately denied. The mirror is worse:
     * the same permission would become "promote anybody to the project default" by
     * deleting their narrow row. In {@code STRICT} there is no inherited role, so
     * {@code defaultProjectRole} answers {@code null} and there is nothing to bound.
     *
     * <p>Ordering is HD-132's: the administrator set is locked <em>first and
     * unconditionally</em>, before the target row is read, because deciding whether to
     * lock from an unlocked read is the race the lock exists to close.
     */
    @Transactional
    public void removeMember(User actor, UUID workspaceId, UUID projectId, UUID userId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.PROJECT_MEMBER_MANAGE);
        var project = ctx.project();
        var admins = lockProjectAdmins(project);
        var user = userRepository.findById(userId)
                .orElseThrow(ProjectNotFoundException::new);
        var member = projectMemberRepository.findByProjectAndUser(project, user)
                .orElseThrow(ProjectNotFoundException::new);
        // What they hold now…
        requireWithinGrantCeiling(ctx, roleCatalog.view(member.getRole().getId()), GrantCeilingAction.ACTING_ON);
        // …and what this removal would leave them holding (null in a STRICT workspace).
        var inherited = workspaceAccess.defaultProjectRole(ctx.workspace(), project);
        if (inherited != null) {
            requireWithinGrantCeiling(ctx, inherited, GrantCeilingAction.LEAVING_DEFAULT);
        }
        requireNotLastProjectAdmin(admins, user.getId());
        projectMemberRepository.delete(member);
    }

    // ---- membership guards ----

    /** What a requested legacy {@code ProjectRole} actually becomes on disk — see {@link #addMember}. */
    @SuppressWarnings("deprecation")
    private static ProjectRole storedRole(ProjectRole requested) {
        return requested == ProjectRole.VIEWER ? ProjectRole.MEMBER : requested;
    }

    /** The built-in role {@link #storedRole} names, as a view carrying its permission set. */
    @SuppressWarnings("deprecation")
    private RoleView assignableRole(ProjectRole requested) {
        return roleCatalog.builtIn(RoleScope.PROJECT, storedRole(requested).name());
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
     * The project's administrators, read under a row lock. See
     * {@code ProjectMemberRepository.lockAllByProjectAndRoleId} for why the lock is
     * unconditional; the returned ids are read off {@code getUser()} proxies, which does
     * not load the user rows.
     */
    private Set<UUID> lockProjectAdmins(Project project) {
        return projectMemberRepository.lockAllByProjectAndRoleId(project, BuiltInRoles.PROJECT_MANAGER)
                .stream()
                .map(m -> m.getUser().getId())
                .collect(Collectors.toSet());
    }

    /** A project that has an administrator must not lose its last one (HD-132's shape). */
    private void requireNotLastProjectAdmin(Set<UUID> admins, UUID targetUserId) {
        if (admins.contains(targetUserId) && admins.size() <= 1) {
            throw new LastProjectAdminException();
        }
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
     * The caller's <em>explicit</em> project role, or {@link ProjectRole#VIEWER} when they
     * have no {@code project_members} row — the wire value of
     * {@code ProjectResponse.myRole}, and <strong>nothing else</strong>: since HD-123 S2
     * no authorization decision in this class reads a role at all.
     *
     * <p>It is deliberately NOT the effective role of §5.2: reporting the inherited
     * Contributor here would flip {@code myRole} from {@code "VIEWER"} to {@code "MEMBER"}
     * for every workspace member without an explicit row, which is a wire change in a
     * slice that is supposed to have none. {@code myPermissions} carries the effective
     * answer instead, and S5 retires this field's role in gating altogether.
     */
    @SuppressWarnings("deprecation")
    private ProjectRole legacyRole(ProjectContext ctx) {
        return ctx.explicitProjectRole() ? ctx.projectRole().asProjectRole() : ProjectRole.VIEWER;
    }

    /** {@link #legacyRole(ProjectContext)} from an already-batched membership row. */
    @SuppressWarnings("deprecation")
    private ProjectRole legacyRole(ProjectMember explicit) {
        return explicit == null
                ? ProjectRole.VIEWER
                : roleCatalog.view(explicit.getRole().getId()).asProjectRole();
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
