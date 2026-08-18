package com.hamstrack.workspace.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.ProjectAccessMode;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The single tenancy chokepoint (HD-82) and, since HD-123, the single
 * <strong>authorization</strong> chokepoint too. Encapsulates the resolve-workspace →
 * verify-membership → <strong>404-not-403</strong> dance that was copy-pasted across ~12
 * services, and resolves the caller's effective permissions <em>once per request</em>
 * into the returned context (§5.1, Rule P1).
 *
 * <p><strong>Invariant (byte-identical to every former inline copy):</strong> a
 * non-existent workspace AND a workspace the caller isn't a member of both throw
 * {@link WorkspaceNotFoundException} (HTTP 404) — never a 403, which would disclose
 * existence across tenants. The project/issue compositions add
 * {@link ProjectNotFoundException} / {@link IssueNotFoundException} on top, matching the
 * former inline resolvers exactly. <strong>Permission evaluation happens strictly after
 * that</strong>, so a permission failure (403) can only ever reach a caller who has
 * already been proved a member — the rule §12 exists to protect.
 *
 * <p>Every method is {@code @Transactional(readOnly = true)} and participates in the
 * caller's transaction via {@code REQUIRED} propagation (no new tx boundary), so the
 * returned entities stay managed for the caller and no extra flush is introduced that
 * could bump an {@code @Version} — matching the previous inline behavior.
 *
 * <p><strong>Query cost</strong> (§9.2, warm role cache): a workspace-scoped resolution
 * is <strong>2</strong> statements (workspace, membership) and a project-scoped one is
 * <strong>4</strong> (+ project, + explicit project membership). Resolving roles to
 * permissions costs <strong>0</strong>: built-in role ids are compile-time constants
 * ({@code BuiltInRoles}) and {@code RolePermissionCache} holds the permission sets. The
 * memberships are {@code JOIN FETCH}ed with their role, which is why the count does not
 * grow. Authorization is then free at the call site — a handler that checks six
 * permissions costs exactly what one that checks none costs.
 *
 * <p><strong>It is now the only one.</strong> The admin delegated-scope
 * {@code ScopeResolver} was deleted in HD-126 (S3): its three methods are
 * {@code workspace.taxonomy.manage}, {@code project.taxonomy.manage} and the curator set
 * {@code Permission.projectCuration()}, all checked through this class. Keeping two
 * authorization services would have recreated the exact condition — two predicates for
 * one question — that HD-123 exists to remove (§10.4). Do not add a second one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAccessService {

    /** {@link Permission#projectCuration()} as a set, resolved once — it is immutable. */
    private static final PermissionSet PROJECT_CURATION =
            PermissionSet.granting(Permission.projectCuration());

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final IssueRepository issueRepository;
    private final RoleCatalog roleCatalog;
    /** HD-127 §3c: a corrupt role_id is survivable on the list paths, so it must be alertable. */
    private final ProductMetrics metrics;

    /**
     * Resolve the workspace and verify the actor's membership. Throws
     * {@link WorkspaceNotFoundException} (404) for both a non-existent workspace and a
     * non-member — identical to every former inline {@code resolveWorkspace}.
     */
    @Transactional(readOnly = true)
    public WorkspaceContext requireMember(User actor, UUID workspaceId) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        var membership = workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        var role = requireRole(membership.getRole().getId(),
                RoleScope.WORKSPACE, workspace.getId(), RoleScopeViolationSource.WORKSPACE_MEMBERS);
        return new WorkspaceContext(workspace, membership, role, role.permissions());
    }

    /**
     * {@link #requireMember} then resolve the project within that workspace, and resolve
     * the caller's <em>effective project role</em>. Throws
     * {@link ProjectNotFoundException} (404) if the project isn't in the workspace.
     *
     * <p><strong>Renamed from {@code requireProjectMember} (§9.1).</strong> The old name
     * was a documented lie: it has never read {@code project_members}, it verifies
     * <em>workspace</em> membership, and the mismatch cost the project one CLAUDE.md
     * gotcha and an epic's worth of confusion about what "project role" meant. It still
     * does not <em>require</em> a project role — reads are open to every workspace member
     * in v1 (§12) — it resolves one.
     */
    @Transactional(readOnly = true)
    public ProjectContext resolveProject(User actor, UUID workspaceId, UUID projectId) {
        var ws = requireMember(actor, workspaceId);
        var project = projectRepository.findByIdAndWorkspace(projectId, ws.workspace())
                .orElseThrow(ProjectNotFoundException::new);
        return projectContext(actor, ws, project);
    }

    /**
     * {@link #resolveProject} then resolve the issue by number within the project. Throws
     * {@link IssueNotFoundException} (404) if no such issue exists.
     */
    @Transactional(readOnly = true)
    public IssueContext requireIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var pc = resolveProject(actor, workspaceId, projectId);
        var issue = issueRepository.findByProjectAndNumber(pc.project(), issueNumber)
                .orElseThrow(IssueNotFoundException::new);
        return new IssueContext(pc.workspace(), pc.membership(), pc.project(), issue,
                pc.workspaceRole(), pc.workspacePermissions(), pc.projectRole(), pc.permissions());
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Builds the project-scoped half of a {@link ProjectContext} — the effective project
     * role (§5.2) and the permission set it yields.
     *
     * <p>Public so a caller holding an already-resolved workspace and project can get the
     * same answer without re-resolving either.
     *
     * <p><strong>{@code ProjectService.list} deliberately does NOT call this</strong> — it
     * would be an N+1, because this reads {@code project_members} for one project and the
     * list batches all of them into a single query. What must not diverge is the
     * <em>rule</em>, so the three pieces of it are separately public and the list composes
     * exactly those: {@link #requireRole} (scope + ownership on the explicit row),
     * {@link #defaultProjectRole} (the §5.2 fallback chain) and
     * {@link #effectiveProjectPermissions} (the union with the workspace-scoped bypass).
     * There is one implementation of each; a second one is how a list view and a detail
     * view start disagreeing — the HD-98 / HD-116 bug class this epic exists to close.
     */
    @Transactional(readOnly = true)
    public ProjectContext projectContext(User actor, WorkspaceContext ws, Project project) {
        // T1: this is public and takes an already-resolved pair, so it must not simply
        // trust that they belong together — "my caller passes matching arguments" is not a
        // tenancy control, it is a hope, and this is the class the whole codebase relies on
        // for the 404-not-403 rule. The check costs no SELECT: `project.workspace` is a
        // lazy proxy and reading its id does not initialise it.
        if (!project.getWorkspace().getId().equals(ws.workspace().getId())) {
            throw new ProjectNotFoundException();
        }
        var explicit = projectMemberRepository.findByProjectAndUser(project, actor);
        var projectRole = explicit
                .map(m -> requireRole(m.getRole().getId(),
                        RoleScope.PROJECT, ws.workspace().getId(), RoleScopeViolationSource.PROJECT_MEMBERS))
                .orElseGet(() -> defaultProjectRole(ws.workspace(), project));
        return new ProjectContext(ws.workspace(), ws.membership(), project,
                ws.workspaceRole(), ws.permissions(),
                projectRole, explicit.isPresent(),
                effectiveProjectPermissions(ws.permissions(), projectRole));
    }

    /**
     * The project-scoped permissions of <strong>somebody other than the caller</strong>,
     * within an already-resolved project. Empty when that person is not a member of the
     * workspace at all.
     *
     * <p>Exists for exactly one permission — {@link Permission#ISSUE_ASSIGNABLE}, the only
     * entry in the catalog that is about being a <em>subject</em> rather than acting
     * (§6.3). {@code issue.assign} is checked against the actor, through the
     * {@link ProjectContext} resolved once per request; {@code issue.assignable} has to be
     * evaluated against the <em>target</em>, whose roles no per-request resolution can
     * know in advance. That asymmetry is the whole point of the permission, and it is why
     * this is not a violation of Rule P1 (§5.1): it answers a question about a different
     * person, not a second answer to the same question about the caller.
     *
     * <p><strong>Never throws for a stranger.</strong> A user who is not a workspace
     * member yields {@link Optional#empty()}, so the caller can answer with its own
     * value-level error (a 422 "Unknown assignee") rather than a 404 that would tell a
     * member which user ids exist in other tenants. Costs two indexed lookups, and only
     * on a request that actually names an assignee.
     *
     * <p><strong>Nor for a corrupt row belonging to the subject.</strong>
     * {@link #requireRole} throws {@link WorkspaceNotFoundException} when a {@code role_id}
     * fails the scope/ownership assertion — correct when it is the <em>caller's</em> row
     * (fail closed, they get nothing), catastrophic here: one bad {@code project_members}
     * row would turn every assignee-carrying request in the workspace into "workspace not
     * found", for everybody, because of somebody else's data. The row is still logged at
     * ERROR by {@code requireRole}; this degrades the <em>subject</em> to "holds nothing",
     * which the callers read as "cannot be assigned". Fail-closed in the direction that
     * refuses one field value instead of the whole tenant.
     */
    @Transactional(readOnly = true)
    public Optional<PermissionSet> projectPermissionsOf(User subject, ProjectContext ctx) {
        var workspace = ctx.workspace();
        return workspaceMemberRepository.findByWorkspaceAndUser(workspace, subject)
                .map(membership -> {
                    try {
                        var workspaceRole = requireRole(membership.getRole().getId(),
                                RoleScope.WORKSPACE, workspace.getId(), RoleScopeViolationSource.WORKSPACE_MEMBERS);
                        var projectRole = projectMemberRepository
                                .findByProjectAndUser(ctx.project(), subject)
                                .map(m -> requireRole(m.getRole().getId(),
                                        RoleScope.PROJECT, workspace.getId(), RoleScopeViolationSource.PROJECT_MEMBERS))
                                .orElseGet(() -> defaultProjectRole(workspace, ctx.project()));
                        return effectiveProjectPermissions(workspaceRole.permissions(), projectRole);
                    } catch (WorkspaceNotFoundException e) {
                        return PermissionSet.empty();
                    }
                });
    }

    /**
     * The project role a workspace member inherits when they have <em>no</em> explicit
     * {@code project_members} row (§5.2, step 2):
     * {@code projects.default_project_role_id} → {@code workspaces.default_project_role_id}
     * → the built-in <strong>Contributor</strong> — but only while the workspace is
     * {@link ProjectAccessMode#OPEN}. In {@code STRICT} the answer is {@code null}: no
     * project role at all.
     *
     * <p>This is the mechanism that makes the upgrade a no-op. V14 leaves every workspace
     * {@code OPEN} with a NULL default, so every workspace member falls through to
     * Contributor — whose permission set is verbatim what they can do in a project today.
     */
    public RoleView defaultProjectRole(Workspace workspace, Project project) {
        if (workspace.getProjectAccessMode() != ProjectAccessMode.OPEN) {
            return null;
        }
        boolean fromProject = project.getDefaultProjectRoleId() != null;
        var defaultId = fromProject
                ? project.getDefaultProjectRoleId()
                : workspace.getDefaultProjectRoleId();
        if (defaultId == null) {
            return roleCatalog.defaultProjectRole();
        }
        // T2: `default_project_role_id` is a plain FK to roles(id) — the database cannot
        // express "…and it must be a PROJECT role of THIS workspace", so this is the only
        // place that can. Unreachable in S1 (V14 leaves both columns NULL and no write path
        // exists), and live the moment S4/S7 ships the picker: a foreign or workspace-scoped
        // role id written here would otherwise be honoured for EVERY member of this
        // workspace who has no explicit project row — which is nearly everyone (§2.3).
        var role = roleCatalog.view(defaultId);
        if (!isUsableAs(role, RoleScope.PROJECT, workspace.getId())) {
            log.error("{} {} names default_project_role_id {} which is not a PROJECT role of "
                      + "workspace {} (scope={}, owner={}). Falling back to the built-in "
                      + "Contributor. This is a data error: the picker must only offer roles from "
                      + "RoleRepository.findAssignable(id, workspaceId, PROJECT).",
                    fromProject ? "Project" : "Workspace",
                    fromProject ? project.getId() : workspace.getId(),
                    defaultId, workspace.getId(), role.scope(), role.workspaceId());
            // Same signal as reportScopeViolation, from the one call site that recovers rather
            // than refusing: the fallback keeps the workspace working, so without a counter a
            // permanently bad default column is invisible except in the log stream.
            metrics.roleScopeViolation(RoleScopeViolationSource.DEFAULT_PROJECT_ROLE);
            return roleCatalog.defaultProjectRole();
        }
        return role;
    }

    /**
     * <strong>The one way to turn an assigned {@code role_id} into a usable
     * {@link RoleView}</strong> — resolve it through the cache, then enforce the tenancy +
     * scope invariant (H3/T2): it must be of the expected {@link RoleScope}, and it must be
     * either a built-in template ({@code workspaceId == null}) or owned by this workspace.
     *
     * <p>Scope matters as much as ownership, and less obviously. A {@link PermissionSet}
     * is a flat {@code EnumSet} that does not remember which scope its grants came from,
     * so a WORKSPACE role stored in {@code project_members.role_id} would place
     * {@code workspace.edit} and {@code workspace.member.manage} straight into
     * {@code ProjectContext.permissions()} — and every {@code require(...)} downstream
     * would be satisfied honestly. Nothing upstream can catch it: the role belongs to the
     * right workspace and the row is otherwise valid.
     *
     * <p><strong>Public because the two list endpoints cannot call
     * {@link #projectContext}</strong> and must not therefore skip the check.
     * {@code ProjectService.list} and {@code WorkspaceService.listForUser} batch their
     * membership rows into one query to avoid an N+1, so they resolve role ids themselves;
     * before this was exposed they were the two paths that read a {@code role_id} without
     * asserting anything, which is how a list view and a detail view start disagreeing
     * about what someone may do — the HD-98/HD-116 bug class this epic exists to close.
     * Costs no query on a warm cache, so a page of 50 projects pays nothing for it.
     *
     * <p>Throws {@link WorkspaceNotFoundException} rather than a 500 because it is a
     * tenancy failure and the caller must not learn more than "no".
     *
     * @param source the table the id came from, for the ERROR log — the operator's only
     *               clue as to which row is wrong
     */
    @Transactional(readOnly = true)
    public RoleView requireRole(UUID roleId, RoleScope expected, UUID workspaceId,
                                RoleScopeViolationSource source) {
        var role = roleCatalog.view(roleId);
        if (!isUsableAs(role, expected, workspaceId)) {
            reportScopeViolation(role, expected, workspaceId, source);
            throw new WorkspaceNotFoundException();
        }
        return role;
    }

    /**
     * <strong>{@link #requireRole}, degraded instead of refusing</strong> — for
     * <em>collection</em> and <em>third-party</em> reads only (HD-127 §3b).
     *
     * <p>The assertion is the same; what differs is the blast radius of failing it. On a
     * single-resource read the answer is about the caller and one 404 is proportionate. On
     * a list it is not: one corrupt {@code role_id} would 404 the caller's <em>entire</em>
     * workspace list, project list or People tab because of one row — often somebody else's
     * row. So these four call sites — {@code WorkspaceService.listForUser},
     * {@code WorkspaceService.listMembers}, {@code ProjectService.list},
     * {@code ProjectService.listMembers} — keep the entry and render it with no role.
     *
     * <p><strong>It must NOT be applied to {@code requireMember} or
     * {@code projectContext}.</strong> Degrading the caller's own workspace membership is
     * not "empty permissions": in an {@code OPEN} workspace the project-role fallback is
     * independent of the workspace role, so an empty workspace role would still leave the
     * caller inheriting <strong>Contributor in every project of the workspace</strong>.
     * Turning a 404 into Contributor-everywhere is a widening, and an invisible one. The
     * consequence of keeping the 404 there is intended: a corrupt row makes the workspace
     * appear in {@code GET /workspaces} and 404 on {@code GET /workspaces/{id}} — because
     * <em>a list is a directory, a detail read is an authorization</em>. Loud, bounded to
     * one entry, and strictly better than a user who can see no workspace at all.
     *
     * <p><strong>Empty means empty, and never the foreign role.</strong> Callers render
     * {@code myPermissions} as {@code []} (never absent — an absent field makes a client
     * fall back to permissive rendering) and the role key/name as JSON {@code null}. The one
     * genuine leak in the vicinity is precisely that name: {@code listMembers} calls the
     * assertion <em>because</em> it renders it, so emitting the role we just refused would
     * defeat the whole check.
     *
     * <p><strong>The degrade cannot mask a tenancy bug.</strong> Whether somebody is a member
     * of a workspace is decided by the {@code workspace_members} row and its
     * {@code workspace_id} join, before and independently of this; the role id answers only
     * <em>what may they do</em>, and degrading it can only ever narrow —
     * {@code PermissionSet.empty()} is the floor. Nothing becomes reachable that was not
     * already reachable.
     *
     * <p><strong>Keyed on the assertion failing, never on the role being absent.</strong>
     * {@code RolePermissionCache.byId} throws {@code IllegalStateException} for an id that
     * resolves to no row (a dangling FK — impossible while {@code ON DELETE} is NO ACTION,
     * hence a 500 by design), and that is deliberately allowed to propagate. Do <strong>not
     * </strong> widen this to {@code catch (Exception)}: a broken migration would then turn
     * into a silent empty permission set on every list in the product.
     */
    @Transactional(readOnly = true)
    public Optional<RoleView> resolveRoleOrDegrade(UUID roleId, RoleScope expected,
                                                   UUID workspaceId,
                                                   RoleScopeViolationSource source) {
        var role = roleCatalog.view(roleId);
        if (!isUsableAs(role, expected, workspaceId)) {
            reportScopeViolation(role, expected, workspaceId, source);
            return Optional.empty();
        }
        return Optional.of(role);
    }

    /**
     * One ERROR line and one counter increment for both paths above, so the two cannot drift
     * apart in what they report.
     *
     * <p>The log names the table, the id and the expected scope — the operator's only clue
     * as to which row is wrong. The counter exists because the degrade makes the condition
     * <em>survivable</em>: a permanently corrupt row would otherwise log at ERROR on every
     * list request for ever, which is a Loki bill rather than a signal.
     */
    private void reportScopeViolation(RoleView role, RoleScope expected, UUID workspaceId,
                                      RoleScopeViolationSource source) {
        log.error("{} references role {} (key={}, scope={}, owner={}) which is not a {} role "
                  + "of workspace {}. Refusing the request.",
                source.tag(), role.id(), role.key(), role.scope(), role.workspaceId(),
                expected, workspaceId);
        metrics.roleScopeViolation(source);
    }

    private boolean isUsableAs(RoleView role, RoleScope expected, UUID workspaceId) {
        return role.scope() == expected
                && (role.workspaceId() == null || role.workspaceId().equals(workspaceId));
    }

    /**
     * {@code permissionsOf(effectiveProjectRole)} ∪ the two workspace-scoped
     * "in every project of this workspace" grants, if the workspace set holds them (§9.1):
     * <ul>
     *   <li>{@link Permission#PROJECT_CURATE_ALL} → the curator set
     *       {@link Permission#projectCuration()}. <strong>This is the one the built-in
     *       Owner and Admin hold</strong>, and it is today's implicit workspace-admin
     *       bypass made explicit (§17.2) — the same four permissions
     *       {@code ScopeResolver.requireProjectCurator} let them through before HD-126, and not
     *       one more;</li>
     *   <li>{@link Permission#PROJECT_ADMINISTER_ALL} → every project permission. Held by
     *       no built-in; grantable to a custom role in S4.</li>
     * </ul>
     *
     * <p>Both honour {@code ownRequired}, so neither hands anyone unrestricted
     * {@code comment.edit}: administering a project is not permission to edit someone
     * else's words (§17.3).
     */
    public PermissionSet effectiveProjectPermissions(PermissionSet workspacePermissions,
                                                     RoleView projectRole) {
        var base = projectRole == null ? PermissionSet.empty() : projectRole.permissions();
        if (workspacePermissions.has(Permission.PROJECT_CURATE_ALL)) {
            base = base.union(PROJECT_CURATION);
        }
        if (workspacePermissions.has(Permission.PROJECT_ADMINISTER_ALL)) {
            base = base.union(PermissionSet.allOf(RoleScope.PROJECT));
        }
        return base;
    }
}
